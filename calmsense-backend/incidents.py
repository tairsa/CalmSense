"""Server incidents: what the daily digest and the admin Problems page show.

Alex's design (2026-11-02): only real failures count (a retrain that raised,
or an auto-retrain pass that never ran; a lower accuracy is fine). One row per
(code, user): a repeat raises `count` and `last_seen` instead of adding rows.
An incident resolves itself after 72 h without a repeat.

Codes continue the phone's E-numbering (ErrorCatalog.kt); never reuse one.
"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

import storage

log = logging.getLogger("calmsense.incidents")

RETRAIN_FAILED = 15   # E-15: one user's retrain raised
PASS_MISSED = 16      # E-16: no auto-retrain pass finished in time

QUIET_HOURS = 72
# The daily check runs a pass itself, so the last one is <= ~24 h old unless
# yesterday's check (and the hourly loop) both missed.
PASS_MAX_AGE_HOURS = 26
LOG_CHARS = 4000
LAST_PASS_KEY = "auto_retrain_last_pass_at"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _parse(ts: str | None) -> datetime | None:
    if not ts:
        return None
    dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def record(code: int, user_id: str | None, summary: str, logs: str = "",
           now: datetime | None = None) -> None:
    """Open (or re-open and count) the incident for (code, user). Never raises:
    a broken incident store must not break the retrain loop that reports it."""
    try:
        now_iso = (now or _now()).isoformat()
        key = f"E-{code}:{user_id or '-'}"
        old = storage.get_incident(key)
        storage.upsert_incident({
            "key": key,
            "code": code,
            "user_id": user_id,
            "summary": summary,
            "logs": logs[-LOG_CHARS:],
            "count": (old["count"] + 1) if old and not old.get("resolved_at") else 1,
            "first_seen": old["first_seen"] if old and not old.get("resolved_at") else now_iso,
            "last_seen": now_iso,
            "resolved_at": None,
        })
    except Exception:
        log.exception("Could not record incident E-%s for %s", code, user_id)


def resolve_quiet(now: datetime | None = None) -> int:
    """Mark open incidents with no repeat for QUIET_HOURS as resolved."""
    now = now or _now()
    resolved = 0
    for row in storage.read_incidents():
        if row.get("resolved_at"):
            continue
        last = _parse(row.get("last_seen"))
        if last and now - last >= timedelta(hours=QUIET_HOURS):
            storage.upsert_incident({**row, "resolved_at": now.isoformat()})
            resolved += 1
    return resolved


def mark_pass_finished(now: datetime | None = None) -> None:
    try:
        storage.set_server_state(LAST_PASS_KEY, (now or _now()).isoformat())
    except Exception:
        log.exception("Could not save the last-pass time")


def check_pass_age(now: datetime | None = None) -> None:
    """Open E-16 when no pass finished within PASS_MAX_AGE_HOURS."""
    now = now or _now()
    last = _parse(storage.get_server_state(LAST_PASS_KEY))
    if last is None:
        # Fresh install: nothing to compare yet; the pass about to run sets it.
        return
    age_h = (now - last).total_seconds() / 3600
    if age_h > PASS_MAX_AGE_HOURS:
        record(PASS_MISSED, None,
               f"No auto-retrain pass finished for {age_h:.0f} h (limit {PASS_MAX_AGE_HOURS} h). "
               "The server was asleep or the loop is stuck.",
               f"last pass finished at {last.isoformat()}", now=now)


def digest(now: datetime | None = None) -> dict:
    """The daily digest: open incidents in full, plus what resolved in the last day.
    `text` is Markdown (GitHub job summary); the lists are for the admin app."""
    now = now or _now()
    rows = storage.read_incidents()
    open_rows = [r for r in rows if not r.get("resolved_at")]
    day_ago = now - timedelta(days=1)
    fixed = [r for r in rows if r.get("resolved_at") and (_parse(r["resolved_at"]) or now) >= day_ago]

    lines = [f"# CalmSense server digest · {now.date().isoformat()}", "",
             f"**{len(open_rows)} open**, {len(fixed)} fixed in the last 24 h.", ""]
    for r in open_rows:
        lines += [
            f"## E-{r['code']} · {r['summary']}",
            f"- user: `{r.get('user_id') or '-'}`",
            f"- seen {r['count']}×, first {r['first_seen']}, last {r['last_seen']}",
            "", "```", (r.get("logs") or "(no logs)").strip(), "```", "",
        ]
    for r in fixed:
        lines.append(f"- fixed: E-{r['code']} `{r.get('user_id') or '-'}` (quiet for {QUIET_HOURS} h)")
    return {"open": len(open_rows), "fixed_last_day": len(fixed),
            "incidents": open_rows, "fixed": fixed, "text": "\n".join(lines) + "\n"}
