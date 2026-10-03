"""Admin API auth, and the full learning loop: phone feedback -> retrain ->
weights the phone downloads -> the phone's own decision rule."""

from __future__ import annotations

import json
import math
from datetime import datetime, timedelta, timezone

import jwt
import pytest

import auth
import model_service
import storage
from conftest import as_user

ADMIN_ROUTES = ["/api/v1/admin/users", "/api/v1/admin/metrics/global", "/api/v1/admin/admins",
                "/api/v1/admin/users/u/sensor-data", "/api/v1/admin/auto-retrain/status"]


@pytest.fixture
def admin_token(raw_client):
    storage.create_admin("Root@Example.com", auth.hash_password("correct horse"), "Root")
    r = raw_client.post("/api/v1/admin/auth/login", json={"email": "root@example.com", "password": "correct horse"})
    assert r.status_code == 200
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


def test_admin_routes_reject_missing_and_forged_tokens(raw_client):
    forged = jwt.encode({"sub": "1", "email": "root@example.com",
                         "exp": datetime.now(timezone.utc) + timedelta(hours=1)}, "x" * 48, algorithm="HS256")
    for path in ADMIN_ROUTES:
        assert raw_client.get(path).status_code == 401, path
        assert raw_client.get(path, headers={"Authorization": f"Bearer {forged}"}).status_code == 401, path


def test_login_rejects_bad_password_and_unknown_email(raw_client):
    storage.create_admin("a@x.com", auth.hash_password("right-password"), None)
    assert raw_client.post("/api/v1/admin/auth/login", json={"email": "a@x.com", "password": "wrong"}).status_code == 401
    assert raw_client.post("/api/v1/admin/auth/login", json={"email": "b@x.com", "password": "wrong"}).status_code == 401


def test_password_hash_roundtrip():
    h = auth.hash_password("pw")
    assert auth.verify_password("pw", h) and not auth.verify_password("PW", h)
    assert not auth.verify_password("pw", "garbage")


def test_admins_list_never_leaks_hashes(raw_client, admin_token):
    admins = raw_client.get("/api/v1/admin/admins", headers=admin_token).json()["admins"]
    assert admins and all("password_hash" not in a for a in admins)


def test_register_requires_an_admin(raw_client, admin_token):
    body = {"email": "new@x.com", "password": "longenough"}
    assert raw_client.post("/api/v1/admin/auth/register", json=body).status_code == 401
    assert raw_client.post("/api/v1/admin/auth/register", json=body, headers=admin_token).status_code == 200
    assert raw_client.post("/api/v1/admin/auth/register", json=body, headers=admin_token).status_code == 409


def test_deactivated_admin_token_stops_working(raw_client, admin_token):
    path = storage.ADMIN_USERS_FILE
    with open(path, encoding="utf-8") as f:
        rows = json.load(f)
    rows[0]["is_active"] = False
    with open(path, "w", encoding="utf-8") as f:
        json.dump(rows, f)
    assert raw_client.get("/api/v1/admin/users", headers=admin_token).status_code == 401


# --- Baseline model contract ------------------------------------------------

def phone_predict(w, hr, hrv, motion, baseline=52.5):
    """Exactly PanicModel.predict on the phone: hrv_rel = ln(hrv / baseline)."""
    rel = model_service.hrv_rel(hrv, baseline)
    return 1 / (1 + math.exp(-(w[0] * hr + w[1] * hrv + w[2] * motion + w[3] * rel + w[4])))


def test_baseline_weights_classify_the_canonical_profiles():
    base = model_service.load_baseline()
    assert base["note"].startswith("Synthetic"), "ml/model_weights.json missing or malformed"
    w = base["weights"]
    assert len(w) == 5 and w[1] == 0.0, "raw HRV must not be weighted"
    # Midpoints of the priors in ml/generate_data.py (motion in m/s^2), for a
    # 52.5 ms baseline.
    assert phone_predict(w, 70, 52, 0.25) < 0.5    # resting
    assert phone_predict(w, 92, 35, 0.5) < 0.5     # stress, not panic
    assert phone_predict(w, 100, 37, 1.9) < 0.5    # light activity
    assert phone_predict(w, 145, 15, 0.75) > 0.5   # panic
    assert phone_predict(w, 145, 15, 1.5) > 0.5    # panic, restless
    assert phone_predict(w, 145, 25, 5.0) < 0.5    # exercise: motion must veto


