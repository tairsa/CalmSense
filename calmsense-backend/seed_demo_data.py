"""
seed_demo_data.py - populate Supabase with one realistic panic-attack
"episode" worth of data, for a CalmSense demo / presentation.

Run from the calmsense-backend/ folder:

    python seed_demo_data.py

Writes (via storage.* so it uses the same Supabase-with-JSON-fallback path
the production API uses):

  * 20 sensor_data rows   - a ~30 min timeline of readings showing a panic
                            attack rise and recovery (rest -> onset -> peak
                            -> recovery), so a chart of HR vs time tells a
                            visible story.
  * 1 panic_feedback row  - labeled training signal at the peak
                            (was_panic=True, severity=8, model hit p=0.97).
  * 1 panic_reports row   - what the user journaled afterwards (feeling,
                            symptoms, trigger, what helped, GPS, duration).

After running, refresh the three tables in Supabase Table Editor.
"""

from datetime import datetime, timedelta, timezone

import storage

USER_ID = "demo_user"

# Anchor "now" so every timestamp forms a continuous timeline ending now.
# Bump this back by e.g. timedelta(days=1) if you want it to look older.
NOW = datetime.now(timezone.utc)


def iso(t: datetime) -> str:
    return t.isoformat()


# -----------------------------------------------------------------------------
# A 30-min panic-episode timeline at ~1.5 min cadence (20 readings).
#   5 calm  +  5 onset  +  5 peak  +  5 recovery
# -----------------------------------------------------------------------------
def sensor_timeline() -> list[dict]:
    start = NOW - timedelta(minutes=30)
    rows: list[dict] = []

    # calm baseline
    for i in range(5):
        rows.append(dict(
            user_id=USER_ID,
            panic_attack_detection=False,
            current_hr=round(72 + i * 0.8, 2),
            current_hrv=round(46 - i * 0.4, 2),
            current_motion_intensity=0.10,
            timestamp=iso(start + timedelta(minutes=i * 1.5)),
        ))

    # onset (HR rising, HRV falling) - not yet flagged
    for i in range(5):
        rows.append(dict(
            user_id=USER_ID,
            panic_attack_detection=False,
            current_hr=round(85 + i * 9, 2),        # 85 -> 121
            current_hrv=round(32 - i * 4, 2),       # 32 -> 16
            current_motion_intensity=0.18,
            timestamp=iso(start + timedelta(minutes=(5 + i) * 1.5)),
        ))

    # peak panic - the model fires
    for i in range(5):
        rows.append(dict(
            user_id=USER_ID,
            panic_attack_detection=True,
            current_hr=round(140 + (i % 3), 2),     # ~140-142
            current_hrv=round(11 - i * 0.3, 2),     # 11 -> 9.8
            current_motion_intensity=0.08,
            timestamp=iso(start + timedelta(minutes=(10 + i) * 1.5)),
        ))

    # recovery
    for i in range(5):
        rows.append(dict(
            user_id=USER_ID,
            panic_attack_detection=False,
            current_hr=round(128 - i * 8, 2),       # 128 -> 96
            current_hrv=round(18 + i * 3, 2),       # 18 -> 30
            current_motion_intensity=0.20,
            timestamp=iso(start + timedelta(minutes=(15 + i) * 1.5)),
        ))

    return rows


# -----------------------------------------------------------------------------
# One feedback row at the peak (the labeled training signal).
# -----------------------------------------------------------------------------
def feedback_row() -> dict:
    return dict(
        user_id=USER_ID,
        was_panic=True,
        severity=8,
        detected_by_model=True,
        current_hr=141.0,
        current_hrv=10.5,
        current_motion_intensity=0.08,
        model_probability=0.97,
        timestamp=iso(NOW - timedelta(minutes=18)),
    )


# -----------------------------------------------------------------------------
# One journaled report (what the user filled in afterwards).
# Coordinates are a Tel Aviv landmark - feel free to change.
# -----------------------------------------------------------------------------
def report_row() -> dict:
    return dict(
        user_id=USER_ID,
        timestamp=iso(NOW - timedelta(minutes=18)),
        severity=8,
        detected_by_model=True,
        feeling="Overwhelmed and shaky, like the room was closing in.",
        symptoms=["rapid_heart_rate", "shortness_of_breath", "dizziness"],
        activity_before="work",
        what_helped="Breathing exercises in the app",
        duration_minutes=15,
        latitude=32.0853,
        longitude=34.7818,
        location_accuracy_m=12.0,
        current_hr=141.0,
        current_hrv=10.5,
        current_motion_intensity=0.08,
    )


def main() -> None:
    print(f"[seed] storage backend = {storage.storage_backend()}")

    timeline = sensor_timeline()
    print(f"[seed] writing {len(timeline)} sensor_data rows ...")
    for rec in timeline:
        storage.append_record(rec)

    print("[seed] writing 1 panic_feedback row ...")
    storage.append_feedback(feedback_row())

    print("[seed] writing 1 panic_reports row ...")
    storage.append_report(report_row())

    print("[seed] done. Refresh the three tables in Supabase Table Editor.")


if __name__ == "__main__":
    main()
