package eu.darken.amply.main.ui.dashboard

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SessionPresentationTest {

    private val session = ChargeSessionRecord(
        restorePolicy = ChargePolicy.FixedLimit(80),
        startedAtMillis = 1_000L,
        connectedSeen = true,
    )
    private val reason = "reason".toCaString()

    @Test
    fun `no record is NONE regardless of observation`() {
        listOf(
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.BATTERY_HARDWARE),
            ChargeObservation.LastRequested(ChargePolicy.FixedLimit(80)),
            ChargeObservation.Unknown(reason),
        ).forEach { observation ->
            SessionPresentation.from(null, observation) shouldBe SessionPresentation.NONE
        }
    }

    @Test
    fun `full-charge policies confirm the override`() {
        listOf(
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.BATTERY_HARDWARE),
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU),
            ChargeObservation.LastRequested(ChargePolicy.Unrestricted),
            // Samsung's session override reaches 100% via PauseAtFull.
            ChargeObservation.Verified(ChargePolicy.PauseAtFull, BackendKind.DIRECT_WSS),
            ChargeObservation.LastRequested(ChargePolicy.PauseAtFull),
            // A 100% "limit" reaches full too.
            ChargeObservation.Verified(ChargePolicy.FixedLimit(100), BackendKind.SHIZUKU),
        ).forEach { observation ->
            SessionPresentation.from(session, observation) shouldBe SessionPresentation.ACTIVE
        }
    }

    @Test
    fun `capped or unconfirmed observations stay RECORDED`() {
        listOf(
            // Restore already landed (or an external change) while the record lingers.
            ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.BATTERY_HARDWARE),
            ChargeObservation.LastRequested(ChargePolicy.FixedLimit(80)),
            ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU),
            // Override write failed and the record is kept for recovery.
            ChargeObservation.Unknown(reason),
            ChargeObservation.NeedsSetup(reason),
            ChargeObservation.Unsupported(reason),
        ).forEach { observation ->
            SessionPresentation.from(session, observation) shouldBe SessionPresentation.RECORDED
        }
    }

    @Test
    fun `selector shows the restore policy during a session`() {
        listOf(
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.BATTERY_HARDWARE),
            ChargeObservation.LastRequested(ChargePolicy.Unrestricted),
            ChargeObservation.Unknown(reason),
        ).forEach { observation ->
            selectedPolicyFor(session, observation) shouldBe ChargePolicy.FixedLimit(80)
        }
    }

    @Test
    fun `selector follows the observation without a session`() {
        selectedPolicyFor(null, ChargeObservation.Verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU)) shouldBe
            ChargePolicy.Adaptive
        selectedPolicyFor(null, ChargeObservation.LastRequested(ChargePolicy.PauseAtFull)) shouldBe
            ChargePolicy.PauseAtFull
        selectedPolicyFor(null, ChargeObservation.Unknown(reason)) shouldBe null
        selectedPolicyFor(null, ChargeObservation.NeedsSetup(reason)) shouldBe null
    }

    @Test
    fun `selector may return a policy without a matching choice`() {
        // A restore policy outside the adapter's current choices (e.g. Samsung's 90% on a Pixel
        // selector) simply highlights nothing — documented fallback, not an error.
        val record = session.copy(restorePolicy = ChargePolicy.FixedLimit(90))
        selectedPolicyFor(record, ChargeObservation.LastRequested(ChargePolicy.Unrestricted)) shouldBe
            ChargePolicy.FixedLimit(90)
    }
}