def test_motion_is_in_watch_units():
    """Everyday wrist movement at a normal heart rate (the June 2026 median at
    HR 70-85 was ~1.1 m/s^2) is neither panic nor 'exercise' that hides one."""
    w = model_service.load_baseline()["weights"]
    assert model_service.FEATURE_NAMES[2] == "motion_ms2"
    assert phone_predict(w, 78, 50, 1.1) < 0.05
    # A panic while fidgeting at 1 m/s^2 used to be capped to 1.0 = exercise.
    assert phone_predict(w, 140, 15, 1.0) > 0.5


def test_estimated_hrv_at_rest_is_not_panic():
    """The bug hrv_rel fixes: a bpm-derived estimate reads ~10 ms for a resting
    person. Against that source's own ~11 ms baseline it is no drop at all."""
    w = model_service.load_baseline()["weights"]
    assert phone_predict(w, 75, 10, 0.1, baseline=11) < 0.05
    # The same reading against a real-IBI scale baseline would look like panic.
    assert phone_predict(w, 120, 10, 0.1, baseline=52.5) > 0.5


def test_snapshots_trained_on_raw_hrv_are_not_served(client):
    old = storage.insert_model_snapshot({"user_id": "u", "weights": [0.07, -0.35, -11.9, 0.0, 1.4],
                                         "feature_names": ["hr", "hrv", "motion", "reserved", "bias"],
                                         "source": "trained"})
    storage.upsert_user_model_state("u", old["id"], None)
    assert client.get("/api/v1/sensor-data", headers=as_user("u")).json()["source"] == "baseline"


def test_feedback_baseline_is_stored_only_when_sent(client):
    fb = {"user_id": "x", "was_panic": False, "detected_by_model": True, "current_hrv": 9.0}
    client.post("/api/v1/panic-feedback", json=fb, headers=as_user("old-phone"))
    client.post("/api/v1/panic-feedback", json={**fb, "hrv_baseline": 11.0}, headers=as_user("new-phone"))
    rows = {r["user_id"]: r for r in storage.read_all_feedback()}
    assert "hrv_baseline" not in rows["old-phone"]  # works against a table without the column
    assert rows["new-phone"]["hrv_baseline"] == 11.0
    assert client.post("/api/v1/panic-feedback", json={**fb, "hrv_baseline": 0}, headers=as_user("x")).status_code == 422


def test_admin_users_report_hrv_source_mix(client, raw_client, admin_token):
    s = {"user_id": "x", "panic_attack_detection": False, "current_hr": 70.0,
         "current_hrv": 40.0, "current_motion_intensity": 0.0}
    for src in ["real_ibi", "real_ibi", "bpm_derived", None]:
        client.post("/api/v1/sensor-data", json={**s, "hrv_source": src}, headers=as_user("alice"))
    users = raw_client.get("/api/v1/admin/users", headers=admin_token).json()["users"]
    assert users[0]["hrv_sources"] == {"real_ibi": 2, "bpm_derived": 1, "unknown": 1}


def test_feedback_retrain_serves_new_weights_to_the_phone(client, raw_client, admin_token):
    t0 = datetime(2026, 1, 1, tzinfo=timezone.utc)
    for i in range(12):
        panic = i % 2 == 0
        client.post("/api/v1/panic-feedback", headers=as_user("alice"), json={
            "user_id": "alice", "was_panic": panic, "severity": 7 if panic else None,
            "detected_by_model": True, "current_hr": 140.0 if panic else 75.0,
            "current_hrv": 15.0 if panic else 50.0, "hrv_baseline": 52.5,
            "current_motion_intensity": 0.05,
            "timestamp": (t0 + timedelta(hours=i)).isoformat(),
        })

    r = raw_client.post("/api/v1/admin/users/alice/model/retrain", json={}, headers=admin_token)
    assert r.status_code == 200, r.text

    served = client.get("/api/v1/sensor-data", headers=as_user("alice")).json()
    assert served["source"] == "trained" and len(served["weights"]) == 5
    w = served["weights"]
    assert w[1] == 0.0
    assert phone_predict(w, 140, 15, 0.05) > 0.5 > phone_predict(w, 75, 50, 0.05)

    # Other users are untouched.
    assert client.get("/api/v1/sensor-data", headers=as_user("bob")).json()["source"] == "baseline"

    users = raw_client.get("/api/v1/admin/users", headers=admin_token).json()["users"]
    assert users == [{"user_id": "alice", "sensor_count": 0, "feedback_count": 12, "report_count": 0,
                      "last_seen": (t0 + timedelta(hours=11)).isoformat(), "model_source": "trained",
                      "hrv_sources": {}}]

    # Reset puts the baseline back.
    raw_client.post("/api/v1/admin/users/alice/model/reset", headers=admin_token)
    assert client.get("/api/v1/sensor-data", headers=as_user("alice")).json()["source"] == "baseline"


