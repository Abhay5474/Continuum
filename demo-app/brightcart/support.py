"""The support desk's AI features, each built on a Continuum call.

- The assistant: a tool-using agent (order lookup, refunds, preferences) with
  long-term memory per customer and Continuum's loop detector watching it.
- Triage: every ticket classified as JSON, urgent ones sent with a deadline
  and CRITICAL priority, bulk runs as BACKGROUND.
- Reply drafts: "careful" drafts use the HIGH_QUALITY routing mode and ask
  Continuum to measure confidence.
- Photo and file questions: vision, and pasted data that Continuum's context
  transformers rewrite for the model.
"""
from __future__ import annotations

import json
import uuid

from . import store
from .continuum import Continuum, ContinuumError

MAX_TOOL_ROUNDS = 4

TOOLS = [
    {"type": "function", "function": {
        "name": "lookup_order",
        "description": "Look up one Brightcart order by its id (like BC-1042): item, status, delivery, "
                       "amount and whether it can be returned.",
        "parameters": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]}}},
    {"type": "function", "function": {
        "name": "list_my_orders",
        "description": "List the current customer's orders.",
        "parameters": {"type": "object", "properties": {}}}},
    {"type": "function", "function": {
        "name": "start_refund",
        "description": "Start a refund for a returnable order. Only call this after the customer has clearly "
                       "asked for a refund and the order is returnable.",
        "parameters": {"type": "object", "properties": {
            "order_id": {"type": "string"},
            "reason": {"type": "string", "description": "The customer's reason, in a few words"},
            "collect_item": {"type": "boolean", "description": "Book a courier to collect the item"}},
            "required": ["order_id", "reason"]}}},
    {"type": "function", "function": {
        "name": "save_preference",
        "description": "Remember a lasting preference or fact the customer tells you (contact method, sizes, "
                       "delivery instructions) for future conversations.",
        "parameters": {"type": "object", "properties": {"preference": {"type": "string"}},
                       "required": ["preference"]}}},
]


def memory_scope(customer_id: str) -> str:
    return f"customer-{customer_id}"


def system_prompt(cust: dict | None, memories: list) -> str:
    who = (f"You are helping {cust['name']} ({cust['tier']} member since {cust['since']}, {cust['city']}, "
           f"customer id {cust['id']})." if cust else "The customer is not signed in.")
    known = ""
    if memories:
        known = "\nWhat we remember about this customer from earlier conversations:\n" + \
                "\n".join(f"- {m}" for m in memories)
    return (
        "You are Brightcart's customer support assistant. Be warm, brief and specific. "
        "Use the tools to look up orders instead of guessing; never invent order details. "
        f"{who}{known}\n\n{store.POLICY}\n\n"
        "If a message contains a placeholder like [REDACTED_CARD_NUMBER] or [REDACTED_CREDENTIAL], the customer "
        "shared sensitive data that our privacy filter removed: tell them kindly you never need it, and carry on."
    )


