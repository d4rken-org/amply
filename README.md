<img src="https://github.com/d4rken-org/amply/raw/main/.assets/banner.png" width="400" alt="Amply banner">

# Amply

[![GitHub release (latest SemVer including pre-releases)](https://img.shields.io/github/v/release/d4rken-org/amply?include_prereleases)](https://github.com/d4rken-org/amply/releases/latest)
[![RB Status](https://shields.rbtlog.dev/simple/eu.darken.amply)](https://shields.rbtlog.dev/eu.darken.amply)
[![Code tests & eval](https://github.com/d4rken-org/amply/actions/workflows/code-checks.yml/badge.svg)](https://github.com/d4rken-org/amply/actions/workflows/code-checks.yml)
[![Github Downloads](https://img.shields.io/github/downloads/d4rken-org/amply/total.svg?label=GitHub%20Downloads&logo=github)](https://github.com/d4rken-org/amply#download)
[![Discord](https://img.shields.io/badge/Discord-Amply-5865F2?logo=discord&logoColor=white)](https://discord.gg/cyaFKfeCKJ)

<!-- Enable at launch (Amply is pre-launch: no Play listing / Crowdin project yet):
[![Google Play Downloads](https://img.shields.io/endpoint?color=green&logo=google-play&logoColor=green&url=https%3A%2F%2Fplay.cuzi.workers.dev%2Fplay%3Fi%3Deu.darken.amply%26l%3DGoogle%2520Play%26m%3D%24totalinstalls)](https://github.com/d4rken-org/amply#download)
[![Crowdin](https://badges.crowdin.net/amply/localized.svg)](https://crowdin.com/project/amply)
-->



> _**Amply is experimental and pre-launch. Direct control is verified on a limited set of devices — device reports and feedback are welcome!**_

[Amply](https://github.com/d4rken-org/amply) is an Android controller for OEM battery charge-protection modes. Its
primary action temporarily allows one full charge, then restores your protective charge limit automatically — at 100%,
on unplug, or at a safety timeout.

Features include:

* One-tap "charge to 100% once", then automatic restore of your protective limit
* A dashboard that reports charge state honestly — verified, last-requested, or unknown
* Optional "reconnect to charge to 100%" gesture at the active hardware limit
* Quick Settings tile and a home-screen widget for protect / full-charge actions
* A persisted temporary-session monitor that survives reboots
* Diagnostics workflow (Shizuku) for before/after setting discovery, with redacted reports
* Works without ADB/Shizuku too — a charge alarm to remind you to unplug at a chosen level, a live battery-info card, and an OEM battery-protection guide on unsupported devices
* Branded light/dark themes, optional Material You, and contrast choices
* Works via `WRITE_SECURE_SETTINGS` or Shizuku
* No ads, no tracking, open-source (`foss` and `gplay` flavors)

Currently supported for direct control:

* Pixel 6a and newer, Android 15+ (Google charging optimization)
* Samsung One UI 8 (multi-mode battery protection)
* Samsung One UI 4 / 5 (legacy battery-protection toggle)
* Xiaomi / Redmi / POCO on HyperOS 2 (charging protection)
* OnePlus / Oppo / Realme on ColorOS 15 (charging protection; requires Shizuku)

Other devices — other Pixels, Samsung One UI 6/7 and 9+, non-HyperOS-2 Xiaomi, and non-ColorOS-15
OnePlus/Oppo/Realme — are diagnostics-only for now.

## Download

| Source                                                                 | Status                                                                                                                                                                                                                                                                                     |
|------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [GitHub (Pre-Release)](https://github.com/d4rken-org/amply/releases)   | ![https://github.com/d4rken-org/amply/releases](https://img.shields.io/github/v/release/d4rken-org/amply?include_prereleases&display_name=release&logo=github&label=GitHub%20(Pre-Release)) ![](https://img.shields.io/github/downloads-pre/d4rken-org/amply/latest/total?label=%20)         |

<!-- Enable these rows at launch (Amply is pre-launch: no stable release / Play listing / F-Droid packages yet):

| [Google Play](https://play.google.com/store/apps/details?id=eu.darken.amply) | ![](https://img.shields.io/endpoint?color=green&logo=google-play&logoColor=green&url=https%3A%2F%2Fplay.cuzi.workers.dev%2Fplay%3Fi%3Deu.darken.amply%26l%3DGoogle%2520Play%26m%3D%24version) |
| [GitHub (Release)](https://github.com/d4rken-org/amply/releases)             | ![](https://img.shields.io/github/v/release/d4rken-org/amply?display_name=release&logo=github&label=GitHub%20(Release)) ![](https://img.shields.io/github/downloads/d4rken-org/amply/latest/total?label=%20) |
| [F-Droid](https://f-droid.org/en/packages/eu.darken.amply/)                  | ![](https://img.shields.io/f-droid/v/eu.darken.amply?logo=f-droid&label=f-droid%20(latest)) |
| [F-Droid (IzzyOnDroid)](https://apt.izzysoft.de/packages/eu.darken.amply/)   | ![](https://img.shields.io/endpoint?url=https://apt.izzysoft.de/fdroid/api/v1/shield/eu.darken.amply&label=IzzyOnDroid%20(latest)) |
-->

## Support the project

Amply has no ads and doesn't sell your data. It's free and open-source.

* [Sponsor development](https://github.com/sponsors/d4rken) on GitHub
* [Buy me a coffee](https://buymeacoffee.com/tydarken) ☕

<!-- Enable at launch: * Help translate Amply on Crowdin -->

## Get help

* [GitHub Issues](https://github.com/d4rken-org/amply/issues)
* [GitHub Discussions](https://github.com/d4rken-org/amply/discussions)
* [Discord](https://discord.gg/cyaFKfeCKJ)

## Screenshots

<!-- TODO: no screenshots exist yet. Drop phoneScreenshots into fastlane/metadata/android/en-US/images/phoneScreenshots/ and these will render. -->

<img src="https://github.com/d4rken-org/amply/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="100"><img src="https://github.com/d4rken-org/amply/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="100"><img src="https://github.com/d4rken-org/amply/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="100"><img src="https://github.com/d4rken-org/amply/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="100"><img src="https://github.com/d4rken-org/amply/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="100"><img src="https://github.com/d4rken-org/amply/raw/main/fastlane/metadata/android/en-US/images/phoneScreenshots/6.png" width="100">

## Setup & access

Install Amply, then enable control through one access path:

```shell
adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS
```

Alternatively, start Shizuku, grant Amply access, and optionally use Amply's setup card to grant durable WSS. WSS-only
control can write Pixel's hidden values but Android blocks direct third-party reads, so Amply also watches Android's
public charging-hardware state; Shizuku provides exact configured-setting readback while it is running. On tested
Pixels, Google's policy worker takes roughly 10–15 seconds to propagate a setting change to the charging HAL.

## License

Amply's code is available under a GPL v3 license, this excludes:

* Amply icons, logos, mascots and marketing materials/assets.
* Amply animations and videos.
* Amply documentation.
* Google Play screenshots.
* Google Play texts & descriptions.
* Translations.
