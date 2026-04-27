"""
Smoke test for the CalmSense backend.

Run the server first (in another terminal):
    python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload

Then run this script:
    python smoke_test.py

Exits 0 on success, non-zero on any failure.
"""

from __future__ import annotations

import json
import sys
import time
from urllib import request, error

BASE_URL = "http://localhost:8000"
TEST_USER = "smoke-test-user"


def _req(method, path, body=None):
    url = f"{BASE_URL}{path}"
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = request.Request(url, data=data, method=method, headers=headers)
    try:
        with request.urlopen(req, timeout=5) as resp:
            raw = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, raw
    except error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw


def check(label, condition, detail=""):
    mark = "PASS" if condition else "FAIL"
    print(f"  [{mark}] {label}{(' - ' + detail) if detail else ''}")
    if not condition:
        check.failed = True


check.failed = False


def main():
    print(f"Smoke testing {BASE_URL} ...")

    # --- 1. health ---
    print("\n[1] GET /health")
    try:
        status, body = _req("GET", "/health")
    except Exception as e:
        print(f"  [FAIL] could not reach server: {e}")
        print("  Is the server running? Start it with:")
        print("    python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload")
        return 2
    check("status 200", status == 200, f"got {status}")
    check("returns ok", isinstance(body, dict) and body.get("status") == "ok", repr(body))

    # --- 2. POST a normal reading ---
    print("\n[2] POST /api/v1/sensor-data (resting)")
    payload = {
        "user_id": TEST_USER,
        "panic_attack_detection": False,
        "current_hr": 72.0,
        "current_hrv": 45.0,
        "current_motion_intensity": 0.05,
        "timestamp": None,
    }
    status, body = _req("POST", "/api/v1/sensor-data", payload)
    check("status 200", status == 200, f"got {status}")
    check("success=True", isinstance(body, dict) and body.get("success") is True, repr(body))

    # --- 3. POST a panic reading ---
    print("\n[3] POST /api/v1/sensor-data (panic)")
    payload2 = {
        "user_id": TEST_USER,
        "panic_attack_detection": True,
        "current_hr": 132.0,
        "current_hrv": 18.0,
        "current_motion_intensity": 0.02,
        "timestamp": "2026-04-26T08:00:00Z",
    }
    status, body = _req("POST", "/api/v1/sensor-data", payload2)
    check("status 200", status == 200, f"got {status}")
    check("success=True", isinstance(body, dict) and body.get("success") is True, repr(body))

    # --- 4. POST malformed body (should be rejected) ---
    print("\n[4] POST /api/v1/sensor-data (malformed - missing fields)")
    status, body = _req("POST", "/api/v1/sensor-data", {"user_id": "x"})
    check("status 422", status == 422, f"got {status} (expected 422 validation error)")

    # --- 5. GET weights for known user ---
    print("\n[5] GET /api/v1/sensor-data?user_id=...")
    status, body = _req("GET", f"/api/v1/sensor-data?user_id={TEST_USER}")
    check("status 200", status == 200, f"got {status}")
    check(
        "has weights array",
        isinstance(body, dict) and isinstance(body.get("weights"), list),
        repr(body),
    )
    check(
        "user_id echoed",
        isinstance(body, dict) and body.get("user_id") == TEST_USER,
        repr(body),
    )

    # --- 6. GET weights for unknown user ---
    print("\n[6] GET /api/v1/sensor-data?user_id=does-not-exist")
    status, body = _req(
        "GET", "/api/v1/sensor-data?user_id=does-not-exist-" + str(int(time.time()))
    )
    check("status 200", status == 200, f"got {status}")
    # source is 'default' when no model_weights.json is present, and
    # 'trained_global' once ml/train_model.py has been run. Either is fine.
    check(
        "source is default or trained_global",
        isinstance(body, dict) and body.get("source") in ("default", "trained_global"),
        repr(body),
    )
    check(
        "weights is a 5-element array",
        isinstance(body, dict)
        and isinstance(body.get("weights"), list)
        and len(body["weights"]) == 5,
        repr(body),
    )

    # --- 7. GET weights without user_id (should fail) ---
    print("\n[7] GET /api/v1/sensor-data (no user_id)")
    status, body = _req("GET", "/api/v1/sensor-data")
    check(
        "status 422 (validation error)",
        status == 422,
        f"got {status} (expected 422)",
    )

    # --- summary ---
    print()
    if check.failed:
        print("SMOKE TEST: FAILURES detected. See above.")
        return 1
    print("SMOKE TEST: all checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
