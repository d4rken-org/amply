package eu.darken.amply.charging.core

/**
 * Outcome of an unprivileged presence probe for a single settings key.
 *
 * A Boolean cannot carry this. The public `Settings.*.getString` getters throw [SecurityException] for keys the
 * caller may not read — GrapheneOS marks its charge-limit key `@Protected` and denies it to every third-party
 * package, and from API 31 the platform denies non-`@Readable` keys to apps generally — so folding the throw into
 * `false` claims "this OEM has no such setting" when the truth is "we were not allowed to look".
 *
 * That misread a real device: the issue-#49 GrapheneOS report carried `has_battery_charge_limit=false` in the same
 * submission that showed the limit actively enforcing. Reports are the only evidence available for devices nobody
 * owns, so an absent-vs-denied ambiguity there costs a qualification lead.
 */
enum class SettingProbe {
    /** The key exists and its value was read back. */
    PRESENT,

    /**
     * No value came back. Usually means the key does not exist on this build, but it is not proof: the platform
     * also returns null when it cannot reach the settings provider, and this state additionally absorbs any
     * non-security failure of the read itself. Treat it as "nothing found", not as a demonstrated negative.
     */
    ABSENT,

    /** The read was refused. Says nothing either way about whether the key exists. */
    READ_DENIED,
    ;

    /**
     * Fail closed. Only an actual read-back counts as presence, so a capability gate never opens on a denied
     * probe — matching the previous Boolean fields, where a refused read also read as false.
     */
    val isPresent: Boolean get() = this == PRESENT

    /** Stable lowercase token for the device-support report. */
    val reportValue: String get() = name.lowercase()
}

/**
 * Runs an unprivileged key read and classifies the outcome.
 *
 * Only [SecurityException] maps to [SettingProbe.READ_DENIED]; other exceptions fall to [SettingProbe.ABSENT], which
 * keeps the fail-closed behaviour of the `runCatching { … }.getOrDefault(false)` call sites this replaced. They are
 * not split into a fourth "read failed" state because that state could not be trusted anyway: the platform swallows
 * provider-acquisition and `RemoteException` failures internally and simply returns null, so an operational failure
 * frequently never surfaces as an exception here at all.
 *
 * Unlike `runCatching`, this catches [Exception] rather than [Throwable], so an [Error] propagates.
 */
internal inline fun probeSetting(read: () -> String?): SettingProbe = try {
    if (read() != null) SettingProbe.PRESENT else SettingProbe.ABSENT
} catch (_: SecurityException) {
    SettingProbe.READ_DENIED
} catch (_: Exception) {
    SettingProbe.ABSENT
}
