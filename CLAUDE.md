# CalmSense: notes for Claude

Panic-attack detection: Galaxy Watch (Wear OS, Kotlin) → Android phone (Kotlin/Compose,
on-device model) → FastAPI backend on Cloud Run + Supabase Postgres → React admin web.
Started as an academic project; the goal is a product. Architecture: README.md.
Commands: COMMANDS.md. Open work: TASKS.md. Road to the app standard: docs/standard-plan.md.

## Decided (don't re-ask)

- **Client path**: the phone app moves to Flutter (plan phase 4) so it can run on iOS later.
  The watch stays Kotlin: the Samsung Health Sensor SDK is a native `.aar`.
- Backend stays FastAPI on Cloud Run + Supabase; no move to Firebase. Free tiers only.
- Phone and watch share one version and one `applicationId` (`com.calmsense.app`): the
  Data Layer pairs by id. Versioning rules: VERSIONING.md (bump in the commit that ships).
- Alex is patient zero and the only real user (account alex99ka@gmail.com).
- Phone + watch releases are built and signed only by `.github/workflows/release.yml`
  (tag `vX.Y.Z`) with the release key (`calmsense-release.jks`, backed up on Drive). Any
  other key forces an uninstall (data loss). 1.4.1 and older were signed with the PC's
  debug key; 1.5.0 is the one-time switch. Release cert SHA-256: `ad61e01447a9fc9a5f36e54cc75310a5f23eb8efea415707a863d822e3fbf7a4`.
- The Supabase MCP connector cannot reach the production project; schema changes are
  run by Alex in the Supabase SQL editor.

## Rules

- Never publish a release, tag, deploy, or merge/push to `main` without Alex's word
  ("publish", "deploy", "merge"). **A push to `main` deploys the backend** (ci.yml).
- Test before shipping; ship the binary that was tested.
- Never read or print secrets (`.env`, keystores, service-account JSON). Give Alex the command.
- Keep TASKS.md current after every step; sessions get cut off mid-task.
