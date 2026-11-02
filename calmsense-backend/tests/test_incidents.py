"""Server incidents (E-15 / E-16), the daily check and the admin list."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import auth
import auto_retrain
import incidents
import main
import storage

T0 = datetime(2026, 11, 1, 8, 0, tzinfo=timezone.utc)


def test_repeat_counts_then_resolves_after_72h_quiet():
    incidents.record(15, "u1", "boom", "trace 1", now=T0)
    incidents.record(15, "u1", "boom", "trace 2", now=T0 + timedelta(hours=1))
    [row] = storage.read_incidents()
    assert (row["count"], row["first_seen"], row["logs"]) == (2, T0.isoformat(), "trace 2")

    assert incidents.resolve_quiet(now=T0 + timedelta(hours=72)) == 0  # 71 h after the last one
    assert incidents.resolve_quiet(now=T0 + timedelta(hours=73)) == 1
    assert incidents.digest(now=T0 + timedelta(hours=73))["open"] == 0

    # Coming back after it was fixed starts a fresh count.
    incidents.record(15, "u1", "boom", now=T0 + timedelta(days=5))
    [row] = storage.read_incidents()
    assert (row["count"], row["resolved_at"]) == (1, None)


def test_missed_pass_opens_e16():
    incidents.check_pass_age(now=T0)  # never ran: nothing to compare yet
    assert storage.read_incidents() == []
    incidents.mark_pass_finished(now=T0)
    incidents.check_pass_age(now=T0 + timedelta(hours=25))
    assert storage.read_incidents() == []
    incidents.check_pass_age(now=T0 + timedelta(hours=30))
    [row] = storage.read_incidents()
    assert (row["code"], row["user_id"]) == (16, None)


def test_daily_check_guards_and_records_a_failed_retrain(raw_client, monkeypatch):
    url = "/api/v1/ops/daily-check"
    assert raw_client.post(url).status_code == 503  # no token configured = off

    monkeypatch.setattr(main, "_CRON_TOKEN", "s3cret")
    assert raw_client.post(url).status_code == 401
    assert raw_client.post(url, headers={"X-Cron-Token": "wrong"}).status_code == 401

    storage.append_feedback({"user_id": "u1", "was_panic": True})

    def explode(user_id):
        raise ValueError("bad weights")
    monkeypatch.setattr(auto_retrain, "_check_user", explode)

    body = raw_client.post(url, headers={"X-Cron-Token": "s3cret"}).json()
    assert body["open"] == 1
    assert body["incidents"][0]["code"] == 15
    assert "ValueError: bad weights" in body["text"] and "Traceback" in body["text"]
    assert storage.get_server_state(incidents.LAST_PASS_KEY) is not None


def test_admin_sees_open_incidents_first(raw_client):
    incidents.record(16, None, "old", now=T0 - timedelta(days=10))
    incidents.resolve_quiet()
    incidents.record(15, "u1", "new")
    storage.create_admin("root@example.com", auth.hash_password("correct horse"), "Root")
    tok = raw_client.post("/api/v1/admin/auth/login",
                          json={"email": "root@example.com", "password": "correct horse"}).json()["access_token"]
    body = raw_client.get("/api/v1/admin/incidents", headers={"Authorization": f"Bearer {tok}"}).json()
    assert body["open"] == 1
    assert [r["code"] for r in body["incidents"]] == [15, 16]
