import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { portal } from "../api";
import { Chip, Ghost, Pill } from "../system/hub";
import { Table, TH, TR, TD } from "../system/controls";
import { useToast, Spinner, CopyButton } from "../components/ui";
import { dateTimeOf, dateOf } from "../system/time";

/**
 * Account & settings: change password / email, manage API keys (name, last-used,
 * revoke), invite teammates, and delete the account (GDPR).
 */
export default function Settings() {
  const loggedIn = !!portal.session();
  const nav = useNavigate();
  const toast = useToast();

  const [me, setMe] = useState<any>(null);
  const [keys, setKeys] = useState<any[]>([]);
  const [invites, setInvites] = useState<any[]>([]);
  const [members, setMembers] = useState<any[]>([]);

  // forms
  const [curPw, setCurPw] = useState("");
  const [newPw, setNewPw] = useState("");
  const [pwBusy, setPwBusy] = useState(false);
  const [email, setEmail] = useState("");
  const [emailBusy, setEmailBusy] = useState(false);
  const [keyLabel, setKeyLabel] = useState("");
  const [newKey, setNewKey] = useState("");
  const [inviteEmail, setInviteEmail] = useState("");
  const [confirmDelete, setConfirmDelete] = useState("");

  const load = () => {
    portal.me().then((m) => { setMe(m); setEmail(m.email ?? ""); }).catch(() => {});
    portal.keys().then(setKeys).catch(() => {});
    portal.invites().then(setInvites).catch(() => {});
    portal.members().then(setMembers).catch(() => {});
  };
  useEffect(() => {
    if (loggedIn) load();
  }, [loggedIn]);

  if (!loggedIn) {
    return (
      <div className="plane mx-auto mt-16 max-w-md p-8 text-center">
        <div className="text-3xl">⚙️</div>
        <h1 className="mt-2 text-[22px] font-semibold tracking-tight">Account settings</h1>
        <p className="mt-1 text-sm text-slate-400 max-w-2xl leading-relaxed">Sign in through the Developer Portal first.</p>
        <a href="/portal" className="mt-4 inline-block rounded-lg bg-gradient-to-r from-aurora to-neon px-4 py-2 text-sm font-semibold text-ink">
          Open Developer Portal →
        </a>
      </div>
    );
  }

  const changePassword = async () => {
    setPwBusy(true);
    try {
      await portal.changePassword(curPw, newPw);
      toast("Password updated ✓", "success");
      setCurPw(""); setNewPw("");
    } catch (e: any) {
      toast(e?.message ?? "Could not change password", "error");
    } finally { setPwBusy(false); }
  };
  const changeEmail = async () => {
    setEmailBusy(true);
    try {
      await portal.changeEmail(email);
      toast("Email updated ✓", "success");
      load();
    } catch (e: any) {
      toast(e?.message ?? "Could not change email", "error");
    } finally { setEmailBusy(false); }
  };
  const issueKey = async () => {
    try {
      const r = await portal.issueKey(keyLabel || undefined);
      setNewKey(r.apiKey);
      setKeyLabel("");
      toast("API key created ✓", "success");
      portal.keys().then(setKeys);
    } catch (e: any) {
      toast(e?.message ?? "Could not create key", "error");
    }
  };
  const revokeKey = async (id: number) => {
    if (!confirm("Revoke this key? Apps using it will stop working immediately.")) return;
    try {
      await portal.revokeKey(id);
      toast("Key revoked", "success");
      portal.keys().then(setKeys);
    } catch (e: any) {
      toast(e?.message ?? "Could not revoke", "error");
    }
  };
  const sendInvite = async () => {
    try {
      const r = await portal.invite(inviteEmail);
      // When mail is not configured the link is the deliverable, so it is shown
      // rather than left in a response the inviter never sees.
      toast(
        r.emailSent ? `Invite emailed to ${r.email}` : `Invite created — copy the link to send it`,
        "success"
      );
      setInviteEmail("");
      portal.invites().then(setInvites);
      portal.members().then(setMembers).catch(() => {});
    } catch (e: any) {
      toast(e?.message ?? "Could not invite", "error");
    }
  };
  const revokeInvite = async (id: number) => {
    try {
      await portal.revokeInvite(id);
      toast("Invite revoked", "success");
      portal.invites().then(setInvites);
    } catch (e: any) {
      toast(e?.message ?? "Could not revoke", "error");
    }
  };
  const copyLink = (link: string) => {
    navigator.clipboard?.writeText(new URL(link, window.location.origin).toString());
    toast("Invite link copied", "success");
  };
  const deleteAccount = async () => {
    try {
      await portal.deleteAccount();
      portal.logout();
      toast("Account deleted", "success");
      nav("/");
    } catch (e: any) {
      toast(e?.message ?? "Could not delete account", "error");
    }
  };

  return (
    <div className="mx-auto max-w-3xl space-y-6 animate-fade-up">
      <div>
        <div className="flex items-center gap-2.5">
          <Chip glyph="chip" tone="accent" size={28} />
          <h1 className="text-[20px] font-semibold tracking-[-0.011em] text-slate-100">Account Settings</h1>
        </div>
        {me && <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">{me.email} · <span className="font-mono">{me.id}</span></p>}
      </div>

      {/* API keys */}
      <Section title="API keys" subtitle="Name a key so you remember what it's for. Keys are shown once.">
        <div className="flex flex-wrap gap-2">
          <input value={keyLabel} onChange={(e) => setKeyLabel(e.target.value)} placeholder="Key name (e.g. production)"
            className="min-w-0 flex-1 field" />
          <button onClick={issueKey} className="rounded-lg bg-gradient-to-r from-aurora to-neon px-4 py-2 text-sm font-semibold text-ink">
            Create key
          </button>
        </div>
        {newKey && (
          <div className="mt-2 flex items-center gap-2 rounded bg-ink p-2">
            <code className="min-w-0 flex-1 break-all font-mono text-xs text-emerald-300">{newKey}</code>
            <span className="whitespace-nowrap text-[10px] text-slate-500">shown once</span>
            <CopyButton text={newKey} />
          </div>
        )}
        <div className="mt-3" data-guide="settings-keys">
          <Table
            minWidth={520}
            head={
              <tr>
                <TH>Name</TH>
                <TH>Prefix</TH>
                <TH>Last used</TH>
                <TH>Status</TH>
                <TH align="right" width={90} />
              </tr>
            }
          >
            {keys.map((k) => (
              <TR key={k.id}>
                <TD>{k.label || <span className="text-slate-500">unnamed</span>}</TD>
                <TD className="font-mono">{k.prefix}…</TD>
                <TD muted>{k.lastUsedAt ? dateTimeOf(k.lastUsedAt) : "never"}</TD>
                <TD>
                  <Pill tone={k.active ? "ok" : "bad"} dot>
                    {k.active ? "Active" : "Revoked"}
                  </Pill>
                </TD>
                <TD align="right">
                  {k.active && (
                    <Ghost tone="danger" onClick={() => revokeKey(k.id)}>
                      Revoke
                    </Ghost>
                  )}
                </TD>
              </TR>
            ))}
            {keys.length === 0 && (
              <TR>
                <TD muted className="py-4">
                  No keys yet.
                </TD>
              </TR>
            )}
          </Table>
        </div>
      </Section>

      {/* password */}
      <Section title="Change password">
        <div className="grid gap-2 sm:grid-cols-2">
          <input type="password" value={curPw} onChange={(e) => setCurPw(e.target.value)} placeholder="Current password"
            className="field" />
          <input type="password" value={newPw} onChange={(e) => setNewPw(e.target.value)} placeholder="New password (min 6)"
            className="field" />
        </div>
        <button onClick={changePassword} disabled={pwBusy || !curPw || !newPw}
          className="mt-3 flex items-center gap-2 rounded-lg bg-[color:var(--accent-strong)] px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
          {pwBusy && <Spinner />} Update password
        </button>
      </Section>

      {/* email */}
      <Section title="Change email">
        <div className="flex flex-wrap gap-2">
          <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com"
            className="min-w-0 flex-1 field" />
          <button onClick={changeEmail} disabled={emailBusy || !email}
            className="flex items-center gap-2 rounded-lg bg-[color:var(--accent-strong)] px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50">
            {emailBusy && <Spinner />} Save
          </button>
        </div>
      </Section>

      {/* team */}
      <Section
        title="Team"
        subtitle="Anyone who accepts an invite signs in with their own password and works in this account."
      >
        {members.length > 0 && (
          <div className="mb-3 space-y-1">
            {members.map((m) => (
              <div key={m.developerId} className="flex items-center gap-2 text-xs">
                <span className="text-slate-300">{m.name}</span>
                <span className="text-slate-500">{m.email}</span>
                <span className="rounded bg-edge/60 px-1.5 py-0.5 text-[10px] text-slate-400">{m.role}</span>
              </div>
            ))}
          </div>
        )}
        <div className="flex flex-wrap gap-2">
          <input value={inviteEmail} onChange={(e) => setInviteEmail(e.target.value)} placeholder="teammate@example.com"
            className="min-w-0 flex-1 field" />
          <button onClick={sendInvite} disabled={!inviteEmail}
            className="rounded-lg border border-edge px-4 py-2 text-sm hover:border-aurora/50 disabled:opacity-50">
            Invite
          </button>
        </div>
        {invites.length > 0 && (
          <div className="mt-2 space-y-1">
            {invites.map((i) => (
              <div key={i.id} className="flex flex-wrap items-center gap-2 text-xs text-slate-400">
                <span>{i.email}</span>
                <span
                  className={`rounded px-1.5 py-0.5 ${
                    i.accepted
                      ? "bg-emerald-500/20 text-emerald-300"
                      : i.usable
                        ? "bg-amber-500/20 text-amber-300"
                        : "bg-slate-500/20 text-slate-400"
                  }`}
                >
                  {i.accepted ? "accepted" : i.revoked ? "revoked" : i.usable ? "pending" : "expired"}
                </span>
                {i.inviteLink && (
                  <>
                    <button
                      onClick={() => copyLink(i.inviteLink)}
                      className="rounded border border-edge px-1.5 py-0.5 hover:border-aurora/50"
                    >
                      Copy link
                    </button>
                    <button
                      onClick={() => revokeInvite(i.id)}
                      className="rounded border border-rose-500/40 px-1.5 py-0.5 text-rose-300 hover:border-rose-500/70"
                    >
                      Revoke
                    </button>
                  </>
                )}
                <span className="ml-auto text-slate-600">{dateOf(i.createdAt)}</span>
              </div>
            ))}
          </div>
        )}
      </Section>

      {/* danger zone */}
      <div className="rounded-xl border border-rose-500/30 bg-rose-500/5 p-5">
        <div className="text-sm font-semibold text-rose-200">Danger zone</div>
        <p className="mt-1 text-xs text-slate-400 max-w-2xl leading-relaxed">
          Deleting your account permanently removes your keys, credentials, memory, billing and all data (GDPR).
          This cannot be undone. Type <b>DELETE</b> to confirm.
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          <input value={confirmDelete} onChange={(e) => setConfirmDelete(e.target.value)} placeholder="DELETE"
            className="w-40 border-rose-500/30 field" />
          <button onClick={deleteAccount} disabled={confirmDelete !== "DELETE"}
            className="rounded-lg bg-rose-600 px-4 py-2 text-sm font-medium text-white hover:bg-rose-500 disabled:opacity-40">
            Delete my account &amp; data
          </button>
        </div>
      </div>
    </div>
  );
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: import("react").ReactNode }) {
  return (
    <div className="plane p-5">
      <div className="text-sm font-semibold">{title}</div>
      {subtitle && <div className="mt-0.5 text-xs text-slate-400">{subtitle}</div>}
      <div className="mt-3">{children}</div>
    </div>
  );
}
