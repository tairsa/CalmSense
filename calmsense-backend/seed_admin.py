"""Create the first admin account for the admin web app.

Usage:
    python seed_admin.py --email you@example.com --name "Alex" --password secret123
    python seed_admin.py --email you@example.com           # prompts for password

Safe to run repeatedly: if the email already exists it just reports and exits.
Writes to Supabase when configured, otherwise to data/admin_users.json.
"""

from __future__ import annotations

import argparse
import getpass
import sys

import storage
from auth import hash_password


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed a CalmSense admin user.")
    parser.add_argument("--email", required=True)
    parser.add_argument("--name", default=None)
    parser.add_argument("--password", default=None, help="If omitted, you'll be prompted.")
    args = parser.parse_args()

    if storage.get_admin_by_email(args.email) is not None:
        print(f"Admin '{args.email}' already exists — nothing to do.")
        return 0

    password = args.password
    if not password:
        password = getpass.getpass("Password (min 8 chars): ")
        if password != getpass.getpass("Confirm password: "):
            print("Passwords do not match.", file=sys.stderr)
            return 1
    if len(password) < 8:
        print("Password must be at least 8 characters.", file=sys.stderr)
        return 1

    admin = storage.create_admin(args.email, hash_password(password), args.name)
    print(f"Created admin id={admin.get('id')} email={admin['email']} "
          f"(storage: {storage.storage_backend()})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
