"""Shared fixtures: every test gets an empty JSON store in a temp dir and a
client whose caller identity comes from an X-Test-User header.

Run from calmsense-backend/:  python -m pytest tests
"""

from __future__ import annotations

import os
import sys

# Before any app import: no network, no retrain loop, no real credentials.
os.environ["CALMSENSE_AUTO_RETRAIN_MINUTES"] = "0"
for _k in ("SUPABASE_URL", "SUPABASE_KEY", "CALMSENSE_REQUIRE_SUPABASE", "CALMSENSE_API_KEY"):
    os.environ.pop(_k, None)

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest  # noqa: E402
from fastapi import Header  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import storage  # noqa: E402
from auth import current_user_id  # noqa: E402
from main import app  # noqa: E402


@pytest.fixture(autouse=True)
def isolated_storage(tmp_path, monkeypatch):
    monkeypatch.setattr(storage, "_supabase", None)
    monkeypatch.setattr(storage, "DATA_DIR", str(tmp_path))
    for name in dir(storage):
        if name.endswith("_FILE"):
            monkeypatch.setattr(storage, name, str(tmp_path / os.path.basename(getattr(storage, name))))


def _header_user(x_test_user: str = Header(...)) -> str:
    return x_test_user


@pytest.fixture
def client():
    """Auth replaced by the X-Test-User header. Token verification itself is
    covered separately in test_auth_tokens.py."""
    app.dependency_overrides[current_user_id] = _header_user
    yield TestClient(app)
    app.dependency_overrides.clear()


@pytest.fixture
def raw_client():
    """The real auth stack, for tests that the guards actually guard. (When a
    test also asks for `client`, phone-user auth is overridden for both; admin
    auth never is.)"""
    return TestClient(app)


def as_user(uid: str) -> dict:
    return {"X-Test-User": uid}
