import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api.js";
import { useAsync } from "../hooks.js";
import {
  ConfusionMatrix,
  ErrorBox,
  MiniBars,
  ModelBadge,
  Pct,
  SeverityBadge,
  Spinner,
  StatCard,
} from "../components/widgets.jsx";

function fmt(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

const TABS = ["Overview", "Model", "Reports", "Feedback", "Sensor"];

export default function UserDetail() {
  const { userId } = useParams();
  const [tab, setTab] = useState("Overview");

  return (
    <div>
      <div className="breadcrumb muted">
        <Link to="/users">Users</Link> / <span className="mono">{userId}</span>
      </div>
      <h1 className="mono">{userId}</h1>

      <div className="tabs">
        {TABS.map((t) => (
          <button key={t} className={`tab ${tab === t ? "active" : ""}`} onClick={() => setTab(t)}>
            {t}
          </button>
        ))}
      </div>

      {tab === "Overview" && <Overview userId={userId} />}
      {tab === "Model" && <ModelPanel userId={userId} />}
      {tab === "Reports" && <Reports userId={userId} />}
      {tab === "Feedback" && <Feedback userId={userId} />}
      {tab === "Sensor" && <Sensor userId={userId} />}
    </div>
  );
}

function Overview({ userId }) {
  const { data, error, loading } = useAsync(() => api.userDetail(userId), [userId]);
  if (loading) return <Spinner />;
  const m = data?.metrics;
  const model = data?.model;

  return (
    <div>
      <ErrorBox error={error} />
      {m && (
        <>
          <div className="stat-grid">
            <StatCard label="Feedback labels" value={m.feedback_count} />
            <StatCard label="Precision" value={Pct(m.precision)} />
            <StatCard label="Recall" value={Pct(m.recall)} />
            <StatCard
              label="Active model"
              value={<ModelBadge source={model?.active?.source} />}
              hint={model?.training_cutoff ? `cutoff ${fmt(model.training_cutoff)}` : "no cutoff"}
            />
          </div>
          <div className="grid-2">
            <ConfusionMatrix confusion={m.confusion} precision={m.precision} recall={m.recall} />
            <div className="card">
              <h3>Detections by day</h3>
              <MiniBars data={m.detections_by_day} label="detections" />
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function ModelPanel({ userId }) {
  const { data, error, loading, reload } = useAsync(() => api.modelHistory(userId), [userId]);
  const [cutoff, setCutoff] = useState("");
  const [busy, setBusy] = useState(null);
  const [msg, setMsg] = useState(null);
  const [actionError, setActionError] = useState(null);

  async function act(name, fn) {
    setBusy(name);
    setMsg(null);
    setActionError(null);
    try {
      const res = await fn();
      setMsg(res?.snapshot ? `${name} complete (snapshot #${res.snapshot.id}).` : `${name} complete.`);
      await reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(null);
    }
  }

  function toIso(localValue) {
    // datetime-local has no timezone; treat as local time and convert to ISO.
    return localValue ? new Date(localValue).toISOString() : null;
  }

  if (loading) return <Spinner />;
  const snapshots = data?.snapshots || [];

  return (
    <div>
      <ErrorBox error={error} />
      {msg && <div className="alert alert-ok">{msg}</div>}
      <ErrorBox error={actionError} />

      <div className="grid-2">
        <div className="card">
          <h3>Retrain</h3>
          <p className="muted small">
            Fit a fresh logistic-regression model from this user’s labeled feedback.
            Any active training cutoff is respected.
          </p>
          <button className="btn btn-primary" disabled={busy} onClick={() => act("Retrain", () => api.retrain(userId, {}))}>
            {busy === "Retrain" ? "Retraining…" : "Retrain from feedback"}
          </button>
        </div>

        <div className="card">
          <h3>Reset to baseline</h3>
          <p className="muted small">
            Discard the trained model and serve the shipped synthetic baseline.
            Clears the training cutoff. History is preserved.
          </p>
          <button
            className="btn btn-warn"
            disabled={busy}
            onClick={() => {
              if (confirm("Reset this user's model to the synthetic baseline?")) {
                act("Reset", () => api.reset(userId));
              }
            }}
          >
            {busy === "Reset" ? "Resetting…" : "Reset to baseline"}
          </button>
        </div>
      </div>

      <div className="card">
        <h3>Roll back to a date</h3>
        <p className="muted small">
          Re-activate the most recent snapshot at or before the chosen moment —
          no refitting. The date is stored as the training cutoff, so later
          retrains ignore data after it until you reset.
        </p>
        <div className="row gap">
          <input type="datetime-local" value={cutoff} onChange={(e) => setCutoff(e.target.value)} />
          <button
            className="btn btn-primary"
            disabled={busy || !cutoff}
            onClick={() => act("Rollback", () => api.rollback(userId, toIso(cutoff)))}
          >
            {busy === "Rollback" ? "Rolling back…" : "Roll back"}
          </button>
        </div>
        {data?.training_cutoff && (
          <p className="muted small">Current training cutoff: <strong>{fmt(data.training_cutoff)}</strong></p>
        )}
      </div>

      <div className="card no-pad">
        <div className="card-head"><h3>Snapshot history</h3></div>
        {snapshots.length === 0 ? (
          <p className="muted pad">No snapshots yet — retrain or reset to create the first one.</p>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Source</th>
                <th className="num">Accuracy</th>
                <th className="num">Samples</th>
                <th>Trained through</th>
                <th>Created</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {snapshots.map((s) => (
                <tr key={s.id} className={s.is_active ? "row-active" : ""}>
                  <td>{s.id}</td>
                  <td>{s.source}</td>
                  <td className="num">{s.test_accuracy != null ? Pct(s.test_accuracy) : "—"}</td>
                  <td className="num">{s.training_samples ?? "—"}</td>
                  <td>{fmt(s.trained_through)}</td>
                  <td>{fmt(s.created_at)}</td>
                  <td>{s.is_active && <span className="pill pill-ok">active</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function Reports({ userId }) {
  const { data, error, loading } = useAsync(() => api.userReports(userId), [userId]);
  if (loading) return <Spinner />;
  const reports = data?.reports || [];
  return (
    <div>
      <ErrorBox error={error} />
      {reports.length === 0 && !error ? (
        <p className="muted">No reports.</p>
      ) : (
        reports.map((r, i) => (
          <div className="card" key={r.id ?? i}>
            <div className="row between">
              <SeverityBadge value={r.severity} />
              <span className="muted small">{fmt(r.timestamp)}</span>
            </div>
            <div className="report-grid">
              <Field label="Feeling" value={r.feeling} />
              <Field label="Before" value={r.activity_before} />
              <Field label="What helped" value={r.what_helped} />
              <Field label="Duration" value={r.duration_minutes != null ? `${r.duration_minutes} min` : null} />
              <Field label="Symptoms" value={Array.isArray(r.symptoms) ? r.symptoms.join(", ") : r.symptoms} />
              <Field
                label="Location"
                value={
                  r.latitude != null && r.longitude != null ? (
                    <a href={`https://maps.google.com/?q=${r.latitude},${r.longitude}`} target="_blank" rel="noreferrer">
                      Open in Google Maps
                    </a>
                  ) : null
                }
              />
            </div>
            <div className="muted small">
              HR {r.current_hr ?? "—"} · HRV {r.current_hrv ?? "—"} · motion {r.current_motion_intensity ?? "—"} ·{" "}
              {r.detected_by_model ? "auto-detected" : "manual"}
            </div>
          </div>
        ))
      )}
    </div>
  );
}

function Field({ label, value }) {
  return (
    <div>
      <div className="muted small">{label}</div>
      <div>{value || <span className="muted">—</span>}</div>
    </div>
  );
}

function Feedback({ userId }) {
  const { data, error, loading } = useAsync(() => api.userFeedback(userId), [userId]);
  if (loading) return <Spinner />;
  const rows = data?.feedback || [];
  return (
    <div>
      <ErrorBox error={error} />
      {rows.length > 0 && (
        <p className="muted small legend">
          <span className="legend-swatch swatch-untrained" /> not yet used for training
          <span className="legend-swatch swatch-trained" /> used in at least one retrain
        </p>
      )}
      <div className="card no-pad">
        <table className="data-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Was panic</th>
              <th>Detected by model</th>
              <th className="num">Severity</th>
              <th className="num">Model p</th>
              <th className="num">HR</th>
              <th className="num">Motion</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.id ?? i} className={r.used_in_training ? "" : "row-untrained"}>
                <td>{fmt(r.timestamp)}</td>
                <td>{r.was_panic ? "yes" : "no"}</td>
                <td>{r.detected_by_model ? "yes" : "manual"}</td>
                <td className="num"><SeverityBadge value={r.severity} /></td>
                <td className="num">{r.model_probability != null ? r.model_probability.toFixed(2) : "—"}</td>
                <td className="num">{r.current_hr ?? "—"}</td>
                <td className="num">{r.current_motion_intensity ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p className="muted pad">No feedback.</p>}
      </div>
    </div>
  );
}

function Sensor({ userId }) {
  const { data, error, loading } = useAsync(() => api.userSensor(userId), [userId]);
  if (loading) return <Spinner />;
  const rows = data?.sensor_data || [];
  return (
    <div>
      <ErrorBox error={error} />
      <p className="muted small">Showing {rows.length} most recent of {data?.total ?? rows.length}.</p>
      <div className="card no-pad">
        <table className="data-table">
          <thead>
            <tr>
              <th>Time</th>
              <th>Panic flag</th>
              <th className="num">HR</th>
              <th className="num">HRV</th>
              <th className="num">Motion</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.id ?? i}>
                <td>{fmt(r.timestamp)}</td>
                <td>{r.panic_attack_detection ? "panic" : "—"}</td>
                <td className="num">{r.current_hr ?? "—"}</td>
                <td className="num">{r.current_hrv ?? "—"}</td>
                <td className="num">{r.current_motion_intensity ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length === 0 && <p className="muted pad">No sensor data.</p>}
      </div>
    </div>
  );
}
