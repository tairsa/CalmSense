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

def phone_predict(w, hr, hrv, motion):
    """Exactly PanicModel.predict on the phone (weights[3] is reserved)."""
    return 1 / (1 + math.exp(-(w[0] * hr + w[1] * hrv + w[2] * motion + w[4])))


def test_baseline_weights_classify_the_canonical_profiles():
    base = model_service.load_baseline()
    assert base["note"].startswith("Synthetic"), "ml/model_weights.json missing or malformed"
    w = base["weights"]
    assert len(w) == 5 and w[3] == 0.0
    # Midpoints of the priors in ml/generate_data.py.
    assert phone_predict(w, 70, 52, 0.05) < 0.5    # resting
    assert phone_predict(w, 92, 35, 0.10) < 0.5    # stress, not panic
    assert phone_predict(w, 145, 15, 0.15) > 0.5   # panic
    assert phone_predict(w, 145, 25, 0.75) < 0.5   # exercise: motion must veto


def test_feedback_retrain_serves_new_weights_to_the_phone(client, raw_client, admin_token):
    t0 = datetime(2026, 1, 1, tzinfo=timezone.utc)
    for i in range(12):
        panic = i % 2 == 0
        client.post("/api/v1/panic-feedback", headers=as_user("alice"), json={
            "user_id": "alice", "was_panic": panic, "severity": 7 if panic else None,
            "detected_by_model": True, "current_hr": 140.0 if panic else 75.0,
            "current_hrv": 15.0 if panic else 50.0, "current_motion_intensity": 0.05,
            "timestamp": (t0 + timedelta(hours=i)).isoformat(),
        })

    r = raw_client.post("/api/v1/admin/users/alice/model/retrain", json={}, headers=admin_token)
    assert r.status_code == 200, r.text

    served = client.get("/api/v1/sensor-data", headers=as_user("alice")).json()
    assert served["source"] == "trained" and len(served["weights"]) == 5
    w = served["weights"]
    assert phone_predict(w, 140, 15, 0.05) > 0.5 > phone_predict(w, 75, 50, 0.05)

    # Other users are untouched.
    assert client.get("/api/v1/sensor-data", headers=as_user("bob")).json()["source"] == "baseline"

    users = raw_client.get("/api/v1/admin/users", headers=admin_token).json()["users"]
    assert users == [{"user_id": "alice", "sensor_count": 0, "feedback_count": 12, "report_count": 0,
                      "last_seen": (t0 + timedelta(hours=11)).isoformat(), "model_source": "trained"}]

    # Reset puts the baseline back.
    raw_client.post("/api/v1/admin/users/alice/model/reset", headers=admin_token)
    assert client.get("/api/v1/sensor-data", headers=as_user("alice")).json()["source"] == "baseline"


def test_retrain_refuses_too_little_data(raw_client, admin_token):
    r = raw_client.post("/api/v1/admin/users/nobody/model/retrain", json={}, headers=admin_token)
    assert r.status_code == 400
