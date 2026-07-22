package eu.darken.amply.charging.core

import android.content.Context
import android.content.Intent
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
