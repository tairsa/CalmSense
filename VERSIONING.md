# Versioning

All CalmSense components use [semantic versioning](https://semver.org):
`MAJOR.MINOR.PATCH`. Everything starts at **1.0.0** (2026-09-07).

## When to bump

**Every build that ships gets a version** - installed on Alex's devices or
deployed to production. Alex is patient zero: he runs every build himself, so
shipping to him *is* the release, and a version is what lets a bug report,
a Sentry event or a `/health` response name exactly which build it came from.
(Until 2026-10-03 a build was only numbered after it had been confirmed on a
device, which left the build under test indistinguishable from the last one.)

Bump in the same commit that ships, never after. Work that has not shipped yet
does not get a number - it is just the current commit.

| Bump  | For |
|-------|-----|
| PATCH | A fix or small change. The default. |
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

Phone and watch ship as GitHub Releases: pushing a `vX.Y.Z` tag runs
`.github/workflows/release.yml`, which builds and signs both APKs with the release key
and publishes them with `SHA256SUMS` and the CHANGELOG.md notes. The phone finds it
(daily notification, or Settings > App updates). The watch still needs adb.

The three components version independently. They start aligned; they are not
expected to stay that way.

## History

CHANGELOG.md.
