#!/usr/bin/env bash
#
# Update the CalmSense server on the Pi in one command: pull the latest code and
# rebuild/restart the containers (backend + dashboard).
#
# One-time setup:   chmod +x update-server.sh
# Then, to update:  ./update-server.sh
#
# The whole script is wrapped in main() so bash parses it fully before running.
# That way `git pull` rewriting this file mid-run can't corrupt the execution.

set -euo pipefail

main() {
  # Work from the repo root (this script lives there), wherever it's called from.
  cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")"

  echo "==> Pulling latest from git..."
  git pull --ff-only

  echo "==> Rebuilding and restarting containers..."
  cd calmsense-backend
  docker compose up -d --build

  echo "==> Done. Current status:"
  docker compose ps
}

main "$@"
