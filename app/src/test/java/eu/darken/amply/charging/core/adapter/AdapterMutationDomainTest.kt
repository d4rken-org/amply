package eu.darken.amply.charging.core.adapter

import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.access.AccessBackend
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.SettingMutation
import eu.darken.amply.charging.core.access.SettingNamespace
import eu.darken.amply.charging.core.access.shizuku.SettingWritePolicy
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.collections.shouldNotBeEmpty
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Invariant: every mutation any live adapter can emit for any of its supported policies must be
 * admitted by the privileged boundary's per-key value domains. A domain omission would otherwise
 * only surface as a runtime Shizuku write failure on-device.
 */
class AdapterMutationDomainTest {

    private val adapters = listOf(
        PixelChargingAdapter(),
        SamsungModernChargingAdapter(),
        SamsungLegacyChargingAdapter(),
        XiaomiChargingAdapter(),
    )

    @Test
    fun `every adapter-emitted mutation is inside the privileged write domains`() = runTest {
        adapters.forEach { adapter ->
            adapter.supportedPolicies.forEach { policy ->
                val backend = CollectingBackend()
                adapter.apply(policy, backend)
                backend.writes.shouldNotBeEmpty()
                backend.writes.forEach { mutation ->
                    // Throws IllegalArgumentException (failing the test) if out of domain.
                    SettingWritePolicy.validate(mutation.namespace.commandName, mutation.key, mutation.value)
                }
            }
        }
    }

    @Test
    fun `every reapply-emitted mutation is inside the privileged write domains`() = runTest {
        adapters.forEach { adapter ->
            adapter.supportedPolicies.forEach { policy ->
                val backend = CollectingBackend()
                adapter.reapply(policy, backend)
                backend.writes.forEach { mutation ->
                    SettingWritePolicy.validate(mutation.namespace.commandName, mutation.key, mutation.value)
                }
            }
        }
    }

    private class CollectingBackend : AccessBackend {
        override val kind = BackendKind.SHIZUKU
        val writes = mutableListOf<SettingMutation>()
        private val values = mutableMapOf<String, String>()

        override suspend fun status() = BackendStatus(true, true, "test".toCaString())
        override suspend fun read(namespace: SettingNamespace, key: String) =
            eu.darken.amply.charging.core.access.SettingRead(true, values[key], null)

        override suspend fun write(mutation: SettingMutation): Boolean {
            writes += mutation
            values[mutation.key] = mutation.value
            return true
        }

        override suspend fun snapshot(namespace: SettingNamespace) = emptyMap<String, String>()
    }
}
