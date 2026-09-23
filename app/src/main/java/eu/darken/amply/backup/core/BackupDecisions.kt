package eu.darken.amply.backup.core

import eu.darken.amply.rules.core.ChargeRule

data class CapabilityOutcome(
    val backup: AmplyBackup,
    val gestureDowngraded: Boolean,
)

/** A gesture enabled on the source device stays off where the destination cannot run it. */
fun AmplyBackup.withDestinationCapability(gestureAvailable: Boolean): CapabilityOutcome {
    if (gestureAvailable || settings.reconnectGestureEnabled != true) {
        return CapabilityOutcome(this, gestureDowngraded = false)
    }
    return CapabilityOutcome(
        backup = copy(settings = settings.copy(reconnectGestureEnabled = false)),
        gestureDowngraded = true,
    )
}

/** Whether the state after restoring this backup has anything enabled that posts notifications. */
fun AmplyBackup.needsNotifications(): Boolean =
    settings.chargeAlarm?.enabled == true ||
        settings.reconnectGestureEnabled == true ||
        rules?.any { it.enabled } == true

/** Switches off every notification-dependent feature the backup carries; absent fields stay absent. */
fun AmplyBackup.withNotificationsDenied(): AmplyBackup = copy(
    settings = settings.copy(
        chargeAlarm = settings.chargeAlarm?.copy(enabled = false),
        reconnectGestureEnabled = settings.reconnectGestureEnabled?.let { false },
    ),
    rules = rules?.map { it.copy(enabled = false) },
)

/** Rules whose policy this destination does not offer, including ids this build cannot parse. */
fun countUnsupportedRules(rules: List<ChargeRule>, supportedPolicyIds: Set<String>): Int =
    rules.count { it.policyId !in supportedPolicyIds }
