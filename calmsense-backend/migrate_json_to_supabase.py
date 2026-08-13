"""One-shot migration of the JSON fallback store into Supabase.

Run this once, on the machine whose data/ directory holds the real data (the
Pi), with the backend STOPPED so the files cannot change underneath it:

    docker compose stop backend
    docker compose run --rm --no-deps backend python migrate_json_to_supabase.py --dry-run
    docker compose run --rm --no-deps backend python migrate_json_to_supabase.py --verify

`docker compose run` is deliberate: it reuses the service's own volume mount
and env_file, so the script sees exactly the same data/ and the same
credentials as the real backend. No docker cp, no second config path.

Requires SUPABASE_URL and SUPABASE_KEY (the service_role key). Do NOT set
CALMSENSE_REQUIRE_SUPABASE while migrating - this script has to read the JSON
files, which that flag deliberately forbids.

On ids
------
Only one id is referenced anywhere: user_model_state.active_weights_id points
at model_weights.id. Everything else that looks like a foreign key is not one
(the phone's snapshot_id is display-only, and admins are looked up by email,
never by id).

So by default this does NOT preserve ids. It lets Postgres assign them and
rewrites active_weights_id through an old->new map. That removes the need to
resync the identity sequences afterwards, which is the single most likely way
to leave the database in a state where the next insert dies on a duplicate
key. --preserve-ids exists for the paranoid path; if you use it you MUST then
run supabase_post_migration.sql by hand, because supabase-py cannot execute
raw SQL.

Re-running
----------
Safe by default: the script refuses to touch a table that already has rows.
--reimport deletes this JSON's user_ids from the target tables first, which is
only correct while the backend is stopped. Once the Pi has been serving from
Supabase, the JSON files are stale and re-importing them would resurrect old
rows and drop newer ones - at that point the answer is a fresh export from
Supabase, not this script.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

import storage

BATCH = 500

# Column whitelists straight from supabase_schema.sql. Anything not listed is
# dropped rather than sent, so a stray key in an old JSON row cannot fail the
# whole batch with a PostgREST schema error.
COMMON_VITALS = ["current_hr", "current_hrv", "current_motion_intensity"]

SENSOR_COLS = ["user_id", "panic_attack_detection", *COMMON_VITALS, "timestamp"]
FEEDBACK_COLS = ["user_id", "was_panic", "severity", "detected_by_model",
                 *COMMON_VITALS, "model_probability", "timestamp"]
REPORT_COLS = ["user_id", "timestamp", "severity", "detected_by_model", "feeling",
               "symptoms", "activity_before", "what_helped", "duration_minutes",
               "latitude", "longitude", "location_accuracy_m", *COMMON_VITALS]
ADMIN_COLS = ["email", "name", "password_hash", "is_active", "created_at"]
WEIGHTS_COLS = ["user_id", "weights", "feature_names", "model_type", "test_accuracy",
                "training_samples", "trained_through", "source", "note", "created_at"]
STATE_COLS = ["user_id", "active_weights_id", "training_cutoff", "updated_at"]


def pick(row: dict, cols: list[str]) -> dict:
    """Whitelist a row, dropping keys the table does not have."""
    return {k: row[k] for k in cols if k in row}


def load(path: str) -> list[dict] | None:
    """Rows, or None when the file is absent (a legitimate state, not an error)."""
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data if isinstance(data, list) else []


def count(client, table: str, col: str) -> int:
    """Exact row count.

    Counts via the count= header rather than by fetching rows: PostgREST caps a
    response at ~1000 rows, so counting by len() would report 1000 for a table
    of 10k and produce a false PASS.
    """
    return client.table(table).select(col, count="exact").limit(1).execute().count or 0


class Report:
    def __init__(self):
        self.rows: list[tuple] = []
        self.problems: list[str] = []

    def add(self, table, present, in_json, before, inserted, after, status):
        self.rows.append((table, present, in_json, before, inserted, after, status))

    def problem(self, msg):
        self.problems.append(msg)

    def render(self) -> bool:
        print()
        print(f"{'table':<18}{'file':<7}{'json':>7}{'before':>8}{'ins':>8}{'after':>8}  status")
        print("-" * 72)
        for t, present, j, b, i, a, s in self.rows:
            print(f"{t:<18}{('yes' if present else 'no'):<7}{j:>7}{b:>8}{i:>8}{a:>8}  {s}")
        if self.problems:
            print("\nPROBLEMS:")
            for p in self.problems:
                print(f"  ! {p}")
        return not self.problems


def migrate(client, data_dir: str, dry: bool, preserve_ids: bool,
            reimport: bool, rep: Report) -> dict[int, int]:
    """Returns the model_weights old->new id map (empty when preserving ids)."""

    def path(name):
        return os.path.join(data_dir, f"{name}.json")

    def guard(table, col, rows_in_json) -> int | None:
        """Return the pre-existing row count, or None if we must not proceed."""
        before = count(client, table, col)
        if before and not reimport:
            rep.problem(f"{table} already has {before} rows; refusing to double-insert. "
                        f"Use --reimport (backend must be stopped) or clear it first.")
            return None
        return before

    def wipe(table, col, values):
        if not (reimport and values) or dry:
            return
        client.table(table).delete().in_(col, list(values)).execute()

    # --- admin_users ---------------------------------------------------------
    admins = load(path("admin_users"))
    if admins is None:
        rep.add("admin_users", False, 0, 0, 0, count(client, "admin_users", "id"),
                "SKIPPED (no file)")
    else:
        before = count(client, "admin_users", "id")
        inserted = 0
        # Matched on email (unique), so this one is naturally idempotent and
        # needs no --reimport special case.
        for r in admins:
            email = (r.get("email") or "").strip().lower()
            if not email:
                rep.problem("admin_users row with no email; skipped")
                continue
            existing = client.table("admin_users").select("id").eq("email", email) \
                             .limit(1).execute().data if not dry else []
            if existing:
                continue
            row = pick(r, ADMIN_COLS)
            row["email"] = email
            if preserve_ids and "id" in r:
                row["id"] = r["id"]
            if not dry:
                client.table("admin_users").insert(row).execute()
            inserted += 1
        after = before + inserted if dry else count(client, "admin_users", "id")
        rep.add("admin_users", True, len(admins), before, inserted, after, "OK")

    # --- model_weights (build the id map) ------------------------------------
    id_map: dict[int, int] = {}
    weights = load(path("model_weights"))
    if weights is None:
        rep.add("model_weights", False, 0, 0, 0, count(client, "model_weights", "id"),
                "SKIPPED (no file)")
    else:
        wipe("model_weights", "user_id", {r.get("user_id") for r in weights if r.get("user_id")})
        before = guard("model_weights", "id", weights)
        if before is None:
            return id_map
        inserted = 0
        # Ascending by old id so new ids come out in the same relative order,
        # keeping "newest snapshot" intuitive. Inserted one at a time because we
        # need each assigned id back, and the order of a bulk insert response is
        # a PostgREST implementation detail worth not betting on.
        for r in sorted(weights, key=lambda x: x.get("id") or 0):
            row = pick(r, WEIGHTS_COLS)
            old_id = r.get("id")
            if preserve_ids and old_id is not None:
                row["id"] = old_id
            if dry:
                id_map[old_id] = old_id
            else:
                resp = client.table("model_weights").insert(row).execute()
                new_id = (resp.data or [{}])[0].get("id")
                if new_id is None:
                    rep.problem(f"model_weights old id {old_id}: no id returned")
                    continue
                id_map[old_id] = new_id
            inserted += 1
        after = before + inserted if dry else count(client, "model_weights", "id")
        rep.add("model_weights", True, len(weights), before, inserted, after, "OK")

    # --- user_model_state (depends on the map above) -------------------------
    states = load(path("user_model_state"))
    if states is None:
        rep.add("user_model_state", False, 0, 0, 0,
                count(client, "user_model_state", "user_id"), "SKIPPED (no file)")
    else:
        before = count(client, "user_model_state", "user_id")
        inserted = 0
        for r in states:
            row = pick(r, STATE_COLS)
            old_ref = r.get("active_weights_id")
            if old_ref is not None:
                if old_ref in id_map:
                    row["active_weights_id"] = id_map[old_ref]
                else:
                    # Null rather than a failed FK: that user falls back to the
                    # baseline model, which is recoverable. A half-applied
                    # migration is not.
                    rep.problem(f"user_model_state[{r.get('user_id')}]: "
                                f"active_weights_id {old_ref} has no matching snapshot; "
                                f"set to null (user falls back to baseline)")
                    row["active_weights_id"] = None
            if not dry:
                client.table("user_model_state").upsert(row, on_conflict="user_id").execute()
            inserted += 1
        after = before + inserted if dry else count(client, "user_model_state", "user_id")
        rep.add("user_model_state", True, len(states), before, inserted, after, "OK")

    # --- the three append-only tables ----------------------------------------
    for name, cols in (("sensor_data", SENSOR_COLS),
                       ("panic_feedback", FEEDBACK_COLS),
                       ("panic_reports", REPORT_COLS)):
        rows = load(path(name))
        if rows is None:
            rep.add(name, False, 0, 0, 0, count(client, name, "id"), "SKIPPED (no file)")
            continue
        wipe(name, "user_id", {r.get("user_id") for r in rows if r.get("user_id")})
        before = guard(name, "id", rows)
        if before is None:
            continue
        # id/created_at are `generated always as identity` / defaulted, so they
        # are never sent. The event time lives in `timestamp`, which is what the
        # app and the admin sorts actually read.
        payload = [pick(r, cols) for r in rows]
        if not dry:
            for i in range(0, len(payload), BATCH):
                chunk = payload[i:i + BATCH]
                client.table(name).insert(chunk).execute()
                print(f"  {name}: {min(i + BATCH, len(payload))}/{len(payload)}")
        after = before + len(payload) if dry else count(client, name, "id")
        rep.add(name, True, len(rows), before, len(payload), after,
                "OK" if after == before + len(payload) else "COUNT MISMATCH")

    return id_map


def verify(client, data_dir: str, id_map: dict[int, int], rep: Report) -> None:
    """Post-migration checks that would catch a silently broken migration."""
    print("\n--- verification ---")

    # Per-user counts, so a partial insert shows up as a skew rather than a
    # total that happens to look plausible.
    for name in ("sensor_data", "panic_feedback", "panic_reports"):
        p = os.path.join(data_dir, f"{name}.json")
        rows = load(p)
        if not rows:
            continue
        per_user: dict[str, int] = {}
        for r in rows:
            per_user[r.get("user_id")] = per_user.get(r.get("user_id"), 0) + 1
        for uid, n in sorted(per_user.items()):
            got = client.table(name).select("id", count="exact").eq("user_id", uid) \
                        .limit(1).execute().count or 0
            mark = "OK" if got == n else "MISMATCH"
            if got != n:
                rep.problem(f"{name}[{uid}]: json {n} != supabase {got}")
            print(f"  {name:<16} {str(uid)[:38]:<40} json={n:<6} db={got:<6} {mark}")

    # Every active_weights_id must resolve, or the user silently loses their model.
    states = client.table("user_model_state").select("*").execute().data or []
    for s in states:
        ref = s.get("active_weights_id")
        if ref is None:
            print(f"  FK  {s.get('user_id')}: active_weights_id is null (baseline)")
            continue
        hit = client.table("model_weights").select("id").eq("id", ref).limit(1).execute().data
        if hit:
            print(f"  FK  {s.get('user_id')}: -> model_weights.id {ref}  OK")
        else:
            rep.problem(f"user_model_state[{s.get('user_id')}] points at "
                        f"missing model_weights.id {ref}")

    # The identity probe. This is the check that catches a sequence left behind
    # by --preserve-ids, which would otherwise only surface as a duplicate-key
    # 500 the first time someone retrains.
    try:
        probe = client.table("model_weights").insert({
            "user_id": "__migration_probe__", "weights": [0],
            "source": "probe", "note": "migration sanity check - safe to delete",
        }).execute()
        new_id = (probe.data or [{}])[0].get("id")
        max_id = max((v for v in id_map.values()), default=0)
        client.table("model_weights").delete().eq("user_id", "__migration_probe__").execute()
        if new_id and new_id > max_id:
            print(f"  identity probe: next id {new_id} > max migrated {max_id}  OK")
        else:
            rep.problem(f"identity sequence is behind: next id {new_id} <= "
                        f"max migrated {max_id}. Run supabase_post_migration.sql.")
    except Exception as e:
        rep.problem(f"identity probe failed: {e}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true",
                    help="read and validate everything, write nothing")
    ap.add_argument("--verify", action="store_true",
                    help="run post-migration checks after writing")
    ap.add_argument("--preserve-ids", action="store_true",
                    help="insert original ids (then run supabase_post_migration.sql)")
    ap.add_argument("--reimport", action="store_true",
                    help="delete this JSON's user_ids from the target tables first; "
                         "ONLY correct while the backend is stopped")
    ap.add_argument("--data-dir", default=storage.DATA_DIR)
    args = ap.parse_args()

    client = storage._supabase
    if client is None:
        print("ERROR: no Supabase client. Set SUPABASE_URL and SUPABASE_KEY "
              "(service_role) and make sure the `supabase` package is installed.",
              file=sys.stderr)
        print(f"       storage reports: {storage.storage_error()}", file=sys.stderr)
        return 2
    if storage._REQUIRE_SUPABASE:
        print("ERROR: CALMSENSE_REQUIRE_SUPABASE is set, which blocks reading the "
              "JSON files this script must migrate. Unset it for the migration.",
              file=sys.stderr)
        return 2

    print(f"source : {args.data_dir}")
    print(f"target : {os.environ.get('SUPABASE_URL')}")
    print(f"mode   : {'DRY RUN (no writes)' if args.dry_run else 'LIVE'}"
          f"{' +preserve-ids' if args.preserve_ids else ''}"
          f"{' +reimport' if args.reimport else ''}")

    if args.reimport and not args.dry_run:
        print("\n--reimport will DELETE existing rows for the user_ids found in the "
              "JSON files.\nThis is only correct if the backend is stopped.")
        if input("Type 'yes' to continue: ").strip().lower() != "yes":
            print("aborted")
            return 1

    rep = Report()
    id_map = migrate(client, args.data_dir, args.dry_run, args.preserve_ids,
                     args.reimport, rep)

    if args.verify and not args.dry_run:
        verify(client, args.data_dir, id_map, rep)

    ok = rep.render()
    if args.dry_run:
        print("\nDry run only - nothing was written.")
    print("\nRESULT:", "OK" if ok else "PROBLEMS FOUND")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
