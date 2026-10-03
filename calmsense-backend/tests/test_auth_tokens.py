"""Supabase token verification, offline: a local EC key stands in for the
project's JWKS, so this exercises the real decode path through the HTTP layer
(audience, expiry, algorithm pinning) without network or credentials."""

from __future__ import annotations

import time

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import ec

import auth

KEY = ec.generate_private_key(ec.SECP256R1())
OTHER_KEY = ec.generate_private_key(ec.SECP256R1())


class _FakeJwks:
    def __init__(self, fail=False):
        self.fail = fail

    def get_signing_key_from_jwt(self, token):
        if self.fail:
            raise ConnectionError("jwks down")
        return jwt.PyJWK.from_dict(jwt.algorithms.ECAlgorithm.to_jwk(KEY.public_key(), as_dict=True) | {"alg": "ES256"})


def _token(key=KEY, alg="ES256", **over):
    claims = {"sub": "user-123", "aud": "authenticated", "exp": int(time.time()) + 600} | over
    return jwt.encode(claims, key, algorithm=alg)


@pytest.fixture
def jwks(monkeypatch):
    fake = _FakeJwks()
    monkeypatch.setattr(auth, "_jwks_client", fake)
    return fake


def _get(raw_client, token):
    return raw_client.get("/api/v1/profile", headers={"Authorization": f"Bearer {token}"})


def test_valid_token_is_accepted(raw_client, jwks):
    assert _get(raw_client, _token()).status_code == 200


@pytest.mark.parametrize("token", [
    _token(aud="anon"),                         # wrong audience
    _token(exp=int(time.time()) - 10),          # expired
    _token(key=OTHER_KEY),                      # not signed by the project
    _token(key="a-shared-secret-at-least-32-bytes!", alg="HS256"),   # algorithm confusion
    _token(sub=""),                             # no subject
    "not.a.jwt",
])
def test_bad_tokens_are_401(raw_client, jwks, token):
    assert _get(raw_client, token).status_code == 401


def test_key_outage_is_503_not_401(raw_client, jwks):
    # A 401 here would make the phone discard a perfectly good session.
    jwks.fail = True
    assert _get(raw_client, _token()).status_code == 503
