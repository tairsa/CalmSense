import { useState } from "react";
import { api } from "../api.js";
import { useAuth } from "../auth.jsx";
import { useAsync } from "../hooks.js";
import { ErrorBox, PageHeader, Spinner } from "../components/widgets.jsx";

function fmtDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString();
}

export default function Admins() {
  const { admin: current } = useAuth();
  const { data, error, loading, reload } = useAsync(() => api.admins(), []);
  const [form, setForm] = useState({ email: "", name: "", password: "" });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState(null);
  const [formError, setFormError] = useState(null);

  function set(field) {
    return (e) => setForm((f) => ({ ...f, [field]: e.target.value }));
  }

  async function onSubmit(e) {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    setFormError(null);
    try {
      await api.register({
        email: form.email.trim(),
        name: form.name.trim() || null,
        password: form.password,
      });
      setMsg(`Added ${form.email.trim()}.`);
      setForm({ email: "", name: "", password: "" });
      await reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <Spinner />;
  const admins = data?.admins || [];

  return (
    <div>
      <PageHeader title="Admins" subtitle="Accounts that can sign in to this dashboard" onRefresh={reload} />
      <ErrorBox error={error} />

      <div className="grid-2">
        <div className="card no-pad">
          <div className="card-head"><h3>Existing admins</h3></div>
          <table className="data-table">
            <thead>
              <tr>
                <th>Email</th>
                <th>Name</th>
                <th>Added</th>
              </tr>
            </thead>
            <tbody>
              {admins.map((a) => (
                <tr key={a.id}>
                  <td>
                    {a.email}
                    {current?.email === a.email && <span className="pill pill-neutral you-tag">you</span>}
                  </td>
                  <td>{a.name || <span className="muted">—</span>}</td>
                  <td>{fmtDate(a.created_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {admins.length === 0 && <p className="muted pad">No admins found.</p>}
        </div>

        <form className="card" onSubmit={onSubmit}>
          <h3>Add an admin</h3>
          <p className="muted small">
            Create a login for another person (e.g. Tair). They can sign in immediately.
          </p>
          {msg && <div className="alert alert-ok">{msg}</div>}
          <ErrorBox error={formError} />
          <label>
            Email
            <input type="email" value={form.email} onChange={set("email")} required />
          </label>
          <label>
            Name (optional)
            <input type="text" value={form.name} onChange={set("name")} />
          </label>
          <label>
            Password
            <input
              type="password"
              value={form.password}
              onChange={set("password")}
              minLength={8}
              required
            />
          </label>
          <p className="muted small">Minimum 8 characters.</p>
          <button className="btn btn-primary" disabled={busy} type="submit">
            {busy ? "Adding…" : "Add admin"}
          </button>
        </form>
      </div>
    </div>
  );
}
