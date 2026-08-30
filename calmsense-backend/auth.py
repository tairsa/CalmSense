"""Admin authentication: password hashing (stdlib) + JWT (PyJWT).

Password hashing uses hashlib.pbkdf2_hmac so there is no native dependency to
build on Windows. Tokens are signed with ADMIN_JWT_SECRET (set it in .env for
anything beyond local dev; a random per-process secret is used if unset, which
invalidates tokens on restart).
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
from datetime import datetime, timedelta, timezone

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

import storage

_PBKDF2_ALGO = "sha256"
_PBKDF2_ITERATIONS = 240_000

JWT_ALGORITHM = "HS256"
JWT_EXPIRE_HOURS = int(os.environ.get("ADMIN_JWT_EXPIRE_HOURS", "12"))

# A stable secret keeps tokens valid across restarts; fall back to a random one
# so the app still runs locally without configuration (tokens reset on restart).
#
# That fallback is fine on a laptop and wrong anywhere the process restarts on
# its own. On a scale-to-zero container every cold start would mint a new
# secret, so admin sessions would die at random and any second instance would
# reject the first one's tokens. Where Supabase is mandatory, so is this.
_REQUIRE_SUPABASE = os.environ.get("CALMSENSE_REQUIRE_SUPABASE", "").strip().lower() in ("1", "true", "yes")

if _REQUIRE_SUPABASE and not os.environ.get("ADMIN_JWT_SECRET"):
    raise RuntimeError(
        "CALMSENSE_REQUIRE_SUPABASE is set but ADMIN_JWT_SECRET is missing. "
        "A per-process random secret would invalidate every admin session on "
        "each cold start; refusing to start."
    )

JWT_SECRET = os.environ.get("ADMIN_JWT_SECRET") or secrets.token_urlsafe(48)

_bearer = HTTPBearer(auto_error=False)


# --- Passwords -------------------------------------------------------------

def hash_password(password: str) -> str:
    """Return a 'pbkdf2$<iters>$<salt_hex>$<hash_hex>' string."""
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(_PBKDF2_ALGO, password.encode("utf-8"), salt, _PBKDF2_ITERATIONS)
    return f"pbkdf2${_PBKDF2_ITERATIONS}${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        scheme, iterations, salt_hex, hash_hex = stored.split("$")
        if scheme != "pbkdf2":
            return False
        digest = hashlib.pbkdf2_hmac(
            _PBKDF2_ALGO, password.encode("utf-8"), bytes.fromhex(salt_hex), int(iterations)
        )
        return hmac.compare_digest(digest.hex(), hash_hex)
    except (ValueError, AttributeError):
        return False


# --- Tokens ----------------------------------------------------------------

def create_access_token(admin: dict) -> str:
    now = datetime.now(timezone.utc)
    payload = {
        "sub": str(admin["id"]),
        "email": admin["email"],
        "name": admin.get("name"),
        "iat": now,
        "exp": now + timedelta(hours=JWT_EXPIRE_HOURS),
    }
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def get_current_admin(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> dict:
    """FastAPI dependency: validate the bearer token, return the admin claims."""
    if creds is None or not creds.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        payload = jwt.decode(creds.credentials, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Token expired")
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail="Invalid token")

    # Confirm the admin still exists / is active.
    admin = storage.get_admin_by_email(payload.get("email", ""))
    if admin is None or not admin.get("is_active", True):
        raise HTTPException(status_code=401, detail="Admin no longer active")
    return {"id": admin["id"], "email": admin["email"], "name": admin.get("name")}


# ---------------------------------------------------------------------------
# Supabase user tokens (the phone app)
#
# Separate from the admin JWTs above: those are minted here and signed with a
# shared secret, whereas Supabase signs user tokens with its own key.
#
# This project signs with ES256 and publishes the public key at the JWKS
# endpoint below, so verification needs that key, not a secret. There is no
# shared secret to configure and none to leak. (An earlier plan assumed HS256
# and a SUPABASE_JWT_SECRET; that could never have worked here. If you have a
# value in that variable it is almost certainly the key id, which is public.)
# ---------------------------------------------------------------------------

SUPABASE_URL = (os.environ.get("SUPABASE_URL") or "").rstrip("/")

# PyJWT caches fetched keys, so this is one HTTP call per key rotation rather
# than per request. Created lazily so importing this module never needs the
# network.
_jwks_client = None


class SupabaseAuthUnavailable(RuntimeError):
    """Raised when tokens cannot be verified at all (config or network)."""


def _jwks() -> "jwt.PyJWKClient":
    global _jwks_client
    if _jwks_client is None:
        if not SUPABASE_URL:
            raise SupabaseAuthUnavailable(
                "SUPABASE_URL is not set, so Supabase tokens cannot be verified."
            )
        _jwks_client = jwt.PyJWKClient(
            f"{SUPABASE_URL}/auth/v1/.well-known/jwks.json",
            cache_keys=True,
        )
    return _jwks_client


def verify_supabase_token(token: str) -> dict:
    """Return the verified claims of a Supabase access token.

    Raises jwt.PyJWTError when the token is bad, or SupabaseAuthUnavailable
    when we cannot reach the keys - the two are different failures and the
    caller maps them to 401 and 503 respectively. Treating an outage as "token
    invalid" would silently sign every user out.
    """
    try:
        signing_key = _jwks().get_signing_key_from_jwt(token)
    except SupabaseAuthUnavailable:
        raise
    except jwt.PyJWTError:
        # A malformed token, or a kid that is not in the key set.
        raise
    except Exception as e:
        raise SupabaseAuthUnavailable(f"could not fetch Supabase signing keys: {e}") from e

    return jwt.decode(
        token,
        signing_key.key,
        # ES256 is what this project uses; RS256 is accepted so a future key
        # rotation to RSA does not lock every client out.
        algorithms=["ES256", "RS256"],
        audience="authenticated",
    )


def get_supabase_claims(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> dict:
    """FastAPI dependency: verified claims from the caller's Supabase token."""
    if creds is None or not creds.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        claims = verify_supabase_token(creds.credentials)
    except SupabaseAuthUnavailable as e:
        # Our problem, not the caller's. 503 so clients retry rather than
        # discarding a session that is actually fine.
        raise HTTPException(status_code=503, detail=str(e))
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="Token expired")
    except jwt.PyJWTError as e:
        raise HTTPException(status_code=401, detail=f"Invalid token: {e}")

    if not claims.get("sub"):
        raise HTTPException(status_code=401, detail="Token has no subject")
    return claims


def current_user_id(claims: dict = Depends(get_supabase_claims)) -> str:
    """The authenticated user's id, taken from the verified token.

    Endpoints must use this instead of a user_id from the request body, query
    string or path - otherwise any caller can read or write anyone's data by
    changing a string.
    """
    return claims["sub"]
