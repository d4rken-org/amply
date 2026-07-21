package eu.darken.amply.diagnostics.core

import eu.darken.amply.charging.core.access.SettingNamespace

/**
 * The exact, reviewable set of charge-protection settings whose values are safe to auto-disclose in a **public**
 * GitHub issue, keyed by namespace-qualified identity with an explicit public value domain.
 *
 * A row auto-discloses only when every observed value is within its domain (see [ContributionMatrix]); an unexpected
 * value under a known key stays opt-in, because a known key can still be repurposed or corrupted to hold something
 * sensitive. Everything not listed here is redacted by default — whole row, including the key name.
 *
 * Mirrors the spike-verified charge keys (kept deliberately separate from the privileged `SettingWritePolicy`, which
 * governs writes; these two domains happen to coincide today but answer different questions). Extend only with
 * spike-documented keys — see the qualification ledger in `.claude/rules/privileged-access.md`.
 */
object ContributionAllowlist {
    private val ROWS: Map<SettingId, Set<String>> = mapOf(
        // Pixel charging optimization
        SettingId(SettingNamespace.SECURE, "charge_optimization_mode") to setOf("0", "1"),
        SettingId(SettingNamespace.SECURE, "adaptive_charging_enabled") to setOf("0", "1"),
        // Xiaomi HyperOS charging protection
        SettingId(SettingNamespace.SECURE, "security_pc_secure_protect_mode_key") to setOf("0", "1"),
        // Samsung battery protection
        SettingId(SettingNamespace.GLOBAL, "protect_battery") to setOf("0", "1", "3"),
        SettingId(SettingNamespace.GLOBAL, "battery_protection_threshold") to setOf("80", "85", "90", "95"),
        // OnePlus/Oppo candidate
        SettingId(SettingNamespace.SYSTEM, "regular_charge_protection_switch_state") to setOf("0", "1"),
    )

    /** Public value domain for a known charging key, or null if the key is not a known charge-protection setting. */
    fun publicValues(id: SettingId): Set<String>? = ROWS[id]
}
