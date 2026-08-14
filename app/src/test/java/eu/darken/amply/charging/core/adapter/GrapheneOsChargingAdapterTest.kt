package eu.darken.amply.charging.core.adapter

import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.SettingRead
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GrapheneOsChargingAdapterTest {
    private val adapter = GrapheneOsChargingAdapter()

    private fun graphene(
        packages: Boolean = true,
        key: Boolean = true,
        systemUser: Boolean = true,
    ) = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel 9 Pro XL",
        sdk = 37,
        fingerprint = "google/komodo/komodo:17/CP2A.260805.005/2026080501:user/release-keys",
        codename = "komodo",
        // The real GrapheneOS shape: no Settings-Intelligence controller.
        hasChargingOptimization = false,
        hasGrapheneOsPackages = packages,
        hasBatteryChargeLimit = key,
        isSystemUser = systemUser,
    )

    @Test
    fun `grapheneos identity with the key and system user enables control`() {
        val support = adapter.probe(graphene())

        support.matched shouldBe true
        support.controlEnabled shouldBe true
        support.detail shouldBe R.string.adapter_detail_grapheneos_ready
        support.contributionWanted shouldBe false
    }

    @Test
    fun `a stock pixel does not match even with the key present`() {
        // Identity is packages-only: this adapter precedes the live Pixel adapter, so a future
        // stock Pixel shipping a same-named key must fall through to its own adapter.
        val support = adapter.probe(graphene(packages = false))

        support.matched shouldBe false
        support.detail shouldBe R.string.adapter_detail_requires_grapheneos
    }

    @Test
    fun `the unprobeable key never gates control`() {
        // @Protected denies the unprivileged key probe on real GrapheneOS (hasBatteryChargeLimit is
        // always false there — verified via the issue-#49 beta report), so presence is assumed.
        val support = adapter.probe(graphene(key = false))

        support.matched shouldBe true
        support.controlEnabled shouldBe true
        support.detail shouldBe R.string.adapter_detail_grapheneos_ready
        support.contributionWanted shouldBe false
    }

    @Test
    fun `secondary user disables control`() {
        val support = adapter.probe(graphene(systemUser = false))

        support.matched shouldBe true
        support.controlEnabled shouldBe false
        support.detail shouldBe R.string.adapter_detail_secondary_user
    }

    @Test
    fun `read maps both values`() = runTest {
        adapter.read(backend("1")) shouldBe
            ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU)
        adapter.read(backend("0")) shouldBe
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
    }

    @Test
    fun `an absent key is the factory off state`() = runTest {
        // Upstream reads the key via BoolSetting(..., default false): a never-toggled device has no
        // row and charges unrestricted, so absence decodes as exactly that.
        adapter.read(FakeBackend()) shouldBe
            ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.SHIZUKU)
    }

    @Test
    fun `unrecognized values are flagged as such`() = runTest {
        val observed = adapter.read(backend("2"))

        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe true
    }

    @Test
    fun `unreadable state is unknown but not flagged unrecognized`() = runTest {
        val observed = adapter.read(FakeBackend(readable = false))

        observed.shouldBeInstanceOf<ChargeObservation.Unknown>()
        observed.unrecognizedValue shouldBe false
    }

    @Test
    fun `apply writes the single key`() = runTest {
        val backend = FakeBackend()

        adapter.apply(ChargePolicy.FixedLimit(80), backend) shouldBe true
        adapter.apply(ChargePolicy.Unrestricted, backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.GLOBAL, GrapheneOsChargingAdapter.KEY_CHARGE_LIMIT, "1"),
            SettingMutation(SettingNamespace.GLOBAL, GrapheneOsChargingAdapter.KEY_CHARGE_LIMIT, "0"),
        )
    }

    @Test
    fun `apply rejects unsupported policies without writing`() = runTest {
        val backend = FakeBackend()

        adapter.apply(ChargePolicy.Adaptive, backend) shouldBe false
        adapter.apply(ChargePolicy.PauseAtFull, backend) shouldBe false
        adapter.apply(ChargePolicy.FixedLimit(85), backend) shouldBe false

        backend.writes shouldBe emptyList()
    }

    @Test
    fun `apply fails on rejected or dropped writes`() = runTest {
        adapter.apply(ChargePolicy.FixedLimit(80), FakeBackend(failWrites = true)) shouldBe false
        // Write reports success but the value never lands: read-back equality must fail.
        adapter.apply(ChargePolicy.FixedLimit(80), FakeBackend(dropWrites = true)) shouldBe false
    }

    @Test
    fun `reapply is a plain apply`() = runTest {
        // No observer-poke inversion: the ROM samples the key at plug-session start, so a forced
        // re-write must not transiently flip the value the way the Pixel adapter does.
        val backend = backend("1")

        adapter.reapply(ChargePolicy.FixedLimit(80), backend) shouldBe true

        backend.writes shouldContainExactly listOf(
            SettingMutation(SettingNamespace.GLOBAL, GrapheneOsChargingAdapter.KEY_CHARGE_LIMIT, "1"),
        )
    }

    @Test
    fun `decode maps the pixel-style hardware states`() {
        adapter.decodeHardware(chargingState = 4, plugged = true) shouldBe ChargeObservation.Verified(
            ChargePolicy.FixedLimit(80),
            BackendKind.BATTERY_HARDWARE,
        )
        // Unplugged the sticky broadcast retains its last powered value — never evidence.
        adapter.decodeHardware(chargingState = 4, plugged = false) shouldBe null
        adapter.decodeHardware(chargingState = 1, plugged = true)
            .shouldBeInstanceOf<ChargeObservation.Unknown>()
        // Adaptive (5) is not a policy this adapter supports; it must never verify.
        adapter.decodeHardware(chargingState = 5, plugged = true)
            .shouldBeInstanceOf<ChargeObservation.Unknown>()
    }

    @Test
    fun `session capabilities`() {
        adapter.id shouldBe "grapheneos-chargelimit-v1"
        adapter.sessionOverridePolicy shouldBe ChargePolicy.Unrestricted
        adapter.defaultProtectivePolicy shouldBe ChargePolicy.FixedLimit(80)
        adapter.verification shouldBe VerificationStrategy.SYNC_READBACK
        adapter.policyLatchesAtPlug shouldBe true
        adapter.reconnectGestureSupported shouldBe false
        // Not a namespace need: the key is @Protected and only the shell UID (Shizuku) may touch it.
        adapter.preferShizukuForWrites shouldBe true
    }

    private fun backend(value: String) =
        FakeBackend(values = mutableMapOf(GrapheneOsChargingAdapter.KEY_CHARGE_LIMIT to value))

    private class FakeBackend(
        private val readable: Boolean = true,
        private val values: MutableMap<String, String> = mutableMapOf(),
        private val failWrites: Boolean = false,
        private val dropWrites: Boolean = false,
    ) : AccessBackend {
        // The realistic provenance on GrapheneOS: only the shell UID (Shizuku) can read the key.
        override val kind = BackendKind.SHIZUKU
        val writes = mutableListOf<SettingMutation>()

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) =
            SettingRead(readable, values[key], if (readable) null else "blocked".toCaString())

        override suspend fun write(mutation: SettingMutation): Boolean {
            if (failWrites) return false
            writes += mutation
            if (!dropWrites) values[mutation.key] = mutation.value
            return true
        }
    }
}
