package eu.darken.amply.upgrade.ui

sealed interface UpgradeEvents {
    data object RestoreSucceeded : UpgradeEvents

    /** Play answered and no purchase was found. A real result: troubleshooting and escalation apply. */
    data object RestoreFailed : UpgradeEvents

    /**
     * The restore didn't finish within its budget, so ownership is simply unknown. Kept apart from
     * [RestoreFailed] because that dialog asserts a completed check and steers toward the
     * multi-account explanation, neither of which is warranted here.
     */
    data object RestoreInconclusive : UpgradeEvents
    data object SubscriptionStillRenewing : UpgradeEvents
    data object SubscriptionCheckFailed : UpgradeEvents

    /** A billing failure that has no dedicated dialog; the host renders its mapped copy. */
    data class Error(val error: Throwable) : UpgradeEvents
}
