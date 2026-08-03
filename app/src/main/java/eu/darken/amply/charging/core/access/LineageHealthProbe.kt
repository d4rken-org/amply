package eu.darken.amply.charging.core.access

/** The charging-control provider LineageOS has currently bound. */
enum class LineageChargingProvider {
    /** Hard percentage cap — the mechanism Amply's FixedLimit policy needs. */
    LIMIT,

    /** Plain charging on/off, no native cap. */
    TOGGLE,

    /** Time/alarm-based (Google Adaptive-style). */
    DEADLINE,

    /** Present but not an upstream provider — a fork. Never interpreted. */
    UNKNOWN,
}

/**
 * By which mechanism, if any, this device could hold a fixed percentage cap — as far as the *currently bound*
 * provider reveals.
 *
 * **None of these values is a disqualifier**, and there is deliberately no "unsupported" case. Two upstream facts
 * rule one out: `Toggle` also accepts `MODE_LIMIT` and enforces `targetPct` itself by cutting charging, so it is a
 * capable mechanism rather than a negative; and `isHALModeSupported` converts a `RemoteException` into `false`, so
 * even "provider X was not selected" can be a transient artefact rather than a capability statement.
 */
enum class LineageLimitMechanism {
    /** Native HAL limit — `Limit` is only bound when `mLimit.isSupported()`. */
    NATIVE_LIMIT,

    /** Framework-driven: `Toggle` accepts MODE_LIMIT and cuts charging at `targetPct` (with a recharge margin). */
    FRAMEWORK_TOGGLE,

    /**
     * Not observed. The device was in a time-based mode, whose branch returns `Deadline` before either
     * limit-capable provider is consulted — so nothing was learned. Re-run with a limit set to resolve.
     */
    NOT_OBSERVED,

    /** A fork or newer provider; not interpretable. */
    UNKNOWN,
}

/**
 * What `dumpsys lineagehealth` tells us about this device's charge-control capability.
 *
 * **The provider alone is not a capability readout.** Upstream `ChargingControlController.getProviderForMode`
 * branches on the *configured mode* first and only then on capability:
 * `MODE_LIMIT` tries Limit then Toggle, while `MODE_AUTO`/`MODE_MANUAL` try **Deadline first**, then Limit, then
 * Toggle. So a device sitting in AUTO reports Deadline whether or not its HAL also supports LIMIT — Deadline
 * short-circuits before Limit is ever consulted. (This bit Amply once already: oriole's NO-GO was established by
 * a mode=3 write reading back as 1, not by its Deadline dump.)
 */
data class LineageHealthSummary(
    val provider: LineageChargingProvider,
    /** Configured `charging_control_mode`, or null if absent. 0=none, 1=auto, 2=manual, 3=limit. */
    val mode: Int?,
) {
    /**
     * The mechanism the bound provider would use for a percentage cap. Read it as "what was observed", never as a
     * verdict: [LineageLimitMechanism] has no negative case, and even [LineageLimitMechanism.NATIVE_LIMIT] is not
     * qualification — oriole on LineageOS 20 bound `Limit` and still charged straight past the cap. Whether the
     * hardware actually stops the current can only be settled by watching a real charging session.
     */
    val limitMechanism: LineageLimitMechanism
        get() = when (provider) {
            LineageChargingProvider.LIMIT -> LineageLimitMechanism.NATIVE_LIMIT
            LineageChargingProvider.TOGGLE -> LineageLimitMechanism.FRAMEWORK_TOGGLE
            LineageChargingProvider.DEADLINE -> LineageLimitMechanism.NOT_OBSERVED
            LineageChargingProvider.UNKNOWN -> LineageLimitMechanism.UNKNOWN
        }

    /** Compact, non-sensitive form for the Binder hop — see [parseLineageHealthSummary]. */
    fun encode(): String = "${provider.name}|${mode ?: ""}"
}

/**
 * Parses `dumpsys lineagehealth` output.
 *
 * **Only the provider and the mode are read.** The dump also carries `StartTime`/`TargetTime` — the user's
 * configured charging schedule, which reveals their sleep window — and the live battery percentage. None of that
 * may reach a public report, so this extracts two fields instead of forwarding raw text, and the raw dump is
 * parsed inside the privileged user service so it never crosses the Binder boundary at all.
 *
 * Returns null when there is no provider line (not LineageOS, or charging control unavailable).
 */
fun parseLineageHealthDump(raw: String): LineageHealthSummary? {
    var provider: LineageChargingProvider? = null
    var mode: Int? = null
    raw.lineSequence().map { it.trim() }.forEach { line ->
        when {
            provider == null && line.startsWith(PROVIDER_PREFIX) ->
                provider = classifyProvider(line.removePrefix(PROVIDER_PREFIX).trim())
            // "Mode: 1" in the Configuration block. mIsEnabled/etc. are deliberately ignored.
            mode == null && line.startsWith(MODE_PREFIX) ->
                mode = line.removePrefix(MODE_PREFIX).trim().toIntOrNull()
        }
    }
    return provider?.let { LineageHealthSummary(it, mode) }
}

/**
 * Fully-qualified match only. A fork's `com.example.Deadline` must NOT inherit upstream's semantics — derivatives
 * are deliberately accepted by `DeviceInfo.isLineageOs`, so a same-simple-name class from an unknown package is
 * exactly the case that has to fail closed to [LineageChargingProvider.UNKNOWN].
 */
private fun classifyProvider(className: String): LineageChargingProvider = when (className) {
    "$UPSTREAM_PROVIDER_PACKAGE.Limit" -> LineageChargingProvider.LIMIT
    "$UPSTREAM_PROVIDER_PACKAGE.Toggle" -> LineageChargingProvider.TOGGLE
    "$UPSTREAM_PROVIDER_PACKAGE.Deadline" -> LineageChargingProvider.DEADLINE
    else -> LineageChargingProvider.UNKNOWN
}

/** Decodes [LineageHealthSummary.encode]; null on anything unrecognised. */
fun parseLineageHealthSummary(encoded: String?): LineageHealthSummary? {
    val parts = encoded?.split('|') ?: return null
    if (parts.size != 2) return null
    val provider = LineageChargingProvider.entries.firstOrNull { it.name == parts[0] } ?: return null
    return LineageHealthSummary(provider, parts[1].toIntOrNull())
}

private const val PROVIDER_PREFIX = "Provider:"
private const val MODE_PREFIX = "Mode:"
private const val UPSTREAM_PROVIDER_PACKAGE = "org.lineageos.platform.internal.health.ccprovider"
