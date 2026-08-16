package eu.darken.amply.charging.core.adapter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import javax.inject.Inject
import javax.inject.Singleton

data class AdapterSelection(
    val adapter: ChargingAdapter?,
    val support: AdapterSupport,
)

@Singleton
class AdapterRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    lineage: LineageChargingAdapter,
    lineageLab: LineageLabAdapter,
    grapheneOs: GrapheneOsChargingAdapter,
    pixel: PixelChargingAdapter,
    samsungModern: SamsungModernChargingAdapter,
    samsungLegacy: SamsungLegacyChargingAdapter,
    samsungLab: SamsungLabAdapter,
    xiaomi: XiaomiChargingAdapter,
    xiaomiHyperOs3: XiaomiHyperOs3ChargingAdapter,
    xiaomiLab: XiaomiLabAdapter,
    onePlus: OnePlusChargingAdapter,
    onePlusLab: OnePlusLabAdapter,
) {
    // Custom-ROM adapters come FIRST: a custom ROM changes charging control regardless of the OEM
    // hardware underneath, so a LineageOS build on Samsung/Xiaomi/OnePlus/Pixel must be handled by the
    // Lineage live/lab pair — never swallowed by a manufacturer-based OEM adapter. lineageLab sits
    // right after the live adapter and before all OEM adapters, catching a derivative that reports the
    // Lineage feature without shipping the settings provider. GrapheneOS is the same class and must
    // precede `pixel` specifically: it ships only on Pixels, and the Pixel probe matches any
    // Google/Pixel* device, which would swallow it as a matched-but-diagnostics-only stock Pixel.
    // Stock devices match neither ROM identity, so all three skip and OEM matching proceeds.
    // Live adapters otherwise match only their verified scopes; same-OEM misses fall to the lab adapters.
    private val adapters = listOf(
        lineage, lineageLab, grapheneOs,
        pixel, samsungModern, samsungLegacy, samsungLab, xiaomi, xiaomiHyperOs3, xiaomiLab, onePlus, onePlusLab,
    )

    /**
     * Select the adapter for [device] and resolve what its enforcement evidence permits.
     *
     * [evidenceState] has **no default on purpose**: with one, "the caller forgot", "the store hasn't
     * been read yet" and "genuinely nothing stored" would all collapse into the control-enabled
     * branch. Callers that only need adapter *capabilities* (settings URIs, the native-settings
     * intent, the reconnect-gesture flag) pass [EnforcementEvidenceState.Loading], which resolves
     * fail-closed and can never enable control.
     *
     * [verificationStarted] is whether the user opted this build into verification; it only ever
     * *withholds* less, so it defaults to the conservative false.
     */
    fun select(
        device: DeviceInfo = DeviceInfo.current(context),
        evidenceState: EnforcementEvidenceState,
        verificationStarted: Boolean = false,
    ): AdapterSelection {
        val match = adapters.firstNotNullOfOrNull { adapter ->
            adapter.probe(device).takeIf { it.matched }?.let { adapter to it }
        } ?: return AdapterSelection(
            adapter = null,
            support = AdapterSupport(
                matched = false,
                controlEnabled = false,
                detail = R.string.adapter_detail_none,
                contributionWanted = true,
            ),
        )
        val (adapter, support) = match
        return AdapterSelection(
            adapter = adapter,
            support = resolveEnforcement(adapter, device, support, evidenceState, verificationStarted),
        )
    }
}

/**
 * Apply the enforcement gate to a matched adapter's [support]. The order is the contract:
 *
 *  1. a refutation — or an undecodable record, which may be one — disables control and asks for a
 *     contribution, whatever else is true;
 *  2. a maintainer-qualified device or a local confirmation leaves control exactly as the probe
 *     decided. This step MUST precede step 4, or "no stored evidence" would override the
 *     maintainer fast path and turn every qualified device into a candidate;
 *  3. a user-started verification leaves control as probed, but the surfaces must not claim the cap
 *     is proven;
 *  4. otherwise the device is a candidate: control off until the user starts verification.
 *
 * [EnforcementEvidenceState.Loading] falls through to step 4 — fail-closed.
 */
internal fun resolveEnforcement(
    adapter: ChargingAdapter,
    device: DeviceInfo,
    support: AdapterSupport,
    evidenceState: EnforcementEvidenceState,
    verificationStarted: Boolean,
): AdapterSupport {
    if (!adapter.enforcementEvidenceRequired) return support
    val evidence = (evidenceState as? EnforcementEvidenceState.Present)
        ?.evidence
        ?.takeIf { it.adapterId == adapter.id }
    return when {
        evidence?.verdict == EnforcementVerdict.REFUTED || evidenceState is EnforcementEvidenceState.Corrupt ->
            support.copy(
                controlEnabled = false,
                detail = support.gateDetail(R.string.adapter_detail_enforcement_refuted),
                // A device that accepts the setting and charges past it anyway is exactly the report
                // the maintainer wants, even though the adapter itself is fully mapped.
                contributionWanted = true,
                enforcement = EnforcementStatus.REFUTED,
            )
        adapter.maintainerQualified(device) || evidence?.verdict == EnforcementVerdict.CONFIRMED ->
            support.copy(enforcement = EnforcementStatus.CONFIRMED)
        verificationStarted -> support.copy(enforcement = EnforcementStatus.UNDER_TEST)
        else -> support.copy(
            controlEnabled = false,
            detail = support.gateDetail(R.string.adapter_detail_enforcement_candidate),
            enforcement = EnforcementStatus.CANDIDATE,
        )
    }
}

/**
 * The enforcement reason replaces the probe's detail only when the probe itself was happy. A device
 * that already failed a gate (secondary user, missing provider) keeps that more specific reason —
 * telling the user to run a verification they cannot complete would be the wrong instruction.
 */
private fun AdapterSupport.gateDetail(enforcementDetail: Int): Int =
    if (controlEnabled) enforcementDetail else detail
