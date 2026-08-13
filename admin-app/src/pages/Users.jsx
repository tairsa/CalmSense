import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api.js";
import { useAsync } from "../hooks.js";
import { ErrorBox, ModelBadge, PageHeader, Spinner } from "../components/widgets.jsx";

function fmtDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

export default function Users() {
  const { data, error, loading, reload } = useAsync(() => api.users(), []);
  const [query, setQuery] = useState("");
  const navigate = useNavigate();

  const users = data?.users || [];
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return users;
    return users.filter((u) => u.user_id.toLowerCase().includes(q));
  }, [users, query]);

  if (loading) return <Spinner />;

  return (
    <div>
      <PageHeader title="Users" subtitle={`${users.length} total`} onRefresh={reload}>
        <input
          className="search"
          type="search"
          placeholder="Search user id…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </PageHeader>
      <ErrorBox error={error} />
      {users.length === 0 && !error ? (
        <p className="muted">No users have sent data yet.</p>
      ) : filtered.length === 0 ? (
        <p className="muted">No users match “{query}”.</p>
      ) : (
        <div className="card no-pad">
          <table className="data-table">
            <thead>
              <tr>
                <th>User</th>
                <th className="num">Sensor</th>
                <th className="num">Feedback</th>
                <th className="num">Reports</th>
                <th>Model</th>
                <th>Last seen</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((u) => (
                <tr
                  key={u.user_id}
                  className="clickable"
                  onClick={() => navigate(`/users/${encodeURIComponent(u.user_id)}`)}
                >
                  <td className="mono">{u.user_id}</td>
                  <td className="num">{u.sensor_count}</td>
                  <td className="num">{u.feedback_count}</td>
                  <td className="num">{u.report_count}</td>
                  <td><ModelBadge source={u.model_source} /></td>
                  <td>{fmtDate(u.last_seen)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
