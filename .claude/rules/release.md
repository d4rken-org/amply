# Release

## Current state (honest)

Amply is **pre-launch** (`0.1.0-beta1`). It uses CAPod's versioning system: a `version.properties` source of truth,
a `VERSION` mirror, a `tools/release/bump.sh` bump/validate tool, and `release-prepare` / `release-tag` workflows. The
tooling exists but no release has been cut. Fastlane exists only as **store-listing metadata**
(`fastlane/metadata/android/en-US/*.txt`) — there are **no** fastlane build/deploy lanes, so `release-tag.yml`
publishes a **FOSS GitHub release only** (see CI below).

## Versioning

- Single source: `version.properties` at the repo root, parsed at configuration time by the buildSrc
  `ProjectConfigPlugin` (CAPod's scheme): `versionName = "major.minor.patch-type{build}"`,
  `versionCode = major*10000000 + minor*100000 + patch*1000 + build*10`. `type` must be `rc` or `beta` (bump.sh
  rejects anything else). A root `VERSION` file (`<name> <code>`) mirrors it as a drift check.
- Constraints (enforced by both `ProjectConfig`'s `require`s and `bump.sh`): `minor`, `patch`, `build` are `0..99` —
  overflow collides with the next-higher field (e.g. `patch=0,build=100` equals `patch=1,build=0`). Changing only
  `type` does **not** change the versionCode, so a store update needs another field bumped. versionCode stays
  monotonic (`bump.sh` refuses a non-increasing code).
- **Bumping**: don't hand-edit — run `./tools/release/bump.sh --mode=plan --bump-kind=<build|patch|minor|major>`
  to preview, `--mode=write` to apply (rewrites both files and re-verifies). `--mode=check` validates consistency
  (also run in CI). Prefer the `release-prepare` workflow, which wraps bump.sh with tag-collision guards.
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
`test{Foss,Gplay}DebugUnitTest`, a fastlane metadata length check, and a **release-tooling check** (shellcheck +
bats on `bump.sh`, plus `bump.sh --mode=check`). It skips version-bump-only pushes (`paths-ignore: VERSION,
version.properties`). CI does **not** run R8/minified packaging (lintVital only compiles the beta/release sources) —
run `./gradlew assembleFossRelease assembleGplayRelease` locally before tagging a release.

## Release workflows

- **`release-prepare.yml`** (`workflow_dispatch`): computes the next version with `bump.sh`, guards tag collisions,
  and — when `dry_run=false` — commits `version.properties`+`VERSION`, tags `v<name>`, and pushes. The push job needs
  the `RELEASE_APP_CLIENT_ID` / `RELEASE_APP_PRIVATE_KEY` GitHub App secrets; the dry-run plan needs no secrets.
- **`release-tag.yml`** (on a `v*` tag): validates the tag against `version.properties`, builds `assembleFossBeta`
  (for `-beta` tags) or `assembleFossRelease`, and attaches the versioned APK to a GitHub (pre-)release. Signed only
  when `SIGNING_KEYSTORE_BASE64` + `STORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` secrets are present; otherwise the APK
  is unsigned (its filename carries an `-UNSIGNED` marker).
- **Deliberately omitted vs. CAPod**: no Google Play upload job (Amply has no fastlane deploy lanes, and Play
  publication must stay gated — see below) and no Pages deploy (the project website is deferred).

## Google Play gate

Google Play publication must remain gated on approval of the declared `specialUse` foreground-service use case (see
`privileged-access.md`). Do not treat a green CI build as clearance to publish. `release-tag.yml` intentionally does
**not** upload to Play — that step is added only after the use-case approval lands.
