# Versioning

All CalmSense components use [semantic versioning](https://semver.org):
`MAJOR.MINOR.PATCH`. Everything starts at **1.0.0** (2026-09-07).

## When to bump

A build becomes a version **once Alex has confirmed it on a real device.**
Work in progress does not get a number — an unconfirmed build is just the
current commit. This keeps a version a statement about something that was
actually seen working, not about something that merely compiled.

| Bump  | For |
|-------|-----|
| PATCH | A confirmed fix or small change. The default. |
| MINOR | New user-visible capability, backwards compatible. |
| MAJOR | A break: an API contract change, or a phone build that no longer works with an existing watch build. |

## Where the number lives

Each deployable owns exactly one source of truth. Bump the file, nothing else.

| Component | Source of truth | Shown to the user |
|-----------|-----------------|-------------------|
| Phone + watch | `android-app/gradle.properties` → `calmsense.versionName` | Settings, foot of the list / watch status face |
| Admin web app | `admin-app/package.json` → `version` | Top bar, beside the brand |
| Backend | `calmsense-backend/main.py` → `APP_VERSION` | `GET /health` → `"version"` |

The phone and watch deliberately share one number. They have the same
`applicationId` and pair over the Data Layer, so letting them drift would make
"which build are you on?" ambiguous exactly when a pairing bug makes it matter.

`versionCode` is **derived**, never edited: `android-app/build.gradle.kts`
computes `MAJOR*10000 + MINOR*100 + PATCH` (so 1.0.0 → 10000). The build fails
if the version is not three numeric parts, or if minor or patch reaches 100 —
past that the derived code would stop increasing, which Play rejects.

The three components version independently. They start aligned; they are not
expected to stay that way.
