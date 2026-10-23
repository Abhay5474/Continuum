// Every Continuum feature, in the store's own terms. The X-ray uses the names
// and icons; the tour adds a scenario for each (see tour.js).

export const GROUPS = [
  { key: "connect", name: "Talk to any AI", icon: "🔌", tint: "#e9f0ff" },
  { key: "protect", name: "Protect the store", icon: "🛡️", tint: "#fdeaea" },
  { key: "save", name: "Spend less", icon: "💰", tint: "#e4f6ee" },
  { key: "quality", name: "Trust the answers", icon: "✅", tint: "#e8f8f3" },
  { key: "uptime", name: "Never go down", icon: "🛟", tint: "#fff4dc" },
  { key: "traffic", name: "Handle the rush", icon: "🚦", tint: "#fff0e6" },
  { key: "learn", name: "Remember and learn", icon: "🧠", tint: "#f3ebfb" },
  { key: "workflows", name: "Run real processes", icon: "🔄", tint: "#e6f6fb" },
  { key: "specialists", name: "Bring your own models", icon: "🧩", tint: "#eef0ff" },
  { key: "observe", name: "See why", icon: "🔍", tint: "#f1ece7" },
];

// need: "presenter" — flips a Continuum console switch; "operator" — engine-wide switch.
export const FEATURES = [
  { key: "gateway", group: "connect", icon: "🔌", name: "AI gateway",
    blurb: "One Continuum key, any AI model. The store never talks to Google or Groq directly." },
  { key: "streaming", group: "connect", icon: "⌨️", name: "Streaming",
    blurb: "Replies appear word by word, like a person typing, instead of after a long wait." },
  { key: "tools", group: "connect", icon: "🛠️", name: "Tool calling",
    blurb: "The assistant looks up real orders in the store instead of guessing." },
  { key: "json-mode", group: "connect", icon: "🧾", name: "JSON mode",
    blurb: "Support tickets come back as clean data the desk can sort and colour-code." },
  { key: "vision", group: "connect", icon: "📷", name: "Vision",
    blurb: "A customer sends a photo of a cracked mug; the AI looks at the damage." },
  { key: "model-catalogue", group: "connect", icon: "📚", name: "Model catalogue",
    blurb: "Continuum keeps the list of working models current, so the store never names a dead one." },

  { key: "prompt-firewall", group: "protect", icon: "🛡️", name: "Prompt firewall",
    blurb: "Card numbers and PINs are removed before any AI sees them; prompt attacks are blocked." },
  { key: "loop-detection", group: "protect", icon: "🔁", name: "Loop detection",
    blurb: "An AI agent stuck repeating itself is stopped before it burns money." },
  { key: "cost-limits", group: "protect", icon: "💸", name: "Cost limits", need: "presenter",
    blurb: "A runaway batch job hits a spending cap instead of the monthly bill." },

  { key: "semantic-cache", group: "save", icon: "⚡", name: "Semantic cache",
    blurb: "Asked before, even in other words? Answered instantly, with no AI call and no cost." },
  { key: "model-cascade", group: "save", icon: "🪜", name: "Model cascade",
    blurb: "Easy questions go to a small, cheap model; only hard ones reach the big one." },
  { key: "prompt-compression", group: "save", icon: "🗜️", name: "Prompt compression", need: "presenter",
    blurb: "Wordy instructions are squeezed before they are paid for, without losing meaning." },
  { key: "context-optimizer", group: "save", icon: "🧠", name: "Context optimizer",
    blurb: "A 62-message chat sends only what the new question needs, not the whole history." },
  { key: "context-transformers", group: "save", icon: "📄", name: "Context transformers",
    blurb: "Spreadsheets, logs and email threads become compact, AI-ready text." },
  { key: "routing-modes", group: "save", icon: "🧭", name: "Routing modes",
    blurb: "Each request picks its priority: cheapest, fastest or best quality." },

  { key: "quality-gate", group: "quality", icon: "✅", name: "Quality gate",
    blurb: "Every answer is scored: did it answer the question, completely and on topic?" },
  { key: "confidence", group: "quality", icon: "🎯", name: "Confidence",
    blurb: "Continuum asks again and checks the model agrees with itself before you trust it." },
  { key: "verification-engine", group: "quality", icon: "🕸️", name: "Verification engine", need: "presenter",
    blurb: "A small team of AI agents checks each claim before the answer is sent." },
  { key: "semantic-breaker", group: "quality", icon: "🚫", name: "Semantic breaker", need: "presenter",
    blurb: "A model whose answers quietly get worse is benched automatically." },

  { key: "failover", group: "uptime", icon: "🔀", name: "Provider failover", need: "presenter",
    blurb: "Google goes down? The answer comes from another provider, and the customer never knows." },
  { key: "degradation", group: "uptime", icon: "🪂", name: "Degradation ladder", need: "presenter",
    blurb: "Even if every AI provider fails, customers get a helpful reply instead of an error." },
  { key: "hedging", group: "uptime", icon: "🏁", name: "Hedging", need: "operator",
    blurb: "A slow model is raced by a second one; the first good answer wins." },
  { key: "ai-chaos", group: "uptime", icon: "🧪", name: "AI chaos drills", need: "presenter",
    blurb: "Rehearse AI failures safely on the live store, like a fire drill." },

  { key: "admission", group: "traffic", icon: "🚦", name: "Admission control", need: "presenter",
    blurb: "A burst of nine tickets is let in at a pace the AI provider can handle." },
  { key: "priority-deadlines", group: "traffic", icon: "⏱️", name: "Priority & deadlines",
    blurb: "Urgent tickets go first; a deadline that cannot be met is refused up front." },
  { key: "billing", group: "traffic", icon: "📊", name: "Plans & quota", need: "presenter",
    blurb: "Every AI call is metered against the store's monthly plan." },

  { key: "memory", group: "learn", icon: "💾", name: "Long-term memory",
    blurb: "The assistant remembers that Priya wants deliveries after 6 pm, next time too." },
  { key: "adaptive-policy", group: "learn", icon: "📐", name: "Adaptive policy", need: "presenter",
    blurb: "Teach a house rule once; every answer follows it from then on." },
  { key: "autopilot", group: "learn", icon: "👍", name: "Autopilot",
    blurb: "Customers' 👍 and 👎 tune which models Continuum prefers." },

  { key: "workflows", group: "workflows", icon: "🔄", name: "Durable workflows",
    blurb: "A refund runs step by step and survives crashes, restarts and retries." },
  { key: "saga", group: "workflows", icon: "↩️", name: "Saga compensation",
    blurb: "If the courier fails, the refund and restock already done are undone, in order." },
  { key: "exactly-once", group: "workflows", icon: "1️⃣", name: "Exactly-once steps",
    blurb: "A payment reply gets lost and is retried; the customer is still refunded only once." },
  { key: "webhooks", group: "workflows", icon: "🔏", name: "Signed webhooks",
    blurb: "Continuum tells the store when a refund is done, with a signature it can verify." },

  { key: "pipelines", group: "specialists", icon: "🧩", name: "Pipelines",
    blurb: "Upload a receipt, get a verdict: one call runs the whole check." },
  { key: "specialists", group: "specialists", icon: "🔬", name: "Specialists",
    blurb: "The store's own receipt reader gives the AI facts, so it does not have to guess." },

  { key: "provenance", group: "observe", icon: "🔍", name: "Decision provenance", need: "presenter",
    blurb: "Ask “why this answer?” and see every decision Continuum made for it." },
];

export const BY_KEY = Object.fromEntries(FEATURES.map((f) => [f.key, f]));
