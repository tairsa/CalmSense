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