class Assistant:
    def __init__(self, continuum: Continuum, refunds):
        self.c = continuum
        self.refunds = refunds  # starts refund workflows (see app.py)

    # ---- the chat assistant ------------------------------------------------

    def reply(self, customer_id: str, history: list, *, mode: str | None = None, use_tools: bool = True) -> dict:
        cust = store.customer(customer_id)
        last_user = next((m["content"] for m in reversed(history) if m.get("role") == "user"), "")
        memories = []
        if cust:
            try:
                found = self.c.recall(memory_scope(customer_id), last_user, action=f"Recall what we know about "
                                                                                     f"{cust['name']}")
                # The best few by Continuum's ranking (meaning, salience, recency).
                memories = [m["content"] for m in found if isinstance(m, dict) and m.get("content")][:4]
            except ContinuumError:
                memories = []

        messages = [{"role": "system", "content": system_prompt(cust, memories)}] + [
            {"role": m["role"], "content": m["content"]} for m in history[-30:]
            if m.get("role") in ("user", "assistant") and isinstance(m.get("content"), str)]

        actions, steps, progress = [], [], []
        run_id = "chat-" + uuid.uuid4().hex[:10]
        answer = None
        for _ in range(MAX_TOOL_ROUNDS + 1):
            answer = self.c.chat(messages, action=f"Assistant answers {cust['name'] if cust else 'a visitor'}",
                                 tools=TOOLS if use_tools else None, routing_mode=mode)
            msg = (answer.get("choices") or [{}])[0].get("message") or {}
            calls = msg.get("tool_calls") or []
            if not calls:
                break
            messages.append({"role": "assistant", "content": msg.get("content"), "tool_calls": calls})
            for call in calls:
                name = call.get("function", {}).get("name", "")
                try:
                    args = json.loads(call.get("function", {}).get("arguments") or "{}")
                except ValueError:
                    args = {}
                result = self._run_tool(name, args, customer_id)
                step = f"{name}({json.dumps(args, sort_keys=True)})"
                progress.append(step not in steps)
                steps.append(step)
                actions.append({"tool": name, "args": args, "result": result})
                messages.append({"role": "tool", "tool_call_id": call.get("id"), "content": json.dumps(result)})
            # Ask Continuum whether this agent is going round in circles before
            # spending another model call on it.
            verdict = self.c.inspect_loop(run_id, steps, progress, action="Loop check on the assistant's tool calls")
            if verdict.get("looping"):
                return self._out(None, actions, memories, loop=verdict,
                                 text="I seem to be going round in circles here, so I've passed this to a human "
                                      "colleague who will reply shortly.")
        return self._out(answer, actions, memories)

    def _run_tool(self, name: str, args: dict, customer_id: str) -> dict:
        if name == "lookup_order":
            o = store.order(str(args.get("order_id", "")))
            if not o:
                return {"error": f"No order {args.get('order_id')}"}
            if o["customer"] != customer_id:
                return {"error": "That order belongs to a different customer; ask them to sign in."}
            view = {k: o[k] for k in ("id", "item", "status", "amount", "card", "returnable", "returnable_reason")
                    if k in o}
            view.update({k: o[k] for k in ("delivered_on", "eta", "refund") if o.get(k)})
            return view
        if name == "list_my_orders":
            return {"orders": [{"id": o["id"], "item": o["item"], "status": o["status"]}
                               for o in store.orders_for(customer_id)]}
        if name == "start_refund":
            o = store.order(str(args.get("order_id", "")))
            if not o or o["customer"] != customer_id:
                return {"error": "No such order for this customer."}
            if not o["returnable"]:
                return {"error": f"Not refundable: {o['returnable_reason']}."}
            try:
                run = self.refunds(o["id"], str(args.get("reason") or "customer request"),
                                   bool(args.get("collect_item", False)), source="assistant")
                return {"started": True, "refundRun": run["workflowId"],
                        "note": "Refund started; the customer gets an email when it is done."}
            except ContinuumError as e:
                return {"error": f"Could not start the refund: {e.message}"}
        if name == "save_preference":
            pref = str(args.get("preference", "")).strip()
            if not pref:
                return {"error": "empty preference"}
            try:
                stored = self.c.remember(memory_scope(customer_id), pref, action="Remember a customer preference")
                # Continuum keeps one copy of a fact; telling it again only strengthens it.
                return {"saved": True, "alreadyKnown": bool((stored or {}).get("reinforced"))}
            except ContinuumError as e:
                return {"error": e.message}
        return {"error": f"unknown tool {name}"}

    @staticmethod
    def _out(answer, actions, memories, loop=None, text=None) -> dict:
        msg = ((answer or {}).get("choices") or [{}])[0].get("message") or {}
        meta = (answer or {}).get("continuum") or {}
        return {
            "reply": text if text is not None else (msg.get("content") or "").strip() or "(no answer)",
            "model": (answer or {}).get("model"),
            "meta": meta,
            "actions": actions,
            "memories": memories,
            "loop": loop,
        }

    # ---- loop drill --------------------------------------------------------

    def loop_drill(self) -> dict:
        """A deliberately broken agent: it looks up the same order again and again.

        Real models rarely loop on cue, so the drill replays the steps such an
        agent would take and asks Continuum's loop detector after each one.
        """
        run_id = "drill-" + uuid.uuid4().hex[:8]
        steps, verdict = [], {}
        for i in range(1, 9):
            steps.append('lookup_order({"order_id": "BC-1042"})')
            verdict = self.c.inspect_loop(run_id, list(steps), [i == 1] + [False] * (i - 1),
                                          action=f"Loop drill: step {i}")
            if verdict.get("looping") or verdict.get("enabled") is False:
                return {"stoppedAt": i, "verdict": verdict, "steps": steps}
        return {"stoppedAt": None, "verdict": verdict, "steps": steps}

    # ---- triage ------------------------------------------------------------

    def triage(self, ticket: dict, *, bulk: bool = False) -> dict:
        cust = store.customer(ticket.get("customer"))
        urgent_hint = bool(cust and cust.get("tier") == "Platinum") or ticket.get("channel") == "live chat"
        criticality = "BACKGROUND" if bulk else ("CRITICAL" if urgent_hint else "NORMAL")
        deadline = 15000 if (urgent_hint and not bulk) else None
        prompt = [
            {"role": "system", "content":
                "You triage Brightcart support tickets. Reply with JSON only: "
                '{"category": "refund|delivery|product|payment|account|abuse|other", '
                '"urgency": 1-5, "sentiment": "angry|frustrated|neutral|happy", '
                '"summary": "one sentence", "next_action": "one short sentence", '
                '"needs_refund": true|false, "contains_sensitive_data": true|false}'},
            {"role": "user", "content": f"Ticket {ticket['id']} via {ticket['channel']} from "
                                        f"{cust['name'] + ' (' + cust['tier'] + ')' if cust else 'unknown sender'}\n"
                                        f"Subject: {ticket['subject']}\n\n{ticket['body']}"},
        ]
        answer = self.c.chat(prompt, action=f"Triage {ticket['id']}" + (" (bulk)" if bulk else ""),
                             json_mode=True, criticality=criticality, deadline_ms=deadline, max_tokens=300,
                             temperature=0)
        content = ((answer.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        result = _json_object(content)
        return {"ticket": ticket["id"], "triage": result, "raw": None if result else content,
                "criticality": criticality, "deadlineMs": deadline, "meta": answer.get("continuum") or {},
                "model": answer.get("model")}

    def draft_reply(self, ticket: dict, careful: bool) -> dict:
        cust = store.customer(ticket.get("customer"))
        orders = store.orders_for(cust["id"]) if cust else []
        context = "\n".join(f"- {o['id']}: {o['item']}, {o['status']}, {o['amount']:.2f}, "
                            f"{'returnable' if o['returnable'] else 'not returnable'} ({o['returnable_reason']})"
                            for o in orders)
        messages = [
            {"role": "system", "content": "Draft a reply from Brightcart support. Warm, specific, under 120 words, "
                                          "no placeholders, sign off as 'Brightcart Support'.\n\n" + store.POLICY},
            {"role": "user", "content": f"Customer: {cust['name'] + ', ' + cust['tier'] if cust else 'unknown'}\n"
                                        f"Their orders:\n{context or '- none'}\n\n"
                                        f"Ticket {ticket['id']}: {ticket['subject']}\n{ticket['body']}"},
        ]
        answer = self.c.chat(messages, action=f"Draft {'a careful' if careful else 'a'} reply to {ticket['id']}",
                             routing_mode="HIGH_QUALITY" if careful else None, measure=careful)
        msg = ((answer.get("choices") or [{}])[0].get("message") or {})
        return {"ticket": ticket["id"], "draft": (msg.get("content") or "").strip(),
                "meta": answer.get("continuum") or {}, "model": answer.get("model")}

    # ---- help centre ----------------------------------------------------------

    def help_centre(self, question: str) -> dict:
        """The public help centre: the same store policy for every shopper, no
        personal details and no tools, so a question many people ask is the same
        request each time, and Continuum's semantic cache can answer it again
        without calling a model."""
        messages = [
            {"role": "system", "content": "You answer Brightcart help-centre questions in two or three sentences, "
                                          "from the store policy only.\n\n" + store.POLICY},
            {"role": "user", "content": question},
        ]
        answer = self.c.chat(messages, action="Help centre answer")
        msg = ((answer.get("choices") or [{}])[0].get("message") or {})
        return {"answer": (msg.get("content") or "").strip(), "meta": answer.get("continuum") or {},
                "model": answer.get("model")}

    # ---- photo and file questions -----------------------------------------

    def photo(self, data_url: str, question: str, customer_id: str | None) -> dict:
        cust = store.customer(customer_id)
        messages = [
            {"role": "system", "content": "You check product photos for Brightcart support. Describe only what you "
                                          "can see. Say whether visible damage would qualify for a refund under "
                                          "the policy, and what the customer should do next.\n\n" + store.POLICY},
            {"role": "user", "content": [
                {"type": "text", "text": (question or "Is this item damaged?") +
                                         (f" (customer: {cust['name']})" if cust else "")},
                {"type": "image_url", "image_url": {"url": data_url}}]},
        ]
        answer = self.c.chat(messages, action="Check a product photo")
        msg = ((answer.get("choices") or [{}])[0].get("message") or {})
        return {"answer": (msg.get("content") or "").strip(), "meta": answer.get("continuum") or {},
                "model": answer.get("model")}

    def ask_file(self, filename: str, text: str, question: str) -> dict:
        messages = [
            {"role": "system", "content": "You are a Brightcart operations analyst. Answer from the data the user "
                                          "pasted; say so when the data does not contain the answer."},
            # Fenced and tagged: Continuum's context transformers rewrite a whole
            # message or a fenced block, never data loose inside prose.
            {"role": "user", "content": f"{question}\n\nHere is {filename}:\n\n```{_fence_tag(filename)}\n"
                                        f"{text.rstrip()}\n```"},
        ]
        answer = self.c.chat(messages, action=f"Ask about {filename}")
        msg = ((answer.get("choices") or [{}])[0].get("message") or {})
        return {"answer": (msg.get("content") or "").strip(), "meta": answer.get("continuum") or {},
                "model": answer.get("model")}


def _fence_tag(filename: str) -> str:
    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else ""
    return {"csv": "csv", "tsv": "tsv", "log": "log", "eml": "eml"}.get(ext, "")


def _json_object(text: str):
    text = (text or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        text = text[text.find("{"):] if "{" in text else text
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        v = json.loads(text[start:end + 1])
        return v if isinstance(v, dict) else None
    except ValueError:
        return None
