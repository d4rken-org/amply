package eu.darken.amply.charging.core.access.shizuku

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

@Keep
class ChargingControlUserService(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : IChargingControlService.Stub() {

    override fun readSetting(namespace: String, key: String): String? {
        requireNamespace(namespace)
        requireKey(key)
        val result = runCommand("/system/bin/settings", "get", namespace, key)
        if (result.exitCode != 0) throw IllegalStateException(result.stderr.ifBlank { "settings get failed" })
        return result.stdout.trim().takeUnless { it == "null" || it.isEmpty() }
    }

    override fun writeSetting(namespace: String, key: String, value: String): Boolean {
        requireNamespace(namespace)
        requireKey(key)
        SettingWritePolicy.validate(namespace, key, value)
        return runCommand("/system/bin/settings", "put", namespace, key, value).exitCode == 0
    }

    override fun grantWriteSecureSettings(packageName: String): Boolean {
        require(PACKAGE.matches(packageName)) { "Invalid package name" }
        return runCommand(
            "/system/bin/pm",
            "grant",
            packageName,
            "android.permission.WRITE_SECURE_SETTINGS",
        ).exitCode == 0
    }

    override fun snapshotSettings(namespace: String): String {
        requireNamespace(namespace)
        val result = runCommand("/system/bin/settings", "list", namespace)
        if (result.exitCode != 0) throw IllegalStateException(result.stderr.ifBlank { "settings list failed" })
        return result.stdout
    }

    override fun destroy() = exitProcess(0)

    private fun requireNamespace(namespace: String) {
        require(namespace in NAMESPACES) { "Unsupported settings namespace" }
    }

    private fun requireKey(key: String) {
        require(KEY.matches(key)) { "Invalid settings key" }
    }

    private fun runCommand(vararg args: String): CommandResult {
        val process = ProcessBuilder(*args).redirectErrorStream(false).start()
        check(process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Command timed out"
        }
        return CommandResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().use { it.readText() },
            stderr = process.errorStream.bufferedReader().use { it.readText() },
        )
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    companion object {
        private val NAMESPACES = setOf("secure", "global", "system")
        private val KEY = Regex("[A-Za-z0-9_.:-]{1,160}")
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_.]{2,200}")
    }
}

/**
 * Pure validation for the privileged write path. Every writable setting carries an explicit
 * per-key value domain — the boundary itself rejects out-of-domain values instead of trusting
 * the adapter layer. Keys must be spike-verified before being added (see the OEM
 * *_SPIKE_RESULTS docs); the OnePlus key is present for a future lab adapter and is not
 * invoked by production code.
 */
internal object SettingWritePolicy {
    private val WRITABLE: Map<String, Map<String, Set<String>>> = mapOf(
        "secure" to mapOf(
            // Pixel charging optimization (docs/PIXEL_SPIKE_RESULTS.md)
            "charge_optimization_mode" to setOf("0", "1"),
            "adaptive_charging_enabled" to setOf("0", "1"),
            // Xiaomi HyperOS charging protection (docs/XIAOMI_SPIKE_RESULTS.md)
            "security_pc_secure_protect_mode_key" to setOf("0", "1"),
        ),
        "global" to mapOf(
            // Samsung battery protection (docs/SAMSUNG_SPIKE_RESULTS.md)
            "protect_battery" to setOf("0", "1", "3"),
            "battery_protection_threshold" to setOf("80", "85", "90", "95"),
        ),
        "system" to mapOf(
            // OnePlus/Oppo candidate — allowlisted for future lab work, never written in production.
            "regular_charge_protection_switch_state" to setOf("0", "1"),
        ),
    )

    fun validate(namespace: String, key: String, value: String) {
        val domain = WRITABLE[namespace]?.get(key)
        require(domain != null) { "Setting is not allowlisted for writes" }
        require(value in domain) { "Invalid setting value: not in the key's allowed domain" }
    }
}
