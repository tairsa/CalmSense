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

// E-15 retrain failed, E-16 auto-retrain pass missed. Written by the server
// (incidents.py), one row per code + user; fixed after 72 h without a repeat.
function ServerIncidents({ q }) {
  const [open, setOpen] = useState(null);
  const rows = q.data?.incidents || [];
  return (
    <>
      <h3>Server incidents</h3>
      <ErrorBox error={q.error} />
      {rows.length === 0 && !q.error ? (
        <p className="muted">No server incidents.</p>
      ) : (
        <div className="card no-pad">
          <table className="data-table">
            <thead>
              <tr>
                <th>Status</th>
                <th>Code</th>
                <th>Summary</th>
                <th>User</th>
                <th>Seen</th>
                <th>First</th>
                <th>Last</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <Fragment key={r.key}>
                  <tr className="clickable" onClick={() => setOpen(open === r ? null : r)}>
                    <td>{r.resolved_at ? <span className="muted">Fixed {fmtDate(r.resolved_at)}</span> : <strong>Open</strong>}</td>
                    <td className="mono">E-{r.code}</td>
                    <td>{r.summary}</td>
                    <td>
                      {r.user_id ? (
                        <Link to={`/users/${encodeURIComponent(r.user_id)}`} onClick={(e) => e.stopPropagation()}>
                          <span className="mono">{r.user_id}</span>
                        </Link>
                      ) : (
                        <span className="muted">server</span>
                      )}
                    </td>
                    <td>{r.count}×</td>
                    <td>{fmtDate(r.first_seen)}</td>
                    <td>{fmtDate(r.last_seen)}</td>
                  </tr>
                  {open === r && (
                    <tr>
                      <td colSpan={7}>
                        <pre className="mono small" style={{ whiteSpace: "pre-wrap", maxHeight: 480, overflow: "auto" }}>
                          {r.logs || "No logs."}
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
      <h3 style={{ marginTop: 24 }}>Phone reports</h3>
    </>
  );
}

export default function Problems() {
  const { data, error, loading, reload } = useAsync(() => api.problemReports(), []);
  const incidentsQ = useAsync(() => api.incidents(), []);
  const [open, setOpen] = useState(null);

  if (loading) return <Spinner />;
  const reports = data?.reports || [];

  return (
    <div>
      <PageHeader
        title="Problems"
        subtitle="Server incidents and reports sent from the phone's error screen"
        onRefresh={() => {
          reload();
          incidentsQ.reload();
        }}
      />
      <ServerIncidents q={incidentsQ} />
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
