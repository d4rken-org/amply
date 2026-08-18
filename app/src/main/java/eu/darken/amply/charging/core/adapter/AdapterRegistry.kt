package eu.darken.amply.charging.core.adapter

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.enforcement.EnforcementEvidenceState
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.charging.core.enforcement.EnforcementVerdict
import eu.darken.amply.charging.core.qualification.QualificationEvidenceState
import eu.darken.amply.charging.core.qualification.QualificationOutcomeRecord
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
     * [verificationStarted] is whether the user accepted control on this unconfirmed build (the
     * `enforcement.verification_started_for` opt-in, kept under its original key); it only ever
     * *withholds* less, so it defaults to the conservative false.
     */
    fun select(
        device: DeviceInfo = DeviceInfo.current(context),
        evidenceState: EnforcementEvidenceState,
        qualification: QualificationEvidenceState,
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
            support = resolveEnforcement(adapter, device, support, evidenceState, qualification, verificationStarted),
        )
    }
}

/**
 * Apply the enforcement gate to a matched adapter's [support]. The order is the contract:
 *
 *  1. a refutation — or an undecodable record, which may be one — disables control and asks for a
 *     contribution, whatever else is true;
 *  2. a maintainer-qualified device leaves control exactly as the probe decided. That is the only
 *     route to CONFIRMED: physical qualification, never observation (see [EnforcementVerdictEngine]).
 *     This step MUST precede step 5, or "no stored evidence" would override the maintainer fast path
 *     and turn every qualified device into a candidate;
 *  3. a guided qualification run that passed on THIS adapter and THIS build enables control as
 *     SELF_QUALIFIED. It sits below the maintainer fast path (a ledger row is the stronger claim and
 *     should win the label) and above the opt-in (a device that proved itself must not be described
 *     as merely unverified). It sits below the refutation for the reason refutations are terminal:
 *     a device seen charging past its cap loses control however it earned it;
 *  4. a user who accepted the unconfirmed build gets control as probed, but the surfaces must not
 *     claim the cap holds;
 *  5. otherwise the device is a candidate: control off until the user accepts it unconfirmed.
 *
 * Both evidence states fall through to step 5 while [EnforcementEvidenceState.Loading] /
 * [QualificationEvidenceState.Loading] — fail-closed.
 *
 * A probe that already refused control (secondary user, missing provider) short-circuits before any
 * of it: enforcement stays **null**, so the surfaces show the specific probe reason and no opt-in
 * action. Control is unreachable there anyway — the recorder refuses to observe off the system user
 * and `canApply` never becomes true — so offering the opt-in would be an offer that changes nothing,
 * on a card claiming controls are available.
 */
internal fun resolveEnforcement(
    adapter: ChargingAdapter,
    device: DeviceInfo,
    support: AdapterSupport,
    evidenceState: EnforcementEvidenceState,
    qualification: QualificationEvidenceState,
    verificationStarted: Boolean,
): AdapterSupport {
    if (!adapter.enforcementEvidenceRequired) return support
    // The probe already said no for a reason the user cannot opt their way out of; keep that reason
    // and leave enforcement unset rather than offering an opt-in that cannot change anything.
    if (!support.controlEnabled) return support
    val evidence = (evidenceState as? EnforcementEvidenceState.Present)
        ?.evidence
        ?.takeIf { it.adapterId == adapter.id }
    return when {
        evidence?.verdict == EnforcementVerdict.REFUTED || evidenceState is EnforcementEvidenceState.Corrupt ->
            support.copy(
                controlEnabled = false,
                detail = R.string.adapter_detail_enforcement_refuted,
                // A device that accepts the setting and charges past it anyway is exactly the report
                // the maintainer wants, even though the adapter itself is fully mapped.
                contributionWanted = true,
                enforcement = EnforcementStatus.REFUTED,
            )
        adapter.maintainerQualified(device) -> support.copy(enforcement = EnforcementStatus.CONFIRMED)
        qualification.passedFor(adapter.id) -> support.copy(enforcement = EnforcementStatus.SELF_QUALIFIED)
        verificationStarted -> support.copy(enforcement = EnforcementStatus.UNVERIFIED)
        else -> support.copy(
            controlEnabled = false,
            detail = R.string.adapter_detail_enforcement_candidate,
            enforcement = EnforcementStatus.CANDIDATE,
        )
    }
}

/**
 * Whether a guided run passed for [adapterId]. The build scope is already applied by
 * `QualificationEvidenceStore` on read — a record from another ROM build or protocol version never
 * reaches here as `Present` — so this only has to match the adapter, which stops a pass earned on one
 * adapter from licensing a different one on the same device.
 */
private fun QualificationEvidenceState.passedFor(adapterId: String): Boolean {
    val evidence = (this as? QualificationEvidenceState.Present)?.evidence ?: return false
    return evidence.outcome == QualificationOutcomeRecord.PASSED && evidence.adapterId == adapterId
}
