# Release

## Current state (honest)

Amply is **pre-launch** (`versionName = "0.1.0-spike1"`, `versionCode = 10`). There is **no release automation** — no
`tools/release/` scripts, no version-bump tooling, no publish lanes. Versioning is manual, edited directly in
`app/build.gradle.kts`. Fastlane exists only as **store-listing metadata** (`fastlane/metadata/android/en-US/*.txt`),
not as build/deploy lanes. Update this file when real release tooling lands.

## Versioning

- Single source: `defaultConfig` in `app/build.gradle.kts` (`versionCode`, `versionName`).
- No build-type or flavor suffixes: every variant installs as `eu.darken.amply` with the same versionName. Because
  signing certificates differ (debug key vs foss key vs gplay upload key), installed variants are mutually exclusive
  on a device — switching requires an uninstall.

## Signing

Release/beta signing loads per flavor (`releaseFoss`, `releaseGplay`) from either:

- Environment variables: `STORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (checked first), or
- Properties files under `~/.config/projects/eu.darken.amply/`:
  `signing-foss.properties` and `signing-gplay-upload.properties`, using keys `release.storePath`,
  `release.storePassword`, `release.keyAlias`, `release.keyPassword`.

If signing material is absent, the flavor simply builds unsigned — the config degrades gracefully rather than failing.
Never commit signing material or point these at repo paths.

## CI

`.github/workflows/code-checks.yml` runs on push to `main` and on PRs: validates the Gradle wrapper, then builds +
tests + lints **both** flavors including release assembly. It does **not** publish — CI is verification only.

## Google Play gate

Google Play publication must remain gated on approval of the declared `specialUse` foreground-service use case (see
`privileged-access.md`). Do not treat a green CI build as clearance to publish.
