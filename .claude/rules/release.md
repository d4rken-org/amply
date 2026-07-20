# Release

## Current state (honest)

Amply is **pre-launch** (`0.1.0-spike1`). There is **no release automation** — no `tools/release/` scripts, no
version-bump tooling, no publish lanes. Versioning is manual, edited directly in `version.properties`. Fastlane
exists only as **store-listing metadata** (`fastlane/metadata/android/en-US/*.txt`), not as build/deploy lanes.
Update this file when real release tooling (CAPod-style `bump.sh` + `VERSION` file) lands.

## Versioning

- Single source: `version.properties` at the repo root, parsed at configuration time by the buildSrc
  `ProjectConfigPlugin` (CAPod's scheme): `versionName = "major.minor.patch-type{build}"`,
  `versionCode = major*10000000 + minor*100000 + patch*1000 + build*10`.
- Constraints (nothing enforces these until bump tooling lands): keep `minor`, `patch`, `build` ≤ 99 — overflow
  collides with the next-higher field (e.g. `patch=0,build=100` equals `patch=1,build=0`). Changing only `type` does
  **not** change the versionCode, so a store update needs another field bumped. Keep versionCode monotonic.
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

`.github/workflows/code-checks.yml` runs on push to `main` and on PRs as matrix jobs (CAPod parity): wrapper
validation (in the shared `common-setup` action), `lintVital{Foss,Gplay}{Beta,Release}`, `assemble{Foss,Gplay}Debug`,
`test{Foss,Gplay}DebugUnitTest`, and a fastlane metadata length check. CI does **not** run R8/minified packaging
(lintVital only compiles the beta/release sources) and does **not** publish — run
`./gradlew assembleFossRelease assembleGplayRelease` locally before tagging a release.

## Google Play gate

Google Play publication must remain gated on approval of the declared `specialUse` foreground-service use case (see
`privileged-access.md`). Do not treat a green CI build as clearance to publish.
