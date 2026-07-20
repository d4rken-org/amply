package eu.darken.amply.charging.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.charging.core.access.AccessResolver
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.shizuku.ShizukuController
import eu.darken.amply.charging.core.adapter.AdapterRegistry
import eu.darken.amply.charging.core.adapter.AdapterSelection
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class ChargingState(
    val device: DeviceInfo = DeviceInfo.current(),
    val adapterName: String = "Detecting…",
    val adapterId: String? = null,
    val adapterDetail: String = "",
    val controlEnabled: Boolean = false,
    val contributionWanted: Boolean = false,
    val access: AccessSnapshot? = null,
    val observation: ChargeObservation = ChargeObservation.Unknown("Loading"),
    val busy: Boolean = false,
    val message: String? = null,
)

@Singleton
class ChargingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: AdapterRegistry,
    private val accessResolver: AccessResolver,
    private val preferences: ChargingPreferences,
    private val shizukuController: ShizukuController,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(ChargingState())
    val state: StateFlow<ChargingState> = mutableState.asStateFlow()

    suspend fun refresh(message: String? = null): ChargingState = operationMutex.withLock {
        refreshLocked(message)
    }

    suspend fun applyPersistent(policy: ChargePolicy): ApplyResult = operationMutex.withLock {
        applyLocked(policy, updateProtective = policy != ChargePolicy.Unrestricted)
    }

    suspend fun applyTemporary(policy: ChargePolicy): ApplyResult = operationMutex.withLock {
        applyLocked(policy, updateProtective = false)
    }

    /** Re-write that forces a real settings change so a missed observer registration is re-triggered. */
    suspend fun reapplyPersistent(policy: ChargePolicy): ApplyResult = operationMutex.withLock {
        applyLocked(policy, updateProtective = policy != ChargePolicy.Unrestricted, forceNotify = true)
    }

    suspend fun requestShizukuPermission(): Boolean {
        val result = runCatching { shizukuController.requestPermission() }.getOrDefault(false)
        refresh(if (result) "Shizuku permission granted" else "Shizuku permission was not granted")
        return result
    }

    suspend fun grantWriteSecureSettings(): Boolean {
        val result = runCatching { shizukuController.grantWriteSecureSettings() }.getOrDefault(false)
        refresh(if (result) "WRITE_SECURE_SETTINGS granted" else "Could not grant WRITE_SECURE_SETTINGS")
        return result
    }

    fun nativeSettingsIntent() = registry.select().adapter?.nativeSettingsIntent(context)

    fun shizukuManagerPackage(): String? = shizukuController.managerPackage()

    private suspend fun applyLocked(
        policy: ChargePolicy,
        updateProtective: Boolean,
        forceNotify: Boolean = false,
    ): ApplyResult {
        log(TAG, Logging.Priority.INFO) {
            "apply(policy=${policy.stableId}, persistent=$updateProtective, forceNotify=$forceNotify)"
        }
        val selection = registry.select()
        val adapter = selection.adapter
        if (adapter == null || !selection.support.controlEnabled) {
            val observation = ChargeObservation.Unsupported(selection.support.detail)
            mutableState.value = state.value.copy(observation = observation, message = selection.support.detail)
            return ApplyResult(false, observation, selection.support.detail)
        }
        if (policy !in adapter.supportedPolicies) {
            val observation = ChargeObservation.Unsupported("${policy.label} is not supported by ${adapter.displayName}")
            return ApplyResult(false, observation, "Unsupported policy")
        }
        val backend = accessResolver.writeBackend()
        if (backend == null) {
            val observation = ChargeObservation.NeedsSetup("Grant WSS or connect Shizuku")
            mutableState.value = state.value.copy(observation = observation, message = "Setup required")
            return ApplyResult(false, observation, "Setup required")
        }

        mutableState.value = state.value.copy(busy = true, message = "Applying ${policy.label}…")
        val written = try {
            if (forceNotify) adapter.reapply(policy, backend) else adapter.apply(policy, backend)
        } catch (e: CancellationException) {
            // A cancelled write must not run failure side effects (state churn, notifications).
            throw e
        } catch (e: Exception) {
            false
        }
        if (!written) {
            log(TAG, Logging.Priority.ERROR) { "Settings write failed for ${policy.stableId}" }
            val observation = ChargeObservation.Unknown("The settings write failed")
            mutableState.value = state.value.copy(busy = false, observation = observation, message = "Write failed")
            return ApplyResult(false, observation, "Write failed")
        }
        preferences.recordRequested(policy, updateProtective)

        val access = accessResolver.snapshot()
        val observation = if (access.shizuku.ready) {
            val read = adapter.read(accessResolver.shizuku)
            when (read) {
                is ChargeObservation.Verified -> read
                else -> ChargeObservation.LastRequested(policy)
            }
        } else {
            ChargeObservation.LastRequested(policy)
        }
        mutableState.value = state.value.copy(
            busy = false,
            access = access,
            observation = observation,
            message = if (observation is ChargeObservation.Verified) {
                "${policy.label} setting verified; charging hardware may take about 15 seconds"
            } else {
                "${policy.label} requested; charging hardware may take about 15 seconds"
            },
        )
        return ApplyResult(true, observation, mutableState.value.message.orEmpty())
            .also { log(TAG, Logging.Priority.INFO) { "Applied ${policy.stableId}: $observation" } }
    }

    private suspend fun refreshLocked(message: String?): ChargingState {
        val selection: AdapterSelection = registry.select()
        val access = accessResolver.snapshot()
        val adapter = selection.adapter
        val observation = when {
            adapter == null -> ChargeObservation.Unsupported(selection.support.detail)
            !selection.support.controlEnabled -> ChargeObservation.Unsupported(selection.support.detail)
            !access.canControl -> ChargeObservation.NeedsSetup("Grant WSS or connect Shizuku")
            else -> {
                val backend = accessResolver.readBackend()
                val read = if (backend != null) adapter.read(backend) else null
                if (read is ChargeObservation.Verified) {
                    read
                } else {
                    adapter.readHardware(context)
                        ?: preferences.lastRequestedNow()?.let(ChargeObservation::LastRequested)
                        ?: read
                        ?: ChargeObservation.Unknown("State unavailable")
                }
            }
        }
        return ChargingState(
            device = DeviceInfo.current(context),
            adapterName = adapter?.displayName ?: "Unsupported device",
            adapterId = adapter?.id,
            adapterDetail = selection.support.detail,
            controlEnabled = selection.support.controlEnabled,
            contributionWanted = selection.support.contributionWanted,
            access = access,
            observation = observation,
            busy = false,
            message = message,
        ).also {
            mutableState.value = it
            log(TAG, Logging.Priority.VERBOSE) {
                "refresh(adapter=${it.adapterId}, access=${it.access?.label}, observation=${it.observation})"
            }
        }
    }

    private companion object {
        val TAG = logTag("Charging", "Repository")
    }
}
