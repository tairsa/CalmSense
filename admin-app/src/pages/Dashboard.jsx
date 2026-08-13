import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useAsync } from "../hooks.js";
import {
  ConfusionMatrix,
  ErrorBox,
  MiniBars,
  ModelBadge,
  PageHeader,
  Spinner,
  StatCard,
} from "../components/widgets.jsx";

export default function Dashboard() {
  const { data, error, loading, reload } = useAsync(() => api.globalMetrics(), []);
  const usersQ = useAsync(() => api.users(), []);

  if (loading) return <Spinner />;
  const users = usersQ.data?.users || [];

  return (
    <div>
      <PageHeader
        title="Dashboard"
        subtitle="System-wide activity and detection quality"
        onRefresh={() => {
          reload();
          usersQ.reload();
        }}
      />
      <ErrorBox error={error} />
      {data && (
        <>
          <div className="stat-grid">
            <StatCard label="Users" value={data.user_count} to="/users" />
            <StatCard label="Sensor readings" value={data.sensor_count} />
            <StatCard label="Feedback labels" value={data.feedback_count} />
            <StatCard label="Panic reports" value={data.report_count} />
          </div>
          <div className="grid-2">
            <ConfusionMatrix
              confusion={{ ...data.confusion, true_negative: null }}
              precision={data.precision}
              recall={data.recall}
            />
            <div className="card">
              <h3>Feedback by day</h3>
              <MiniBars data={data.feedback_by_day} label="feedback" />
            </div>
          </div>

          <div className="card no-pad">
            <div className="card-head row between">
              <h3>Users</h3>
              <Link className="btn btn-ghost btn-sm" to="/users">View all</Link>
            </div>
            {usersQ.loading ? (
              <p className="muted pad">Loading users…</p>
            ) : users.length === 0 ? (
              <p className="muted pad">No users have sent data yet.</p>
            ) : (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>User</th>
                    <th className="num">Feedback</th>
                    <th className="num">Reports</th>
                    <th>Model</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((u) => (
                    <tr key={u.user_id}>
                      <td className="mono">{u.user_id}</td>
                      <td className="num">{u.feedback_count}</td>
                      <td className="num">{u.report_count}</td>
                      <td><ModelBadge source={u.model_source} /></td>
                      <td>
                        <Link className="btn btn-ghost btn-sm" to={`/users/${encodeURIComponent(u.user_id)}`}>
                          Open
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}
    </div>
  );
}
