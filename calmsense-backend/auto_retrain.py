"""Automatic per-user model retraining.

A background loop (started from the FastAPI lifespan) that periodically scans
every user with labeled feedback and retrains their model when there is enough
new, two-class data since the active snapshot's `trained_through` mark. Each
user's stored training cutoff (set by a rollback) is respected, matching what
the admin app's manual retrain does.

Configuration (env vars):
    CALMSENSE_AUTO_RETRAIN_MINUTES  interval between passes (default 60; 0 disables)
"""

from __future__ import annotations

import asyncio
import logging
import os
from datetime import datetime, timezone

import model_service
import storage

log = logging.getLogger("calmsense.auto_retrain")

INTERVAL_MINUTES = int(os.environ.get("CALMSENSE_AUTO_RETRAIN_MINUTES", "60"))

# Summary of the most recent pass, served by the admin status endpoint.
_last_run: dict | None = None


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _check_user(user_id: str) -> dict:
    """Decide whether `user_id` needs a retrain and do it. Returns an outcome row."""
    state = storage.get_user_model_state(user_id)
    cutoff = state.get("training_cutoff") if state else None
    samples = model_service.usable_samples(user_id, cutoff)

    if len(samples) < model_service.MIN_TRAIN_SAMPLES:
        return {"user_id": user_id, "action": "skipped",
                "reason": f"only {len(samples)} usable rows "
                          f"(need {model_service.MIN_TRAIN_SAMPLES})"}
    # Single-class feedback is fine: retrain anchors the missing class with
    # synthetic samples from the baseline priors.

    active = model_service.get_active_weights(user_id)
    trained_through = (active.get("model_meta") or {}).get("trained_through")
    newest = max((s[4] for s in samples), default=None)
    if (active["source"] == "trained" and trained_through
            and newest and newest <= trained_through):
        return {"user_id": user_id, "action": "skipped",
                "reason": "no new feedback since last training"}

    snap = model_service.retrain_user_model(user_id, cutoff)
    return {"user_id": user_id, "action": "retrained",
            "snapshot_id": snap.get("id"),
            "training_samples": snap.get("training_samples"),
            "test_accuracy": snap.get("test_accuracy")}


def run_pass() -> dict:
    """One retraining sweep over all users with feedback. Safe to call directly
    (admin "run now" endpoint) — outcomes are logged and kept for the status view.
    """
    global _last_run
    started = _now_iso()
    user_ids = sorted({r.get("user_id") for r in storage.read_all_feedback()
                       if r.get("user_id")})

    results = []
    for user_id in user_ids:
        try:
            results.append(_check_user(user_id))
        except ImportError:
            results.append({"user_id": user_id, "action": "error",
                            "reason": "scikit-learn not installed on the server"})
        except Exception as e:  # one bad user must not kill the loop
            log.exception("Auto-retrain failed for user %s", user_id)
            results.append({"user_id": user_id, "action": "error", "reason": str(e)})

    retrained = [r for r in results if r["action"] == "retrained"]
    for r in retrained:
        log.info("Auto-retrained %s: %s samples, accuracy %.3f (snapshot %s)",
                 r["user_id"], r["training_samples"], r["test_accuracy"] or 0.0,
                 r["snapshot_id"])
    log.info("Auto-retrain pass: %d user(s) checked, %d retrained",
             len(user_ids), len(retrained))

    _last_run = {"started_at": started, "finished_at": _now_iso(),
                 "users_checked": len(user_ids), "retrained": len(retrained),
                 "results": results}
    return _last_run


def status() -> dict:
    return {
        "enabled": INTERVAL_MINUTES > 0,
        "interval_minutes": INTERVAL_MINUTES,
        "min_train_samples": model_service.MIN_TRAIN_SAMPLES,
        "last_run": _last_run,
    }


async def _loop() -> None:
    # First pass right away so a restarted server catches up, then on interval.
    while True:
        try:
            await asyncio.to_thread(run_pass)
        except Exception:
            log.exception("Auto-retrain pass crashed; will retry next interval")
        await asyncio.sleep(INTERVAL_MINUTES * 60)


def start() -> asyncio.Task | None:
    """Launch the loop. Returns the task, or None when disabled."""
    # uvicorn only configures its own loggers; without a root handler the
    # loop's activity would be invisible in the server console.
    if not logging.getLogger().handlers:
        logging.basicConfig(level=logging.INFO,
                            format="%(levelname)s:     %(name)s - %(message)s")
    if INTERVAL_MINUTES <= 0:
        log.info("Auto-retrain disabled (CALMSENSE_AUTO_RETRAIN_MINUTES=0)")
        return None
    log.info("Auto-retrain loop starting (every %d min)", INTERVAL_MINUTES)
    return asyncio.create_task(_loop(), name="calmsense-auto-retrain")


def stop(task: asyncio.Task | None) -> None:
    if task is not None:
        task.cancel()
