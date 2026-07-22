package eu.darken.amply.diagnostics.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.access.NamespaceSnapshot
import eu.darken.amply.charging.core.access.STANDARD_SETTINGS_NAMESPACES
import eu.darken.amply.charging.core.access.SettingsSnapshotSource
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.common.ca.CaString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stateless read-only helper for the contribution wizard. Holds **no** session state — the wizard ViewModel owns the
 * raw session and its lifecycle. An interface so the ViewModel can be unit-tested against a hand fake without Android.
 */
interface ContributionRepository {
    suspend fun status(): BackendStatus

    /**
     * Captures all three namespaces into one flat snapshot. Fails as a whole if any namespace fails — a partial
     * capture must never be treated as real state, or the next mode's diff would look like mass deletions.
     */
    suspend fun captureSnapshot(): CaptureResult

    /** Best-effort device identity + the currently selected adapter id, read together to avoid a double probe. */
    fun deviceContext(): DeviceContext
}

@Singleton
class DefaultContributionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: SettingsSnapshotSource,
    private val registry: AdapterRegistry,
) : ContributionRepository {

    override suspend fun status(): BackendStatus = source.status()

    override suspend fun captureSnapshot(): CaptureResult {
        val merged = LinkedHashMap<SettingId, String>()
        // Only the three AOSP namespaces — never the Lineage provider. It isn't a `settings` CLI
        // namespace, and querying it on a non-Lineage device would fail the whole capture.
        for (namespace in STANDARD_SETTINGS_NAMESPACES) {
            when (val result = source.snapshot(namespace)) {
                is NamespaceSnapshot.Success -> result.values.forEach { (key, value) ->
                    merged[SettingId(namespace, key)] = value
                }
                is NamespaceSnapshot.Failure -> return CaptureResult.Failure(result.reason)
            }
        }
        return CaptureResult.Success(merged)
    }

    override fun deviceContext(): DeviceContext {
        val device = DeviceInfo.current(context)
        return DeviceContext(device, registry.select(device).adapter?.id)
    }
}

data class DeviceContext(val device: DeviceInfo, val adapterId: String?)

sealed interface CaptureResult {
    data class Success(val snapshot: Map<SettingId, String>) : CaptureResult
    data class Failure(val reason: CaString) : CaptureResult
}
