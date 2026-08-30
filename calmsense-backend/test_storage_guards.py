"""
Guards for the two storage invariants a cloud deployment depends on.

    python test_storage_guards.py

Exits 0 on success, non-zero on any failure. Same plain-script style as
test_auto_retrain.py - no pytest, no network, no credentials.

Why these two specifically. Both failure modes are SILENT: they produce
plausible-looking wrong answers rather than an error, so nothing catches them
until someone notices missing data much later.

  1. Pagination. PostgREST caps a response (Supabase default 1000 rows). An
     unpaged .select("*") on a 40k-row table returns 1000 and reports success.
     Every multi-row read must go through storage._select_all.

  2. Require-mode. With CALMSENSE_REQUIRE_SUPABASE set the JSON fallback is not
     a valid destination: the filesystem is ephemeral, so a "successful" write
     is discarded at the next cold start and a "successful" read serves an
     empty database. Failures must raise, never degrade.

If you add a storage function that reads more than one row, add it to
MULTI_ROW_READERS below or this test will not cover it.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import storage  # noqa: E402

# Every storage function that can return more than one row.
MULTI_ROW_READERS = [
    "read_all_records", "read_all_feedback", "read_all_reports",
    "list_admins", "list_model_snapshots",
    "list_patients_for_therapist", "get_reports_for_patient",
    "get_sensor_data_for_patient",
]

# Exempt from paging only because they look up a primary key (at most one row).
SINGLE_ROW_READERS = [
    "get_profile", "find_consent_code", "get_admin_by_email",
    "get_model_snapshot", "get_user_model_state",
]

_failed = 0


def check(label, got, expected):
    global _failed
    ok = got == expected
    if not ok:
        _failed += 1
    print(f"  [{'PASS' if ok else 'FAIL'}] {label:<50} expected={expected!r} got={got!r}")


# --------------------------------------------------------------------------
# 1. Pagination
# --------------------------------------------------------------------------

class FakeTable:
    """Mimics PostgREST: never returns more than `cap` rows per request."""

    def __init__(self, total, cap=1000):
        self.rows = [{"id": i, "user_id": "u"} for i in range(1, total + 1)]
        self.cap = cap
        self.requests = 0
        self._lo, self._hi = 0, None

    def select(self, *_a, **_k):
        return self

    def eq(self, *_a, **_k):
        return self

    def order(self, *_a, **_k):
        return self

    def range(self, lo, hi):
        self._lo, self._hi = lo, hi
        return self

    def execute(self):
        self.requests += 1
        window = self.rows[self._lo:] if self._hi is None else self.rows[self._lo:self._hi + 1]
        return type("R", (), {"data": window[:self.cap]})()


class FakeClient:
    def __init__(self, table):
        self._t = table

    def table(self, *_a, **_k):
        return self._t


def test_pagination():
    print("\n[1] _select_all pages past the PostgREST row cap")
    original = storage._supabase
    try:
        # 40576 is the real row count migrated off the Pi on 2026-08-30.
        for total, expected_requests in [(0, 1), (1, 1), (999, 1), (1000, 2),
                                         (1001, 2), (40576, 41)]:
            t = FakeTable(total)
            storage._supabase = FakeClient(t)
            rows = storage._select_all("sensor_data")
            check(f"{total} rows returned in full", len(rows), total)
            check(f"{total} rows took {expected_requests} request(s)", t.requests, expected_requests)
            if rows:
                check(f"{total} rows have no gaps or duplicates",
                      (rows[0]["id"], rows[-1]["id"]), (1, total))

        # What the bug looked like, so the guard itself stays meaningful.
        t = FakeTable(40576)
        storage._supabase = FakeClient(t)
        unpaged = t.select("*").order("id").execute().data
        check("an unpaged select would truncate", len(unpaged), 1000)
    finally:
        storage._supabase = original


def test_readers_are_paged():
    """Source check: catch a new unpaged reader before it reaches production."""
    print("\n[2] every multi-row reader goes through _select_all")
    src = open(os.path.join(HERE, "storage.py"), encoding="utf-8").read()
    bodies = dict(re.findall(r"\ndef (\w+)\([^)]*\)[^:]*:(.*?)(?=\ndef |\Z)", src, flags=re.S))

    for name in MULTI_ROW_READERS:
        check(f"{name} is paged", "_select_all" in bodies.get(name, ""), True)
    for name in SINGLE_ROW_READERS:
        body = bodies.get(name, "")
        check(f"{name} is a single-row lookup",
              ".limit(1)" in body or "data[0]" in body, True)


# --------------------------------------------------------------------------
# 2. Require-mode
# --------------------------------------------------------------------------

def run(code, env_extra, stub_dir=None):
    env = dict(os.environ)
    for k in ("SUPABASE_URL", "SUPABASE_KEY", "CALMSENSE_REQUIRE_SUPABASE",
              "ADMIN_JWT_SECRET"):
        env.pop(k, None)
    env["PYTHONPATH"] = (stub_dir + os.pathsep + HERE) if stub_dir else HERE
    env.update(env_extra)
    return subprocess.run([sys.executable, "-c", code], cwd=HERE, env=env,
                          capture_output=True, text=True, timeout=120)


def make_stub():
    """A stub `supabase` package so require-mode can get past import without
    credentials. Its client raises on execute(), simulating a transport fault."""
    d = tempfile.mkdtemp()
    lines = [
        "class _Q:",
        "    def __getattr__(self, n): return lambda *a, **k: self",
        "    def execute(self): raise RuntimeError('stub transport failure')",
        "class _C:",
        "    def table(self, *a, **k): return _Q()",
        "def create_client(url, key): return _C()",
    ]
    with open(os.path.join(d, "supabase.py"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return d


def test_require_mode():
    print("\n[3] CALMSENSE_REQUIRE_SUPABASE fails loudly instead of degrading")
    stub = make_stub()
    creds = {"SUPABASE_URL": "https://stub.supabase.co", "SUPABASE_KEY": "stub"}
    req = {"CALMSENSE_REQUIRE_SUPABASE": "1", "ADMIN_JWT_SECRET": "x"}
    full = dict(req, **creds)

    p = run("import storage", dict(req), None)
    check("no credentials -> refuses to start", "refusing to start" in p.stderr, True)

    p = run("import auth", dict(creds, CALMSENSE_REQUIRE_SUPABASE="1"), stub)
    check("no ADMIN_JWT_SECRET -> refuses to start", "ADMIN_JWT_SECRET" in p.stderr, True)

    p = run("import storage; storage.read_all_records()", full, stub)
    check("failed read raises, not silent []", "refusing to serve" in p.stderr, True)

    p = run("import storage; storage.read_all_feedback()", full, stub)
    check("failed feedback read raises", "refusing to serve" in p.stderr, True)

    p = run("import storage; storage.get_sensor_data_for_patient('u')", full, stub)
    check("failed therapist read raises", "refusing to serve" in p.stderr, True)

    p = run("import storage; storage._json_read_from('x.json')", full, stub)
    check("JSON read refused outright", "fell through" in p.stderr, True)

    p = run("import storage; storage._json_append_to('x.json', {})", full, stub)
    check("JSON write refused outright", "fell through" in p.stderr, True)

    p = run("import main; assert main.app.docs_url is None; print('ok')", full, stub)
    check("API docs disabled", p.returncode, 0)


def test_dev_mode_unaffected():
    print("\n[4] without the flag, the JSON fallback works exactly as before")
    p = run("import storage; assert storage.storage_backend() == 'json'; print('ok')", {})
    check("imports and reports json", p.returncode, 0)
    p = run("import storage; assert storage._json_read_from('nope.json') == []; print('ok')", {})
    check("missing JSON file reads as empty", p.returncode, 0)
    p = run("import storage; storage._read_fallback_or_raise('read', Exception('x')); print('ok')", {})
    check("read fallback only warns", p.returncode, 0)
    p = run("import main; assert main.app.docs_url == '/docs'; print('ok')", {})
    check("API docs enabled", p.returncode, 0)


def main():
    print("STORAGE GUARD TEST")
    test_pagination()
    test_readers_are_paged()
    test_require_mode()
    test_dev_mode_unaffected()
    print()
    if _failed:
        print(f"STORAGE GUARD TEST: {_failed} check(s) FAILED.")
        return 1
    print("STORAGE GUARD TEST: all checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
