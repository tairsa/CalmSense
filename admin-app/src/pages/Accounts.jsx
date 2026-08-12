import { useMemo, useState } from "react";
import { api } from "../api.js";
import { useAsync } from "../hooks.js";
import { ErrorBox, PageHeader, Spinner } from "../components/widgets.jsx";

const ROLES = [
  { value: "user", label: "Regular user" },
  { value: "therapist", label: "Therapist" },
  { value: "developer", label: "Developer" },
];

function fmtDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

/**
 * Supabase auth accounts and their role.
 *
 * Separate from the Users page on purpose: that one is built from data rows
 * and still lists pre-auth ids such as "tairsa-dev", which are not accounts
 * and cannot hold a role. Only what appears here can be promoted.
 *
 * The role is written to Supabase user_metadata.role, which the phone app
 * reads at sign-in and the backend re-checks on every therapist request.
 * A user must sign out and back in for a change to take effect on their
 * device, since the role is baked into the session at sign-in.
 */
export default function Accounts() {
  const { data, error, loading, reload } = useAsync(() => api.accounts(), []);
  const [query, setQuery] = useState("");
  // user_id -> "saving" | "saved" | error message
  const [status, setStatus] = useState({});
  // Optimistic role overrides so the select reflects the change immediately.
  const [pending, setPending] = useState({});

  const accounts = data?.accounts || [];
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return accounts;
    return accounts.filter(
      (a) =>
        (a.email || "").toLowerCase().includes(q) ||
        (a.user_id || "").toLowerCase().includes(q),
    );
  }, [accounts, query]);

  async function changeRole(account, role) {
    const id = account.user_id;
    const previous = pending[id] ?? account.role;
    setPending((p) => ({ ...p, [id]: role }));
    setStatus((s) => ({ ...s, [id]: "saving" }));
    try {
      await api.setAccountRole(id, role);
      setStatus((s) => ({ ...s, [id]: "saved" }));
    } catch (e) {
      // Roll the select back so it never shows a role that was not saved.
      setPending((p) => ({ ...p, [id]: previous }));
      setStatus((s) => ({ ...s, [id]: e.detail || e.message }));
    }
  }

  if (loading) return <Spinner />;

  return (
    <div>
      <PageHeader
        title="Accounts"
        subtitle={`${accounts.length} signed up`}
        onRefresh={reload}
      >
        <input
          className="search"
          type="search"
          placeholder="Search email or id…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </PageHeader>

      <ErrorBox error={error} />

      <p className="muted" style={{ marginTop: 0 }}>
        Roles decide what the phone app shows. Therapists and developers get the
        Stats view and can read patient data; regular users only ever see their
        own. A change takes effect the next time that person signs in.
      </p>

      {accounts.length === 0 && !error ? (
        <p className="muted">No Supabase accounts yet.</p>
      ) : filtered.length === 0 ? (
        <p className="muted">No accounts match “{query}”.</p>
      ) : (
        <div className="card no-pad">
          <table className="data-table">
            <thead>
              <tr>
                <th>Email</th>
                <th>User id</th>
                <th>Confirmed</th>
                <th>Last sign-in</th>
                <th>Role</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((a) => {
                const role = pending[a.user_id] ?? a.role;
                const st = status[a.user_id];
                return (
                  <tr key={a.user_id}>
                    <td>{a.email || "—"}</td>
                    <td className="mono">{a.user_id}</td>
                    <td>
                      {a.email_confirmed_at ? (
                        fmtDate(a.email_confirmed_at)
                      ) : (
                        <span className="muted">not confirmed</span>
                      )}
                    </td>
                    <td>{fmtDate(a.last_sign_in_at)}</td>
                    <td>
                      <select
                        value={role}
                        disabled={st === "saving"}
                        onChange={(e) => changeRole(a, e.target.value)}
                      >
                        {ROLES.map((r) => (
                          <option key={r.value} value={r.value}>
                            {r.label}
                          </option>
                        ))}
                      </select>
                      {a.role_unset && !pending[a.user_id] && (
                        <span className="muted"> (default)</span>
                      )}
                      {st === "saving" && <span className="muted"> saving…</span>}
                      {st === "saved" && <span className="muted"> saved</span>}
                      {st && st !== "saving" && st !== "saved" && (
                        <div className="alert alert-error">{st}</div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
