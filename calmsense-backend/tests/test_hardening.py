"""Rate limits on guessable credentials, and 500s that do not leak internals."""

from __future__ import annotations

import auth
import main
import storage
from conftest import as_user


def test_admin_login_is_rate_limited_per_email(raw_client):
    storage.create_admin("a@x.com", auth.hash_password("right-password"), None)
    login = lambda pw, email="a@x.com": raw_client.post(  # noqa: E731
        "/api/v1/admin/auth/login", json={"email": email, "password": pw})
    for _ in range(auth.LOGIN_LIMITER.limit):
        assert login("wrong").status_code == 401
    blocked = login("right-password")
    assert blocked.status_code == 429 and int(blocked.headers["Retry-After"]) > 0
    # Case and whitespace do not reset the count; other accounts are unaffected.
    assert login("wrong", "  A@X.com ").status_code == 429
    assert login("wrong", "b@x.com").status_code == 401


def test_successful_logins_do_not_count(raw_client):
    storage.create_admin("a@x.com", auth.hash_password("right-password"), None)
    for _ in range(auth.LOGIN_LIMITER.limit + 5):
        r = raw_client.post("/api/v1/admin/auth/login", json={"email": "a@x.com", "password": "right-password"})
        assert r.status_code == 200


def test_code_redemption_is_rate_limited_per_patient(client):
    redeem = lambda who: client.post(  # noqa: E731
        "/api/v1/consent-codes/redeem", json={"code": "NOP-E22", "patient_id": "x"}, headers=as_user(who))
    for _ in range(auth.REDEEM_LIMITER.limit):
        assert redeem("guesser").status_code == 404
    assert redeem("guesser").status_code == 429
    assert redeem("someone-else").status_code == 404


def test_failed_writes_do_not_leak_the_exception(client, monkeypatch):
    def boom(_record):
        raise RuntimeError('duplicate key value violates unique constraint "sensor_data_pkey"')
    monkeypatch.setattr(main, "append_record", boom)
    r = client.post("/api/v1/sensor-data", headers=as_user("alice"), json={
        "user_id": "x", "panic_attack_detection": False, "current_hr": 70.0,
        "current_hrv": 40.0, "current_motion_intensity": 0.1})
    assert r.status_code == 500
    assert r.json() == {"success": False, "message": "Failed to save data."}
