"""Per-user model management: active-weights lookup, retrain, rollback, reset,
and metrics. Backed by the versioned `model_weights` + `user_model_state`
storage (Supabase or JSON fallback).

The weight vector keeps the existing 5-slot contract the phone expects:
    [w_hr, w_hrv, w_motion, w_reserved, bias]
"""

from __future__ import annotations

import json
import os
from datetime import datetime, timezone

import storage

DEFAULT_WEIGHTS = [0.0, 0.0, 0.0, 0.0, 0.0]
FEATURE_NAMES = ["hr", "hrv", "motion", "reserved", "bias"]
BASELINE_FILE = os.path.join(os.path.dirname(__file__), "ml", "model_weights.json")

# Minimum labeled rows (with both classes present) needed to retrain. Kept low
# enough to be usable for a 2-person dev/demo setup; override with the
# CALMSENSE_MIN_TRAIN_SAMPLES env var.
MIN_TRAIN_SAMPLES = int(os.environ.get("CALMSENSE_MIN_TRAIN_SAMPLES", "10"))


# --- Baseline --------------------------------------------------------------

def load_baseline() -> dict:
    """The shipped synthetic-trained model, or zero weights if the file is gone."""
    try:
        with open(BASELINE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        weights = data.get("weights")
        if not isinstance(weights, list) or len(weights) != 5:
            raise ValueError("bad weights")
        return {
            "weights": [float(w) for w in weights],
            "feature_names": FEATURE_NAMES,
            "model_type": data.get("model_type", "logistic_regression"),
            "test_accuracy": data.get("test_accuracy"),
            "training_samples": data.get("training_samples"),
            "note": "Synthetic-trained baseline.",
        }
    except (FileNotFoundError, ValueError, KeyError, json.JSONDecodeError):
        return {
            "weights": list(DEFAULT_WEIGHTS),
            "feature_names": FEATURE_NAMES,
            "model_type": "logistic_regression",
            "test_accuracy": None,
            "training_samples": None,
            "note": "Default zero weights (baseline file missing).",
        }


# --- Active weights for a user --------------------------------------------

def get_active_weights(user_id: str) -> dict:
    """Return the weights served to a user: their active snapshot, else baseline."""
    state = storage.get_user_model_state(user_id)
    if state and state.get("active_weights_id") is not None:
        snap = storage.get_model_snapshot(state["active_weights_id"])
        if snap:
            return {
                "weights": _coerce_weights(snap.get("weights")),
                "source": snap.get("source", "trained"),
                "model_meta": {
                    "snapshot_id": snap.get("id"),
                    "model_type": snap.get("model_type"),
                    "trained_at": snap.get("created_at"),
                    "test_accuracy": snap.get("test_accuracy"),
                    "training_samples": snap.get("training_samples"),
                    "trained_through": snap.get("trained_through"),
                },
            }
    base = load_baseline()
    return {
        "weights": base["weights"],
        "source": "baseline",
        "model_meta": {
            "model_type": base["model_type"],
            "test_accuracy": base["test_accuracy"],
            "training_samples": base["training_samples"],
        },
    }


def _coerce_weights(weights) -> list:
    if isinstance(weights, str):
        weights = json.loads(weights)
    if not isinstance(weights, list) or len(weights) != 5:
        return list(DEFAULT_WEIGHTS)
    return [float(w) for w in weights]


# --- Reset to baseline -----------------------------------------------------

def reset_user_model(user_id: str) -> dict:
    """Write a fresh baseline snapshot for the user, make it active, clear cutoff."""
    base = load_baseline()
    snap = storage.insert_model_snapshot({
        "user_id": user_id,
        "weights": base["weights"],
        "feature_names": FEATURE_NAMES,
        "model_type": base["model_type"],
        "source": "baseline",
        "test_accuracy": base["test_accuracy"],
        "training_samples": base["training_samples"],
        "trained_through": None,
        "note": "Reset to synthetic baseline.",
    })
    storage.upsert_user_model_state(user_id, snap["id"], None)
    return snap


# --- Retrain from feedback -------------------------------------------------

def _event_time(row: dict) -> str:
    return row.get("timestamp") or row.get("created_at") or ""


def _parse_iso(value: str):
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def usable_samples(user_id: str, cutoff: str | None = None) -> list[tuple]:
    """The user's feedback rows that can train a model, as
    (hr, hrv, motion, label, event_time) tuples. Rows missing vitals or a
    `was_panic` label are dropped; rows after `cutoff` (if set) are ignored.
    """
    cutoff_dt = _parse_iso(cutoff) if cutoff else None

    rows = [r for r in storage.read_all_feedback() if r.get("user_id") == user_id]
    samples = []
    for r in rows:
        hr = r.get("current_hr")
        hrv = r.get("current_hrv")
        motion = r.get("current_motion_intensity")
        label = r.get("was_panic")
        if hr is None or motion is None or label is None:
            continue
        if cutoff_dt is not None:
            t = _parse_iso(_event_time(r))
            if t is not None and t > cutoff_dt:
                continue
        samples.append((float(hr), float(hrv or 0.0), float(motion), int(bool(label)), _event_time(r)))
    return samples


def _synthetic_anchor(label: int, n: int, seed: int = 42) -> list[tuple]:
    """`n` synthetic samples of one class, drawn from the same physiological
    priors the shipped baseline was trained on (ml/generate_data.py). Used to
    stand in for a class the user's real feedback doesn't have yet.
    """
    import random

    from ml.generate_data import PROFILES, sample as synth_sample

    rng = random.Random(seed)
    profiles = [p for p in PROFILES if p.label == label]
    out = []
    i = 0
    while len(out) < n:
        hr, hrv, motion, lab = synth_sample(profiles[i % len(profiles)], rng)
        out.append((hr, hrv, motion, lab, ""))  # synthetic rows carry no event time
        i += 1
    return out


def retrain_user_model(user_id: str, cutoff: str | None = None) -> dict:
    """Retrain logistic regression from the user's labeled feedback.

    Uses feedback rows that have vitals and a `was_panic` label. If `cutoff` is
    set, rows after it are ignored. Raises ValueError when there isn't enough
    usable data to fit a model.

    When the feedback is single-class (the common early case: only false
    positives), the missing class is anchored with synthetic samples from the
    baseline's priors at half weight — the user's real rows reshape the
    boundary on their side while the synthetic side keeps the model knowing
    what a canonical panic (or non-panic) looks like.
    """
    samples = usable_samples(user_id, cutoff)

    if len(samples) < MIN_TRAIN_SAMPLES:
        raise ValueError(
            f"Not enough labeled feedback to retrain: have {len(samples)}, "
            f"need at least {MIN_TRAIN_SAMPLES}."
        )
    labels = {s[3] for s in samples}
    synthetic: list[tuple] = []
    if len(labels) < 2:
        missing_label = 1 if 0 in labels else 0
        synthetic = _synthetic_anchor(missing_label, max(len(samples), 20))

    # Lazy import so the base API runs without sklearn installed.
    import numpy as np
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import accuracy_score

    rows = samples + synthetic
    X = np.array([[s[0], s[1], s[2]] for s in rows], dtype=float)
    y = np.array([s[3] for s in rows], dtype=int)
    sample_weight = np.array([1.0] * len(samples) + [0.5] * len(synthetic))
    model = LogisticRegression(solver="liblinear", C=1.0, max_iter=1000)
    model.fit(X, y, sample_weight=sample_weight)
    acc = float(accuracy_score(y, model.predict(X)))  # in-sample; data is tiny

    coefs = model.coef_[0]
    weights = [float(coefs[0]), float(coefs[1]), float(coefs[2]), 0.0, float(model.intercept_[0])]
    trained_through = max((s[4] for s in samples), default=None)

    note = f"Retrained from {len(samples)} labeled feedback rows."
    if synthetic:
        cls = "panic" if synthetic[0][3] == 1 else "non-panic"
        note += f" Single-class feedback: anchored with {len(synthetic)} synthetic {cls} samples (half weight)."

    snap = storage.insert_model_snapshot({
        "user_id": user_id,
        "weights": weights,
        "feature_names": FEATURE_NAMES,
        "model_type": "logistic_regression",
        "source": "trained",
        "test_accuracy": acc,
        "training_samples": len(samples),
        "trained_through": trained_through,
        "note": note,
    })
    # Retraining keeps any existing cutoff so it stays consistent with a prior rollback.
    state = storage.get_user_model_state(user_id)
    existing_cutoff = state.get("training_cutoff") if state else None
    storage.upsert_user_model_state(user_id, snap["id"], cutoff or existing_cutoff)
    return snap


# --- Rollback to an earlier snapshot ---------------------------------------

def rollback_user_model(user_id: str, cutoff: str) -> dict:
    """Re-activate the most recent snapshot at or before `cutoff` (no refit).

    Stores `cutoff` as the training cutoff so future retrains ignore later data.
    """
    cutoff_dt = _parse_iso(cutoff)
    if cutoff_dt is None:
        raise ValueError(f"Invalid cutoff date: {cutoff!r}")

    snapshots = storage.list_model_snapshots(user_id)  # newest first
    target = None
    for snap in snapshots:
        created = _parse_iso(snap.get("created_at", ""))
        if created is not None and created <= cutoff_dt:
            target = snap
            break

    if target is None:
        raise ValueError(
            f"No model snapshot exists at or before {cutoff}. "
            f"Reset to baseline or retrain first."
        )

    storage.upsert_user_model_state(user_id, target["id"], cutoff)
    return target


# --- Metrics ---------------------------------------------------------------

def compute_user_metrics(user_id: str) -> dict:
    """Confusion-style counts and rates from a user's feedback labels.

    True Negatives aren't stored (a non-detection is never logged), so accuracy
    over all reality isn't computable; we report the cells we do have plus
    precision/recall.
    """
    feedback = [r for r in storage.read_all_feedback() if r.get("user_id") == user_id]
    tp = fp = fn = 0
    for r in feedback:
        detected = bool(r.get("detected_by_model"))
        was_panic = bool(r.get("was_panic"))
        if detected and was_panic:
            tp += 1
        elif detected and not was_panic:
            fp += 1
        elif not detected and was_panic:
            fn += 1

    precision = tp / (tp + fp) if (tp + fp) else None
    recall = tp / (tp + fn) if (tp + fn) else None

    return {
        "user_id": user_id,
        "feedback_count": len(feedback),
        "confusion": {
            "true_positive": tp,
            "false_positive": fp,
            "false_negative": fn,
            "true_negative": None,  # not recorded
        },
        "precision": precision,
        "recall": recall,
        "detections_by_day": _counts_by_day(feedback),
    }


def _counts_by_day(rows: list) -> list:
    buckets: dict[str, int] = {}
    for r in rows:
        t = _parse_iso(_event_time(r))
        if t is None:
            continue
        day = t.astimezone(timezone.utc).date().isoformat()
        buckets[day] = buckets.get(day, 0) + 1
    return [{"date": d, "count": c} for d, c in sorted(buckets.items())]


def get_model_state_view(user_id: str) -> dict:
    """A UI-friendly view of the user's current model state + history length."""
    state = storage.get_user_model_state(user_id)
    snapshots = storage.list_model_snapshots(user_id)
    active = get_active_weights(user_id)
    return {
        "user_id": user_id,
        "active": active,
        "training_cutoff": state.get("training_cutoff") if state else None,
        "snapshot_count": len(snapshots),
    }