def test_retrain_refuses_too_little_data(raw_client, admin_token):
    r = raw_client.post("/api/v1/admin/users/nobody/model/retrain", json={}, headers=admin_token)
    assert r.status_code == 400


# --- Admin stats come from the database, not a table download ------------

class _Rpc:
    def __init__(self, data=None, error=None):
        self.data, self.error = data, error

    def execute(self):
        if self.error:
            raise self.error
        return self


class _FakeSupabase:
    def __init__(self, rpc):
        self._rpc = rpc

    def rpc(self, name):
        assert name == "admin_user_stats"
        return self._rpc


def test_user_stats_reads_the_database_function(monkeypatch):
    row = {"user_id": "u1", "sensor_count": 300000, "feedback_count": 4, "report_count": 1,
           "last_seen": "2026-10-03T19:00:00+00:00", "hrv_real_ibi": 250000,
           "hrv_bpm_derived": 50000, "hrv_no_hrv": 0, "hrv_unknown": 0}
    monkeypatch.setattr(storage, "_supabase", _FakeSupabase(_Rpc([row])))
    monkeypatch.setattr(storage, "read_all_records", lambda: pytest.fail("downloaded the sensor table"))
    assert storage.user_stats() == [{
        "user_id": "u1", "sensor_count": 300000, "feedback_count": 4, "report_count": 1,
        "last_seen": "2026-10-03T19:00:00+00:00",
        "hrv_sources": {"real_ibi": 250000, "bpm_derived": 50000},
    }]


def test_user_stats_still_works_before_the_migration(monkeypatch):
    monkeypatch.setattr(storage, "_supabase", _FakeSupabase(_Rpc(error=RuntimeError("function not found"))))
    monkeypatch.setattr(storage, "read_all_records", lambda: [{"user_id": "u1", "hrv_source": "real_ibi", "timestamp": "t1"}])
    monkeypatch.setattr(storage, "read_all_feedback", lambda: [])
    monkeypatch.setattr(storage, "read_all_reports", lambda: [{"user_id": "u2", "timestamp": "t2"}])
    assert [(u["user_id"], u["sensor_count"], u["report_count"]) for u in storage.user_stats()] == \
        [("u1", 1, 0), ("u2", 0, 1)]


def test_admin_sensor_tab_returns_the_newest_page_and_the_total(client, raw_client, admin_token):
    for i in range(7):
        client.post("/api/v1/sensor-data", headers=as_user("alice"), json={
            "user_id": "x", "panic_attack_detection": False, "current_hr": 60.0 + i,
            "current_hrv": 40.0, "current_motion_intensity": 0.1,
            "timestamp": f"2026-10-03T10:00:0{i}+00:00"})
    body = raw_client.get("/api/v1/admin/users/alice/sensor-data?limit=3", headers=admin_token).json()
    assert body["total"] == 7
    assert [r["current_hr"] for r in body["sensor_data"]] == [66.0, 65.0, 64.0]


def test_select_all_stops_at_the_limit(monkeypatch):
    class Table:
        requests = 0
        def __init__(self): self.rows = list(range(2500))
        def select(self, *a, **k): return self
        def eq(self, *a): return self
        def order(self, *a, **k): return self
        def range(self, lo, hi): self.lo, self.hi = lo, hi; return self
        def execute(self):
            Table.requests += 1
            return type("R", (), {"data": self.rows[self.lo:self.hi + 1][:1000]})()
    t = Table()
    monkeypatch.setattr(storage, "_supabase", type("C", (), {"table": lambda self, name: t})())
    assert len(storage._select_all("sensor_data", limit=1500)) == 1500
    assert Table.requests == 2
