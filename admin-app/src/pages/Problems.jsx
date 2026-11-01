import { Fragment, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api.js";
import { useAsync } from "../hooks.js";
import { ErrorBox, PageHeader, Spinner } from "../components/widgets.jsx";

function fmtDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

// Same label the phone shows: "E-2 | 503", or "E-4" when no server answer was involved.
function codeLabel(r) {
  return r.http_status ? `E-${r.code} | ${r.http_status}` : `E-${r.code}`;
}

export default function Problems() {
  const { data, error, loading, reload } = useAsync(() => api.problemReports(), []);
  const [open, setOpen] = useState(null);

  if (loading) return <Spinner />;
  const reports = data?.reports || [];

  return (
    <div>
      <PageHeader title="Problems" subtitle="Reports sent from the phone's error screen" onRefresh={reload} />
      <ErrorBox error={error} />
      {reports.length === 0 && !error ? (
        <p className="muted">No problem reports yet.</p>
      ) : (
        <div className="card no-pad">
          <table className="data-table">
            <thead>
              <tr>
                <th>When</th>
                <th>Code</th>
                <th>User</th>
                <th>App</th>
                <th>Phone</th>
                <th>Note</th>
              </tr>
            </thead>
            <tbody>
              {reports.map((r) => (
                <Fragment key={r.id ?? `${r.user_id}-${r.timestamp}`}>
                  <tr className="clickable" onClick={() => setOpen(open === r ? null : r)}>
                    <td>{fmtDate(r.timestamp)}</td>
                    <td className="mono">{codeLabel(r)}</td>
                    <td>
                      <Link to={`/users/${encodeURIComponent(r.user_id)}`} onClick={(e) => e.stopPropagation()}>
                        {r.name || r.email || <span className="mono">{r.user_id}</span>}
                      </Link>
                    </td>
                    <td>{r.app_version || "—"}</td>
                    <td>{[r.device, r.os_version && `Android ${r.os_version}`].filter(Boolean).join(", ") || "—"}</td>
                    <td>{r.note || <span className="muted">—</span>}</td>
                  </tr>
                  {open === r && (
                    <tr>
                      <td colSpan={6}>
                        <pre className="mono small" style={{ whiteSpace: "pre-wrap", maxHeight: 480, overflow: "auto" }}>
                          {r.logs || "No logs were attached."}
                        </pre>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
