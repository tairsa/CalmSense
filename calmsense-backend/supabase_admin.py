"""Supabase Auth Admin API client - listing accounts and setting their role.

Separate from storage.py on purpose: that module talks to the *database*
(sensor rows, reports, feedback), while this one talks to Supabase's **auth**
service, which is a different API with different semantics. It is also the
only place in the backend that needs the service_role key for auth writes.

Plain urllib rather than the `supabase` package: the package is an optional
dependency here (storage.py already degrades to a JSON fallback when it is
missing), and role management should not become the one feature that forces
it to be installed.

Requires SUPABASE_URL and SUPABASE_KEY (the **service_role** key - the anon
key cannot read or write other users). Both already documented in .env.example.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

# Roles the dashboard is allowed to assign. Kept in sync with the Android
# UserRole enum; anything else is rejected rather than written through.
VALID_ROLES = ("user", "therapist", "developer")

_TIMEOUT_S = 10


class SupabaseAdminError(RuntimeError):
    """Raised when the Admin API is unreachable, unconfigured or refuses."""


def _config() -> tuple[str, str]:
    url = (os.environ.get("SUPABASE_URL") or "").rstrip("/")
    key = os.environ.get("SUPABASE_KEY") or ""
    if not url or not key:
        raise SupabaseAdminError(
            "SUPABASE_URL / SUPABASE_KEY are not set; account management is "
            "unavailable. Set them in calmsense-backend/.env."
        )
    return url, key


def _request(method: str, path: str, body: dict | None = None) -> dict:
    url, key = _config()
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        f"{url}{path}",
        data=data,
        method=method,
        headers={
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=_TIMEOUT_S) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:400]
        if e.code in (401, 403):
            raise SupabaseAdminError(
                "Supabase rejected the key. Account management needs the "
                "service_role key in SUPABASE_KEY, not the anon key."
            ) from e
        raise SupabaseAdminError(f"Supabase admin API {e.code}: {detail}") from e
    except urllib.error.URLError as e:
        raise SupabaseAdminError(f"Could not reach Supabase: {e.reason}") from e

    return json.loads(raw) if raw else {}


def _public(user: dict) -> dict:
    """Trim a GoTrue user down to what the dashboard needs.

    Deliberately omits tokens, phone, and the raw identities array - the
    dashboard has no use for them and they should not leave the backend.
    """
    meta = user.get("user_metadata") or {}
    role = meta.get("role")
    return {
        "user_id": user.get("id"),
        "email": user.get("email"),
        "role": role if role in VALID_ROLES else "user",
        # True when no role was ever set, so the UI can distinguish "defaulted
        # to user" from "explicitly set to user".
        "role_unset": role not in VALID_ROLES,
        "created_at": user.get("created_at"),
        "last_sign_in_at": user.get("last_sign_in_at"),
        "email_confirmed_at": user.get("email_confirmed_at"),
    }


def list_accounts(per_page: int = 200) -> list[dict]:
    """Every Supabase auth account, newest first.

    Note this is the *account* list, which is not the same as the user list on
    the Users page - that one is derived from data rows and still contains
    pre-auth ids such as 'tairsa-dev'. Only accounts here can hold a role.
    """
    payload = _request("GET", f"/auth/v1/admin/users?page=1&per_page={per_page}")
    users = payload.get("users", payload if isinstance(payload, list) else [])
    accounts = [_public(u) for u in users]
    accounts.sort(key=lambda a: a.get("created_at") or "", reverse=True)
    return accounts


def set_role(user_id: str, role: str) -> dict:
    """Set one account's user_metadata.role, preserving its other metadata.

    Reads the account first and merges, because the Admin API overwrites the
    keys it is given - sending only {"role": ...} would be fine today but
    would silently drop any metadata added later.
    """
    if role not in VALID_ROLES:
        raise SupabaseAdminError(
            f"Unknown role {role!r}; expected one of {', '.join(VALID_ROLES)}."
        )

    current = _request("GET", f"/auth/v1/admin/users/{user_id}")
    meta = dict(current.get("user_metadata") or {})
    meta["role"] = role

    updated = _request(
        "PUT",
        f"/auth/v1/admin/users/{user_id}",
        {"user_metadata": meta},
    )
    return _public(updated)
