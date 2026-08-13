// Small presentational helpers shared across pages.
import { Link } from "react-router-dom";

export function PageHeader({ title, subtitle, onRefresh, children }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted small page-sub">{subtitle}</p>}
      </div>
      <div className="row gap">
        {children}
        {onRefresh && (
          <button className="btn btn-ghost btn-sm" onClick={onRefresh} title="Refresh">
            ↻ Refresh
          </button>
        )}
      </div>
    </div>
  );
}

// Colored badge for a model source (baseline / trained / rollback).
export function ModelBadge({ source }) {
  if (!source) return <span className="muted">—</span>;
  const cls =
    source === "trained" ? "pill-ok" : source === "rollback" ? "pill-warn" : "pill-neutral";
  return <span className={`pill ${cls}`}>{source}</span>;
}

// Severity 1-10 colored chip.
export function SeverityBadge({ value }) {
  if (value == null) return <span className="muted">—</span>;
  const tier = value >= 8 ? "high" : value >= 5 ? "med" : "low";
  return <span className={`sev sev-${tier}`}>{value}/10</span>;
}

export function StatCard({ label, value, hint, to }) {
  const inner = (
    <>
      <div className="stat-value">{value ?? "—"}</div>
      <div className="stat-label">{label}</div>
      {hint && <div className="stat-hint muted">{hint}</div>}
    </>
  );
  if (to) return <Link className="card stat stat-link" to={to}>{inner}</Link>;
  return <div className="card stat">{inner}</div>;
}

export function ErrorBox({ error }) {
  if (!error) return null;
  return <div className="alert alert-error">{error.detail || error.message || String(error)}</div>;
}

export function Spinner({ label = "Loading…" }) {
  return <div className="centered muted">{label}</div>;
}

export function Pct(value) {
  if (value === null || value === undefined) return "—";
  return `${(value * 100).toFixed(1)}%`;
}

// Confusion matrix from {true_positive, false_positive, false_negative, true_negative}
export function ConfusionMatrix({ confusion, precision, recall }) {
  const c = confusion || {};
  const cell = (v) => (v === null || v === undefined ? "n/a" : v);
  return (
    <div className="card">
      <h3>Detection quality</h3>
      <table className="confusion">
        <thead>
          <tr>
            <th></th>
            <th>Actually panic</th>
            <th>Actually not</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th>Model fired</th>
            <td className="cm-tp">{cell(c.true_positive)} <span className="cm-tag">TP</span></td>
            <td className="cm-fp">{cell(c.false_positive)} <span className="cm-tag">FP</span></td>
          </tr>
          <tr>
            <th>No detection</th>
            <td className="cm-fn">{cell(c.false_negative)} <span className="cm-tag">FN</span></td>
            <td className="cm-tn">{cell(c.true_negative)} <span className="cm-tag">TN</span></td>
          </tr>
        </tbody>
      </table>
      <div className="row gap muted small">
        <span>Precision: <strong>{Pct(precision)}</strong></span>
        <span>Recall: <strong>{Pct(recall)}</strong></span>
      </div>
      <p className="muted small">
        True negatives aren’t logged (a non-detection is never recorded), so
        overall accuracy isn’t computable from feedback alone.
      </p>
    </div>
  );
}

// Minimal bar chart from [{date, count}] — no chart library.
export function MiniBars({ data, label }) {
  if (!data || data.length === 0) return <p className="muted small">No {label} yet.</p>;
  const max = Math.max(...data.map((d) => d.count), 1);
  return (
    <div className="bars">
      {data.map((d) => (
        <div className="bar-col" key={d.date} title={`${d.date}: ${d.count}`}>
          <div className="bar" style={{ height: `${(d.count / max) * 100}%` }} />
          <div className="bar-x">{d.date.slice(5)}</div>
        </div>
      ))}
    </div>
  );
}
