"""
Checks that Supabase token verification actually rejects what it should.

    python test_supabase_auth.py

Exits 0 on success. Needs network (it fetches the project's JWKS) and
SUPABASE_URL; skips cleanly with a message if either is unavailable.

Why this exists. Before stage 5 the therapist endpoints took the therapist's
id from the URL path and trusted it, so anyone who knew an id could read that
therapist's patients' reports and GPS. The identity now comes from a verified
token instead. That is only true while these checks pass - a verifier that
accepts a forged token is worse than no verifier, because it looks secure.

The forged-token cases are the important ones: they use a key we generated
ourselves, so they prove the signature is genuinely being checked against
Supabase's published key rather than merely parsed.
"""

from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

_failed = 0
_skipped = False


def check(label, got, expected):
    global _failed
    ok = got == expected
    if not ok:
        _failed += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {label:<52} expected={expected!r} got={got!r}")


def outcome(token: str) -> str:
    """Run a token through the dependency and describe what happened."""
    import jwt
    from fastapi import HTTPException
    from fastapi.security import HTTPAuthorizationCredentials
    import auth

    try:
        auth.get_supabase_claims(
            HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)
        )
        return "accepted"
    except HTTPException as e:
        return f"rejected-{e.status_code}"
    except jwt.PyJWTError:
        return "rejected-jwt"


def main() -> int:
    global _skipped
    print("SUPABASE TOKEN VERIFICATION TEST")

    try:
        import jwt  # noqa: F401
        from cryptography.hazmat.primitives.asymmetric import ec  # noqa: F401
    except ImportError as e:
        print(f"\n  SKIPPED: {e}. Install requirements.txt (cryptography is needed")
        print("           for the ES256 tokens this project issues).")
        _skipped = True
        return 0

    import auth
    if not auth.SUPABASE_URL:
        print("\n  SKIPPED: SUPABASE_URL not set.")
        _skipped = True
        return 0

    print(f"\n[1] the project's key set  ({auth.SUPABASE_URL})")
    try:
        keys = auth._jwks().get_jwk_set().keys
        for k in keys:
            print(f"  key: kid={k.key_id} alg={getattr(k, 'algorithm_name', '?')}")
        check("at least one signing key published", len(keys) >= 1, True)
    except Exception as e:
        print(f"\n  SKIPPED: could not reach the JWKS endpoint ({e}).")
        _skipped = True
        return 0

    print("\n[2] malformed and missing tokens are refused")
    check("empty token", outcome(""), "rejected-401")
    check("not a jwt", outcome("nonsense"), "rejected-401")
    check("three dots but junk", outcome("aaa.bbb.ccc"), "rejected-401")

    print("\n[3] a token we signed ourselves is refused")
    # This is the real test: a structurally perfect, unexpired ES256 token with
    # the right issuer, audience and even the right kid - signed with OUR key.
    # Accepting it would mean anyone can mint any identity.
    import jwt
    from cryptography.hazmat.primitives.asymmetric import ec

    our_key = ec.generate_private_key(ec.SECP256R1())
    real_kid = auth._jwks().get_jwk_set().keys[0].key_id
    now = int(time.time())
    forged = jwt.encode(
        {
            "sub": "attacker-chosen-user-id",
            "aud": "authenticated",
            "iss": f"{auth.SUPABASE_URL}/auth/v1",
            "iat": now,
            "exp": now + 3600,
            "role": "authenticated",
        },
        our_key,
        algorithm="ES256",
        headers={"kid": real_kid},
    )
    check("forged token with a real kid", outcome(forged), "rejected-401")

    forged_no_kid = jwt.encode(
        {"sub": "x", "aud": "authenticated", "iat": now, "exp": now + 3600},
        our_key, algorithm="ES256",
    )
    check("forged token with no kid", outcome(forged_no_kid), "rejected-401")

    print("\n[4] the 'alg=none' downgrade is refused")
    # Classic JWT attack: strip the signature and claim no algorithm.
    unsigned = jwt.encode(
        {"sub": "x", "aud": "authenticated", "iat": now, "exp": now + 3600},
        key="", algorithm="none",
    )
    check("unsigned token", outcome(unsigned), "rejected-401")

    print("\n[5] an HS256 token signed with the kid is refused")
    # Guards the earlier mistake: SUPABASE_JWT_SECRET held the key id, which is
    # public. If anything still verified HS256 against it, that would be a
    # trivially forgeable identity.
    hs = jwt.encode(
        {"sub": "x", "aud": "authenticated", "iat": now, "exp": now + 3600},
        key=real_kid, algorithm="HS256",
    )
    check("HS256 signed with the public kid", outcome(hs), "rejected-401")

    print("\n[6] a real token, if one is supplied")
    real = os.environ.get("CALMSENSE_TEST_TOKEN", "").strip()
    if not real:
        print("  (set CALMSENSE_TEST_TOKEN to a live access token to check acceptance)")
    else:
        res = outcome(real)
        check("real token accepted", res, "accepted")
        if res == "accepted":
            claims = auth.verify_supabase_token(real)
            print(f"  sub={claims.get('sub')}  aud={claims.get('aud')}  role={claims.get('role')}")

    print()
    if _failed:
        print(f"SUPABASE TOKEN VERIFICATION TEST: {_failed} check(s) FAILED.")
        return 1
    print("SUPABASE TOKEN VERIFICATION TEST: all checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
