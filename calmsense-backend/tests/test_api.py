"""Phone-facing API: identity, validation, consent flow, therapist access."""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone

import main
import storage
from conftest import as_user

SENSOR = {
    "user_id": "ignored",
    "panic_attack_detection": False,
    "current_hr": 72.0,
    "current_hrv": 48.0,
    "current_motion_intensity": 0.05,
    "hrv_source": "real_ibi",
}
REPORT = {"user_id": "ignored", "severity": 6, "detected_by_model": True, "latitude": 32.0, "longitude": 34.8}


def test_health(raw_client):
    body = raw_client.get("/health").json()
    assert body["status"] == "ok"
    assert body["version"] == main.APP_VERSION
    assert body["storage"] == "json"


def test_endpoints_require_a_bearer_token(raw_client):
    assert raw_client.post("/api/v1/sensor-data", json=SENSOR).status_code == 401
    assert raw_client.get("/api/v1/sensor-data").status_code == 401
    assert raw_client.get("/api/v1/profile").status_code == 401
    assert raw_client.get("/api/v1/therapist/t/patients").status_code == 401
    assert raw_client.post("/api/v1/consent-codes/redeem", json={"code": "x", "patient_id": "p"}).status_code == 401


def test_identity_comes_from_token_not_body(client):
    r = client.post("/api/v1/sensor-data", json={**SENSOR, "user_id": "victim"}, headers=as_user("alice"))
    assert r.status_code == 200
    rows = storage.read_all_records()
    assert [r["user_id"] for r in rows] == ["alice"]
    assert rows[0]["timestamp"]  # server fills it in
    assert rows[0]["hrv_source"] == "real_ibi"


def test_invalid_payloads_are_rejected(client):
    h = as_user("alice")
    fb = {"user_id": "x", "was_panic": True, "detected_by_model": True}
    assert client.post("/api/v1/panic-feedback", json={**fb, "severity": 11}, headers=h).status_code == 422
    assert client.post("/api/v1/panic-feedback", json={**fb, "severity": 0}, headers=h).status_code == 422
    assert client.post("/api/v1/panic-reports", json={**REPORT, "duration_minutes": -1}, headers=h).status_code == 422
    assert client.post("/api/v1/sensor-data", json={**SENSOR, "hrv_source": "guess"}, headers=h).status_code == 422
    assert storage.read_all_feedback() == [] and storage.read_all_reports() == []


def test_weights_follow_the_five_slot_contract(client):
    body = client.get("/api/v1/sensor-data", headers=as_user("new-user")).json()
    assert body["source"] == "baseline"
    assert len(body["weights"]) == 5 and all(isinstance(w, float) for w in body["weights"])


def test_api_key_enforced_when_configured(client, monkeypatch):
    monkeypatch.setattr(main, "_API_KEY", "s3cret")
    h = as_user("alice")
    assert client.post("/api/v1/sensor-data", json=SENSOR, headers=h).status_code == 401
    assert client.post("/api/v1/sensor-data", json=SENSOR, headers={**h, "X-API-Key": "wrong"}).status_code == 401
    assert client.post("/api/v1/sensor-data", json=SENSOR, headers={**h, "X-API-Key": "s3cret"}).status_code == 200


def test_profile_is_always_the_callers_own(client):
    client.post("/api/v1/profile", json={"user_id": "bob", "role": "therapist"}, headers=as_user("alice"))
    assert storage.get_profile("bob") is None
    assert storage.get_profile("alice")["role"] == "therapist"


def test_rename_keeps_role(client):
    h = as_user("alice")
    assert client.put("/api/v1/profile/display-name", json={"display_name": "A"}, headers=h).status_code == 404
    client.post("/api/v1/profile", json={"user_id": "alice", "role": "therapist"}, headers=h)
    client.put("/api/v1/profile/display-name", json={"display_name": "  Dr A  "}, headers=h)
    assert storage.get_profile("alice") == {"user_id": "alice", "role": "therapist", "display_name": "Dr A"}


# --- Consent + therapist access --------------------------------------------

def _make_therapist(client, tid):
    client.post("/api/v1/profile", json={"user_id": tid, "role": "therapist"}, headers=as_user(tid))


