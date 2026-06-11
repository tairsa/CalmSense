"""
Offline test for the auto-retraining loop (auto_retrain.py).

Runs against a temporary data directory so your real data/ files are never
touched. No server needed:

    python test_auto_retrain.py

Exits 0 on success, non-zero on any failure.
"""

from __future__ import annotations

import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone

# Keep the threshold predictable regardless of local .env overrides.
os.environ["CALMSENSE_MIN_TRAIN_SAMPLES"] = "10"

import storage  # noqa: E402

# Redirect every JSON store into a throwaway directory before anything reads it.
_tmp = tempfile.mkdtemp(prefix="calmsense-test-")
storage._supabase = None  # force JSON mode even if .env has Supabase creds
storage.DATA_DIR = _tmp
storage.DATA_FILE = os.path.join(_tmp, "sensor_data.json")
storage.FEEDBACK_FILE = os.path.join(_tmp, "panic_feedback.json")
storage.REPORTS_FILE = os.path.join(_tmp, "panic_reports.json")
storage.ADMIN_USERS_FILE = os.path.join(_tmp, "admin_users.json")
storage.MODEL_WEIGHTS_FILE = os.path.join(_tmp, "model_weights.json")
storage.USER_MODEL_STATE_FILE = os.path.join(_tmp, "user_model_state.json")

import auto_retrain  # noqa: E402
import model_service  # noqa: E402

USER = "loop-test-user"
FP_USER = "fp-only-user"


def check(label, condition, detail=""):
    mark = "PASS" if condition else "FAIL"
    print(f"  [{mark}] {label}{(' - ' + detail) if detail else ''}")
    if not condition:
        check.failed = True


check.failed = False


def add_feedback(was_panic: bool, hr: float, ts: datetime, user: str = USER) -> None:
    storage.append_feedback({
        "user_id": user,
        "was_panic": was_panic,
        "severity": 7 if was_panic else None,
        "detected_by_model": True,
        "current_hr": hr,
        "current_hrv": 30.0 if was_panic else 55.0,
        "current_motion_intensity": 0.05,
        "model_probability": 0.9 if was_panic else 0.6,
        "timestamp": ts.isoformat(),
    })


def result_for(summary: dict, user_id: str) -> dict:
    return next(r for r in summary["results"] if r["user_id"] == user_id)


def main():
    print(f"Testing auto_retrain against temp dir {_tmp} ...")
    base = datetime.now(timezone.utc) - timedelta(days=1)

    # --- 1. too little data -> skip ---
    print("\n[1] Pass with 4 labeled rows (below minimum)")
    for i in range(2):
        add_feedback(True, 130 + i, base + timedelta(minutes=i))
        add_feedback(False, 75 + i, base + timedelta(minutes=10 + i))
    summary = auto_retrain.run_pass()
    r = result_for(summary, USER)
    check("user skipped", r["action"] == "skipped", r.get("reason", ""))
    check("nothing retrained", summary["retrained"] == 0)

    # --- 2. enough two-class data -> retrain ---
    print("\n[2] Pass with 12 labeled rows (both classes)")
    for i in range(4):
        add_feedback(True, 125 + i, base + timedelta(minutes=20 + i))
        add_feedback(False, 70 + i, base + timedelta(minutes=30 + i))
    summary = auto_retrain.run_pass()
    r = result_for(summary, USER)
    check("user retrained", r["action"] == "retrained", str(r))
    check("12 training samples", r.get("training_samples") == 12, str(r.get("training_samples")))
    active = model_service.get_active_weights(USER)
    check("served model is trained", active["source"] == "trained", active["source"])
    first_snapshot = r.get("snapshot_id")

    # --- 3. immediate second pass -> up to date, no churn ---
    print("\n[3] Second pass with no new feedback")
    summary = auto_retrain.run_pass()
    r = result_for(summary, USER)
    check("user skipped as up-to-date", r["action"] == "skipped", r.get("reason", ""))
    active = model_service.get_active_weights(USER)
    check("active snapshot unchanged",
          active["model_meta"].get("snapshot_id") == first_snapshot)

    # --- 4. new feedback arrives -> retrains again ---
    print("\n[4] Pass after one new labeled row")
    add_feedback(True, 140, base + timedelta(hours=2))
    summary = auto_retrain.run_pass()
    r = result_for(summary, USER)
    check("user retrained again", r["action"] == "retrained", str(r))
    check("new snapshot id", r.get("snapshot_id") != first_snapshot)
    check("13 training samples", r.get("training_samples") == 13, str(r.get("training_samples")))

    # --- 5. training cutoff (rollback) is respected ---
    print("\n[5] Pass with a rolled-back state (cutoff before the new row)")
    # Recreate what a rollback leaves behind: first snapshot active + a
    # training cutoff that hides the row added in step 4.
    cutoff = (base + timedelta(hours=1)).isoformat()
    storage.upsert_user_model_state(USER, first_snapshot, cutoff)
    summary = auto_retrain.run_pass()
    r = result_for(summary, USER)
    check("user skipped (cutoff hides new row)", r["action"] == "skipped", r.get("reason", ""))
    active = model_service.get_active_weights(USER)
    check("rolled-back snapshot stays active",
          active["model_meta"].get("snapshot_id") == first_snapshot)

    # --- 6. single-class feedback (only false positives) -> synthetic anchor ---
    print("\n[6] User with only false-positive feedback (single class)")
    # Panic-ish vitals the model wrongly fired on: elevated HR, lowish HRV, still.
    for i in range(12):
        storage.append_feedback({
            "user_id": FP_USER,
            "was_panic": False,
            "severity": None,
            "detected_by_model": True,
            "current_hr": 115.0 + i,
            "current_hrv": 27.0 + (i % 4),
            "current_motion_intensity": 0.05,
            "model_probability": 0.8,
            "timestamp": (base + timedelta(minutes=40 + i)).isoformat(),
        })
    summary = auto_retrain.run_pass()
    r = result_for(summary, FP_USER)
    check("FP-only user retrained", r["action"] == "retrained", str(r))
    check("training_samples counts real rows only", r.get("training_samples") == 12,
          str(r.get("training_samples")))
    snap = storage.list_model_snapshots(FP_USER)[0]
    check("note mentions synthetic anchor", "synthetic" in (snap.get("note") or ""),
          snap.get("note", ""))

    import math
    w = model_service.get_active_weights(FP_USER)["weights"]

    def prob(hr, hrv, motion):
        z = w[0] * hr + w[1] * hrv + w[2] * motion + w[4]
        return 1.0 / (1.0 + math.exp(-z))

    p_fp = prob(120.0, 28.0, 0.05)      # the user's false-positive region
    p_panic = prob(155.0, 10.0, 0.10)   # canonical panic from the priors
    check("FP region now below 0.5", p_fp < 0.5, f"p={p_fp:.3f}")
    check("canonical panic still detected", p_panic > 0.5, f"p={p_panic:.3f}")

    # --- 7. status view reflects the last pass ---
    print("\n[7] status()")
    s = auto_retrain.status()
    check("interval present", isinstance(s.get("interval_minutes"), int))
    check("last_run recorded", s.get("last_run") is not None
          and s["last_run"]["users_checked"] >= 1)

    print()
    if check.failed:
        print("AUTO-RETRAIN TEST: FAILURES detected. See above.")
        return 1
    print("AUTO-RETRAIN TEST: all checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
