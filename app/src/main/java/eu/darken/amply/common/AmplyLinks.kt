package eu.darken.amply.common

object AmplyLinks {
    const val GITHUB = "https://github.com/d4rken-org/amply"
    const val ISSUES = "$GITHUB/issues"
    const val PRIVACY_POLICY = "https://amply.darken.eu/privacy"
    const val CHANGELOG = "https://amply.darken.eu/changelog"

    /**
     * Browser-based ADB helper (WebUSB) deep-linked to Amply. Runs on the *computer* the
     * phone is plugged into — it grants WRITE_SECURE_SETTINGS over USB without a local ADB
     * install. The phone cannot host itself, so this is a link the user opens on their PC.
     */
    const val WEB_ADB = "https://d4rken.github.io/web-adb/#/amply"
}