def _issue_code(client, tid):
    r = client.post("/api/v1/consent-codes", json={"therapist_id": tid}, headers=as_user(tid))
    assert r.status_code == 200, r.text
    return r.json()["code"]


def test_only_therapists_can_issue_codes(client):
    client.post("/api/v1/profile", json={"user_id": "pat", "role": "patient"}, headers=as_user("pat"))
    assert client.post("/api/v1/consent-codes", json={"therapist_id": "pat"}, headers=as_user("pat")).status_code == 403
    assert client.post("/api/v1/consent-codes", json={"therapist_id": "nobody"}, headers=as_user("nobody")).status_code == 403


def test_code_format(client):
    _make_therapist(client, "t1")
    codes = {_issue_code(client, "t1") for _ in range(20)}
    assert len(codes) == 20
    assert all(re.fullmatch(r"[A-HJ-NP-Z2-9]{3}-[A-HJ-NP-Z2-9]{3}", c) for c in codes)


def test_full_consent_flow(client):
    _make_therapist(client, "t1")
    _make_therapist(client, "t2")
    client.post("/api/v1/panic-reports", json=REPORT, headers=as_user("pat"))
    client.post("/api/v1/sensor-data", json=SENSOR, headers=as_user("pat"))

    reports_url = "/api/v1/therapist/t1/patients/pat/reports"
    assert client.get(reports_url, headers=as_user("t1")).status_code == 403  # no link yet

    code = _issue_code(client, "t1")
    r = client.post("/api/v1/consent-codes/redeem", json={"code": code, "patient_id": "x"}, headers=as_user("pat"))
    assert r.status_code == 200 and r.json()["therapist_id"] == "t1"

    # Linked therapist sees the data.
    assert [p["user_id"] for p in client.get("/api/v1/therapist/t1/patients", headers=as_user("t1")).json()["patients"]] == ["pat"]
    reports = client.get(reports_url, headers=as_user("t1")).json()["reports"]
    assert len(reports) == 1 and reports[0]["latitude"] == 32.0
    assert len(client.get("/api/v1/therapist/t1/patients/pat/sensor-data", headers=as_user("t1")).json()["sensor_data"]) == 1
    assert client.get("/api/v1/my-therapists", headers=as_user("pat")).json()["therapists"][0]["therapist_id"] == "t1"

    # Another therapist can neither borrow t1's path nor read via their own.
    assert client.get(reports_url, headers=as_user("t2")).status_code == 403
    assert client.get("/api/v1/therapist/t2/patients/pat/reports", headers=as_user("t2")).status_code == 403

    # A code works exactly once.
    again = client.post("/api/v1/consent-codes/redeem", json={"code": code, "patient_id": "x"}, headers=as_user("eve"))
    assert again.status_code == 409

    # Revocation cuts access, keeps history, and is idempotent.
    assert client.delete("/api/v1/my-therapists/t1", headers=as_user("pat")).json()["removed"] is True
    assert client.delete("/api/v1/my-therapists/t1", headers=as_user("pat")).json()["removed"] is False
    assert client.get(reports_url, headers=as_user("t1")).status_code == 403
    assert len(storage.read_all_reports()) == 1


def test_unknown_and_expired_codes(client):
    redeem = lambda c: client.post("/api/v1/consent-codes/redeem", json={"code": c, "patient_id": "x"}, headers=as_user("pat"))  # noqa: E731
    assert redeem("NOP-E22").status_code == 404
    past = (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()
    storage.create_consent_code({"code": "OLD-AAA", "therapist_id": "t1", "expires_at": past, "used_at": None, "used_by": None})
    assert redeem("OLD-AAA").status_code == 410
    assert storage.list_patients_for_therapist("t1") == []


def test_code_claim_is_compare_and_set():
    future = (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat()
    storage.create_consent_code({"code": "ABC-DEF", "therapist_id": "t1", "expires_at": future, "used_at": None, "used_by": None})
    assert storage.mark_consent_code_used("ABC-DEF", "p1", "now") is True
    assert storage.mark_consent_code_used("ABC-DEF", "p2", "now") is False
    assert storage.find_consent_code("ABC-DEF")["used_by"] == "p1"
    assert storage.mark_consent_code_used("NOPE", "p1", "now") is False
