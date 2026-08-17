package eu.darken.amply.charging.core

import android.content.Context
import android.content.Intent
import eu.darken.amply.battery.core.BatteryReadout
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.SettingRead
import eu.darken.amply.charging.core.adapter.ChargingAdapter
import eu.darken.amply.charging.core.adapter.VerificationStrategy
import eu.darken.amply.common.ca.toCaString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChargingSyncReadTest {

    private val target = ChargePolicy.FixedLimit(80)
    private val other = ChargePolicy.Unrestricted
    private val t0 = 1_000_000L

    private companion object {
        // android.os.BatteryManager.BATTERY_STATUS_* — inlined so this stays a plain JVM test.
        const val STATUS_CHARGING = 2
        const val STATUS_NOT_CHARGING = 4
        const val STATUS_FULL = 5
    }

    private fun verified(policy: ChargePolicy, backend: BackendKind) = ChargeObservation.Verified(policy, backend)
    private fun unrecognized() = ChargeObservation.Unknown("weird".toCaString(), unrecognizedValue = true)
    private fun generic() = ChargeObservation.Unknown("unreadable".toCaString())

    // --- chooseSyncObservation precedence: Verified > unrecognized > generic; primary (direct) wins ties ---

    @Test
    fun `primary verified always wins`() {
        val p = verified(target, BackendKind.DIRECT_WSS)
        chooseSyncObservation(p, verified(other, BackendKind.SHIZUKU)) shouldBeSameInstanceAs p
    }

    @Test
    fun `fallback verified wins over any non-verified primary`() {
        val f = verified(target, BackendKind.SHIZUKU)
        chooseSyncObservation(generic(), f) shouldBeSameInstanceAs f
        chooseSyncObservation(unrecognized(), f) shouldBeSameInstanceAs f
    }

    @Test
    fun `unrecognized primary survives over a generic fallback`() {
        val p = unrecognized()
        chooseSyncObservation(p, generic()) shouldBeSameInstanceAs p
    }

    @Test
    fun `unrecognized fallback used when primary is generic`() {
        val f = unrecognized()
        chooseSyncObservation(generic(), f) shouldBeSameInstanceAs f
    }

    @Test
    fun `both null is null`() {
        chooseSyncObservation(null, null) shouldBe null
    }

    // --- readSyncDirectFirst: direct-first, Shizuku only as fallback (the latency fix) ---

    @Test
    fun `direct verified short-circuits without ever touching shizuku`() = runTest {
        val direct = FakeBackend(BackendKind.DIRECT_WSS)
        val shizuku = FakeBackend(BackendKind.SHIZUKU)
        val adapter = FakeSyncAdapter(mapOf(direct to { verified(target, BackendKind.DIRECT_WSS) }))

        readSyncDirectFirst(adapter, direct, shizuku) shouldBe verified(target, BackendKind.DIRECT_WSS)
        // The whole point of the fix: a cold/stalled Shizuku bind is never on the read path.
        adapter.readBackends shouldBe listOf(direct)
    }

    @Test
    fun `falls back to shizuku only after a non-verified direct read`() = runTest {
        val direct = FakeBackend(BackendKind.DIRECT_WSS)
        val shizuku = FakeBackend(BackendKind.SHIZUKU)
        val adapter = FakeSyncAdapter(
            mapOf(
                direct to { generic() },
                shizuku to { verified(target, BackendKind.SHIZUKU) },
            ),
        )

        readSyncDirectFirst(adapter, direct, shizuku) shouldBe verified(target, BackendKind.SHIZUKU)
        adapter.readBackends shouldBe listOf(direct, shizuku)
    }

    @Test
    fun `direct unrecognized value short-circuits without touching shizuku`() = runTest {
        val direct = FakeBackend(BackendKind.DIRECT_WSS)
        val shizuku = FakeBackend(BackendKind.SHIZUKU)
        val adapter = FakeSyncAdapter(mapOf(direct to { unrecognized() }))

        val result = readSyncDirectFirst(adapter, direct, shizuku)
        (result as ChargeObservation.Unknown).unrecognizedValue shouldBe true
        // A readable-but-unrecognized value is authoritative; Shizuku reads the same provider and can't do
        // better, so it must not be bound (that is the ~15s stall we are avoiding).
        adapter.readBackends shouldBe listOf(direct)
    }

    @Test
    fun `null shizuku returns the direct read as-is`() = runTest {
        val direct = FakeBackend(BackendKind.DIRECT_WSS)
        val adapter = FakeSyncAdapter(mapOf(direct to { generic() }))

        val result = readSyncDirectFirst(adapter, direct, null)
        (result as ChargeObservation.Unknown).unrecognizedValue shouldBe false
    }

    @Test
    fun `an ordinary direct read failure falls through to shizuku`() = runTest {
        val direct = FakeBackend(BackendKind.DIRECT_WSS)
        val shizuku = FakeBackend(BackendKind.SHIZUKU)
        val adapter = FakeSyncAdapter(
            mapOf(
                direct to { throw IllegalStateException("boom") },
                shizuku to { verified(target, BackendKind.SHIZUKU) },
            ),
        )

        readSyncDirectFirst(adapter, direct, shizuku) shouldBe verified(target, BackendKind.SHIZUKU)
    }

    @Test
    fun `cancellation from a read propagates and is never swallowed`() = runTest {
        val direct = FakeBackend(BackendKind.DIRECT_WSS)
        val adapter = FakeSyncAdapter(mapOf(direct to { throw CancellationException() }))

        shouldThrow<CancellationException> { readSyncDirectFirst(adapter, direct, null) }
    }

    // --- computeRefreshPending: SYNC_READBACK clears on any authoritative read ---

    private fun sync(observation: ChargeObservation, now: Long = t0 + 5_000) = computeRefreshPending(
        reqPolicy = target,
        reqAt = t0,
        now = now,
        observation = observation,
        hardware = null,
        verification = VerificationStrategy.SYNC_READBACK,
    )

    @Test
    fun `sync verified matching clears pending`() {
        sync(verified(target, BackendKind.DIRECT_WSS)) shouldBe null
    }

    @Test
    fun `sync verified for a different policy also clears pending`() {
        // A different verified value is a native/competing change that already took effect — not a
        // mid-transition artifact — so the stale request must clear immediately, not linger 15s.
        sync(verified(other, BackendKind.DIRECT_WSS)) shouldBe null
    }

    @Test
    fun `sync readable-but-unrecognized value clears pending`() {
        sync(unrecognized()) shouldBe null
    }

    @Test
    fun `sync generic-unknown keeps pending until the window expires`() {
        sync(generic()) shouldBe PendingRequest(target, t0)
        sync(generic(), now = t0 + SETTLING_WINDOW_MILLIS) shouldBe null
    }

    @Test
    fun `sync verified adaptive clears pending like any other policy`() {
        // Anti-leak guard. Adaptive's enforcement is conditional, so the UI refuses to claim active
        // protection for it — but pending asks "did the write land", which the readback answers.
        // Adopting the presentation predicate here would spin every Xiaomi adaptive apply for the
        // full settling window, on that adapter's own protective default.
        computeRefreshPending(
            reqPolicy = ChargePolicy.Adaptive,
            reqAt = t0,
            now = t0 + 5_000,
            observation = verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU),
            hardware = null,
            verification = VerificationStrategy.SYNC_READBACK,
        ) shouldBe null
    }

    // --- computeRefreshPending: ASYNC_HARDWARE behavior is unchanged ---

    private fun async(observation: ChargeObservation, hardware: ChargeObservation?) = computeRefreshPending(
        reqPolicy = target,
        reqAt = t0,
        now = t0 + 5_000,
        observation = observation,
        hardware = hardware,
        verification = VerificationStrategy.ASYNC_HARDWARE,
    )

    @Test
    fun `async clears only on matching hardware verification`() {
        async(verified(target, BackendKind.SHIZUKU), verified(target, BackendKind.BATTERY_HARDWARE)) shouldBe null
    }

    @Test
    fun `async keeps pending when hardware still shows a different policy mid-transition`() {
        async(verified(target, BackendKind.SHIZUKU), verified(other, BackendKind.BATTERY_HARDWARE)) shouldBe
            PendingRequest(target, t0)
    }

    @Test
    fun `async ignores settings-only verification without a hardware signal`() {
        async(verified(target, BackendKind.SHIZUKU), null) shouldBe PendingRequest(target, t0)
    }

    // --- computeRefreshPending: plug-latched condition (no clock, resolves only on evidence) ---

    private fun latched(
        reqPolicy: ChargePolicy = other, // Unrestricted: the direction with no hardware confirmation
        reqPlugged: Boolean? = true,
        unpluggedSeenAt: Long = 0L,
        battery: BatteryReadout? = null,
        hardware: ChargeObservation? = null,
        now: Long = t0 + 5_000,
        limitPercent: Int = 80,
        observation: ChargeObservation = verified(reqPolicy, BackendKind.DIRECT_WSS), // matching config
    ) = computeRefreshPending(
        reqPolicy = reqPolicy,
        reqAt = t0,
        now = now,
        observation = observation,
        hardware = hardware,
        verification = VerificationStrategy.SYNC_READBACK,
        policyLatchesAtPlug = true,
        reqPlugged = reqPlugged,
        unpluggedSeenAt = unpluggedSeenAt,
        battery = battery,
        limitPercent = limitPercent,
    )

    private fun pluggedBattery(percent: Int? = null, status: Int? = null) =
        BatteryReadout(levelPercent = percent, status = status, plugged = 2 /* USB */)

    @Test
    fun `latched written while plugged stays pending despite a verified readback`() {
        latched() shouldBe PendingRequest(other, t0, awaitingReplug = true)
    }

    @Test
    fun `latched pending has no expiry`() {
        latched(now = t0 + SETTLING_WINDOW_MILLIS * 100) shouldBe PendingRequest(other, t0, awaitingReplug = true)
    }

    @Test
    fun `latched written unplugged is settled immediately`() {
        latched(reqPlugged = false) shouldBe null
    }

    @Test
    fun `latched unknown plug state at write is treated as plugged`() {
        latched(reqPlugged = null) shouldBe PendingRequest(other, t0, awaitingReplug = true)
    }

    @Test
    fun `latched resolves once an unplug was observed after the write`() {
        latched(unpluggedSeenAt = t0 + 1) shouldBe null
    }

    @Test
    fun `latched watermark at exactly the request time does not resolve`() {
        latched(unpluggedSeenAt = t0) shouldBe PendingRequest(other, t0, awaitingReplug = true)
    }

    @Test
    fun `latched resolves on live unpowered evidence`() {
        latched(battery = BatteryReadout(plugged = 0)) shouldBe null
    }

    @Test
    fun `latched unreported plug state is not unpowered evidence`() {
        latched(battery = BatteryReadout(plugged = null)) shouldBe PendingRequest(other, t0, awaitingReplug = true)
    }

    @Test
    fun `latched limit target resolves on matching hardware evidence`() {
        latched(reqPolicy = target, hardware = verified(target, BackendKind.BATTERY_HARDWARE)) shouldBe null
    }

    @Test
    fun `latched settings-level verification is not hardware evidence`() {
        latched(reqPolicy = target, hardware = verified(target, BackendKind.SHIZUKU)) shouldBe
            PendingRequest(target, t0, awaitingReplug = true)
    }

    @Test
    fun `latched unrestricted resolves once charging above the cap`() {
        latched(battery = pluggedBattery(percent = 81, status = STATUS_CHARGING)) shouldBe null
        latched(battery = pluggedBattery(percent = 100, status = STATUS_FULL)) shouldBe null
    }

    @Test
    fun `latched unrestricted charging at or below the cap is ambiguous and stays pending`() {
        // A latched limit also reads "charging" while still climbing toward the cap.
        latched(battery = pluggedBattery(percent = 80, status = STATUS_CHARGING)) shouldBe
            PendingRequest(other, t0, awaitingReplug = true)
    }

    @Test
    fun `latched unrestricted held above the cap without charging stays pending`() {
        // NOT_CHARGING above the cap proves nothing about THIS request; only active charging does.
        latched(battery = pluggedBattery(percent = 85, status = STATUS_NOT_CHARGING)) shouldBe
            PendingRequest(other, t0, awaitingReplug = true)
    }

    @Test
    fun `latched limit-disproof never applies to a limit target`() {
        latched(reqPolicy = target, battery = pluggedBattery(percent = 90, status = STATUS_CHARGING)) shouldBe
            PendingRequest(target, t0, awaitingReplug = true)
    }

    @Test
    fun `latched backwards clock keeps pending rather than claiming applied`() {
        latched(now = t0 - 1) shouldBe PendingRequest(other, t0, awaitingReplug = true)
    }

    @Test
    fun `latched pending clears when a competing native change is configured`() {
        // The native toggle applies live on these ROMs: a readback verifying a DIFFERENT policy
        // means the request is obsolete and must not keep demanding a replug.
        latched(observation = verified(target, BackendKind.DIRECT_WSS)) shouldBe null
    }

    @Test
    fun `latched pending clears on an unrecognized configured value`() {
        latched(observation = unrecognized()) shouldBe null
    }

    @Test
    fun `latched matching readback never resolves on its own`() {
        // Configuration is exactly what a matching readback proves — and exactly not enough.
        latched(observation = verified(other, BackendKind.SHIZUKU)) shouldBe
            PendingRequest(other, t0, awaitingReplug = true)
    }

    // --- computeUnconfirmedTarget: expected-but-missing hardware confirmation ---

    private fun unconfirmed(
        now: Long = t0 + UNCONFIRMED_THRESHOLD_MILLIS,
        reqPolicy: ChargePolicy? = target,
        reqAt: Long = t0,
        observation: ChargeObservation = ChargeObservation.LastRequested(target),
        hardware: ChargeObservation? = null,
        expected: Boolean = true,
    ) = computeUnconfirmedTarget(
        reqPolicy = reqPolicy,
        reqAt = reqAt,
        now = now,
        observation = observation,
        hardware = hardware,
        confirmationExpected = expected,
    )

    @Test
    fun `an expected but missing confirmation surfaces the target after the threshold`() {
        unconfirmed() shouldBe target
    }


    @Test
    fun `inside the grace threshold nothing surfaces`() {
        unconfirmed(now = t0 + UNCONFIRMED_THRESHOLD_MILLIS - 1) shouldBe null
    }

    @Test
    fun `a backwards clock never surfaces a warning`() {
        unconfirmed(now = t0 - 1) shouldBe null
    }

    @Test
    fun `a matching hardware confirmation clears the warning`() {
        unconfirmed(hardware = verified(target, BackendKind.BATTERY_HARDWARE)) shouldBe null
    }

    @Test
    fun `hardware verifying a different policy still warns while the journal stands`() {
        // WSS-only shape: the observation is only the last request, and state 5 while a fixed limit
        // was requested means something else engaged adaptive — a real contradiction.
        unconfirmed(hardware = verified(ChargePolicy.Adaptive, BackendKind.BATTERY_HARDWARE)) shouldBe target
    }

    @Test
    fun `an authoritative readback of a different policy obsoletes the request`() {
        // Shizuku shape: the settings verifiably hold another policy — a native/competing change
        // replaced the request, and warning about the obsolete one would contradict the card title.
        unconfirmed(
            observation = verified(ChargePolicy.Adaptive, BackendKind.SHIZUKU),
            hardware = verified(ChargePolicy.Adaptive, BackendKind.BATTERY_HARDWARE),
        ) shouldBe null
    }

    @Test
    fun `a readback verifying the requested policy with disagreeing hardware is the intended warning`() {
        unconfirmed(observation = verified(target, BackendKind.SHIZUKU), hardware = null) shouldBe target
    }

    @Test
    fun `an unrecognized configured value never warns`() {
        unconfirmed(observation = unrecognized()) shouldBe null
    }

    @Test
    fun `settings-level verification is not hardware confirmation`() {
        unconfirmed(hardware = verified(target, BackendKind.SHIZUKU)) shouldBe target
    }

    @Test
    fun `no expectation means no warning`() {
        unconfirmed(expected = false) shouldBe null
    }

    @Test
    fun `unsupported and setup states never warn`() {
        unconfirmed(observation = ChargeObservation.Unsupported("n/a".toCaString())) shouldBe null
        unconfirmed(observation = ChargeObservation.NeedsSetup("n/a".toCaString())) shouldBe null
    }

    @Test
    fun `no recorded request never warns`() {
        unconfirmed(reqPolicy = null) shouldBe null
        unconfirmed(reqAt = 0L) shouldBe null
    }

    @Test
    fun `the threshold exceeds the settling window`() {
        // The doc contract that lets the detector ignore pending entirely.
        (UNCONFIRMED_THRESHOLD_MILLIS > SETTLING_WINDOW_MILLIS) shouldBe true
    }

    // --- debounceUnconfirmed: the contradiction must hold stable before it surfaces ---

    @Test
    fun `a fresh candidate is held back until it proves stable`() {
        val first = debounceUnconfirmed(target, now = t0, prevCandidate = null, prevSince = 0L)
        first.surfaced shouldBe null
        first.candidate shouldBe target

        debounceUnconfirmed(
            target,
            now = t0 + UNCONFIRMED_STABILITY_MILLIS,
            prevCandidate = first.candidate,
            prevSince = first.sinceMillis,
        ).surfaced shouldBe target
    }

    @Test
    fun `a transient candidate that clears never surfaces`() {
        // The plug-in transient: state 1 for the ~12s HAL transition, then state 4 clears it.
        val first = debounceUnconfirmed(target, now = t0, prevCandidate = null, prevSince = 0L)
        val cleared = debounceUnconfirmed(
            null,
            now = t0 + 12_000,
            prevCandidate = first.candidate,
            prevSince = first.sinceMillis,
        )
        cleared.surfaced shouldBe null
        cleared.candidate shouldBe null

        // A re-appearing candidate starts a fresh stability interval.
        debounceUnconfirmed(
            target,
            now = t0 + 13_000,
            prevCandidate = cleared.candidate,
            prevSince = cleared.sinceMillis,
        ).surfaced shouldBe null
    }

    @Test
    fun `a changed candidate restarts the stability interval`() {
        val first = debounceUnconfirmed(target, now = t0, prevCandidate = null, prevSince = 0L)
        debounceUnconfirmed(
            ChargePolicy.FixedLimit(90),
            now = t0 + UNCONFIRMED_STABILITY_MILLIS,
            prevCandidate = first.candidate,
            prevSince = first.sinceMillis,
        ).surfaced shouldBe null
    }

    @Test
    fun `a backwards clock restarts rather than surfacing early`() {
        val first = debounceUnconfirmed(target, now = t0, prevCandidate = null, prevSince = 0L)
        debounceUnconfirmed(
            target,
            now = t0 - 1,
            prevCandidate = first.candidate,
            prevSince = first.sinceMillis,
        ).surfaced shouldBe null
    }

    private class FakeBackend(override val kind: BackendKind) : AccessBackend {
        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) = SettingRead(readable = false)
        override suspend fun write(mutation: SettingMutation) = false
    }

    private class FakeSyncAdapter(
        private val reads: Map<AccessBackend, suspend () -> ChargeObservation>,
    ) : ChargingAdapter {
        val readBackends = mutableListOf<AccessBackend>()
        override val id = "fake-sync"
        override val displayName = "Fake".toCaString()
        override val supportedPolicies = emptyList<ChargePolicy>()
        override val verification = VerificationStrategy.SYNC_READBACK

        override suspend fun read(backend: AccessBackend): ChargeObservation {
            readBackends += backend
            return (reads[backend] ?: error("unexpected backend $backend")).invoke()
        }

        override fun probe(device: DeviceInfo) = error("unused")
        override suspend fun apply(policy: ChargePolicy, backend: AccessBackend) = false
        override fun nativeSettingsIntent(context: Context): Intent = error("unused")
    }
}
