#!/usr/bin/env bash
# Dump the database and encrypt the dump. Run daily by .github/workflows/backup.yml.
#
# Needs DATABASE_URL and BACKUP_PASSPHRASE in the environment, pg_dump (same
# major version as the server or newer) and gpg. Writes, in the current folder:
#   calmsense-db-<date>.dump.gpg       the public schema: every app table, schema + data
#   calmsense-auth-<date>.dump.gpg     auth.users: accounts, so restored rows keep their owners
# Restore steps: COMMANDS.md -> "Restore a database backup".
set -euo pipefail
: "${DATABASE_URL:?set DATABASE_URL}" "${BACKUP_PASSPHRASE:?set BACKUP_PASSPHRASE}"
DAY=$(date -u +%F)

# Privileges stay in: they hold the revoke that keeps admin_user_stats() away from anon.
pg_dump -d "$DATABASE_URL" --schema=public --no-owner -Fc -f "calmsense-db-$DAY.dump"
pg_dump -d "$DATABASE_URL" --table=auth.users --data-only -Fc -f "calmsense-auth-$DAY.dump"

for f in calmsense-db-"$DAY".dump calmsense-auth-"$DAY".dump; do
  # Passphrase on stdin, not argv, so it never shows in a process list or log.
  gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 \
      --symmetric --cipher-algo AES256 -o "$f.gpg" "$f" <<<"$BACKUP_PASSPHRASE"
  rm "$f"
done
ls -l calmsense-*-"$DAY".dump.gpg
