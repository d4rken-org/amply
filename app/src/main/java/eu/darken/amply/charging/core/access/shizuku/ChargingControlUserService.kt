package eu.darken.amply.charging.core.access.shizuku

import android.content.Context
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

    override fun writeLineageSetting(key: String, value: String): Boolean {
        // Boundary owns validation: key allowlist + per-key value domain, independent of the adapter layer.
        LineageSettingWritePolicy.validate(key, value)
        // `content insert` upserts (name TEXT UNIQUE ON CONFLICT REPLACE). Constant binary + constant URI,
        // argv-separated — no shell string. The allowlisted key/value contain no ':'/space so the
        // `col:s:val` bind tokens stay single argv elements. Exit code is corroborated by the adapter's
        // read-back verification, so a `content`-reported success that the provider validator rejected still fails.
        val result = runCommand(
            "/system/bin/content", "insert",
            "--uri", LINEAGE_SYSTEM_URI,
            "--bind", "name:s:$key",
            "--bind", "value:s:$value",
        )
        return result.exitCode == 0
    }

    override fun destroy() = exitProcess(0)

    private fun requireNamespace(namespace: String) {
        require(namespace in NAMESPACES) { "Unsupported settings namespace" }
    }

    private fun requireKey(key: String) {
        require(KEY.matches(key)) { "Invalid settings key" }
    }

    private fun runCommand(vararg args: String): CommandResult =
        runBoundedProcess(args.toList(), COMMAND_TIMEOUT_MS, MAX_OUTPUT_BYTES)

    companion object {
        private val NAMESPACES = setOf("secure", "global", "system")
        private val KEY = Regex("[A-Za-z0-9_.:-]{1,160}")
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_.]{2,200}")
        const val LINEAGE_SYSTEM_URI = "content://lineagesettings/system"
        private const val COMMAND_TIMEOUT_MS = 10_000L
        // `snapshotSettings` returns the whole dump as one AIDL String, whose Parcel is ~2 bytes/char plus overhead,
        // so this UTF-8 byte cap stays well under the ~1 MiB Binder transaction ceiling. Real `settings list`
        // dumps are tens of KB; a namespace past this fails explicitly instead of risking TransactionTooLargeException.
        private const val MAX_OUTPUT_BYTES = 256_000
    }
}

internal data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs a subprocess draining stdout and stderr **concurrently on separate threads before** waiting for exit.
 *
 * The naive `waitFor()`-then-read order deadlocks: a `settings list` large enough to fill the OS pipe buffer blocks
 * the child on write while the parent blocks on `waitFor()`, so it never exits until the timeout kills it. Bounded
 * concurrent readers keep the pipes drained. Output past [capBytes] fails explicitly rather than risking a Binder
 * `TransactionTooLargeException` on the returned String. Argument-separated argv only — no shell string is evaluated.
 */
internal fun runBoundedProcess(command: List<String>, timeoutMs: Long, capBytes: Int): CommandResult {
    val process = ProcessBuilder(command).redirectErrorStream(false).start()
    val outReader = BoundedStreamReader(process.inputStream, capBytes)
    val errReader = BoundedStreamReader(process.errorStream, capBytes)
    val outThread = Thread(outReader, "amp-cmd-out").apply { isDaemon = true; start() }
    val errThread = Thread(errReader, "amp-cmd-err").apply { isDaemon = true; start() }
    try {
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        // Join before reading text() so the buffers are complete; the readers close their own streams on exit.
        outThread.join(READER_JOIN_MS)
        errThread.join(READER_JOIN_MS)
        // Overflow is checked before timeout: an oversize stream can itself block the child into a timeout, and the
        // caller wants to know it was too large, not merely slow.
        when {
            outReader.overflow || errReader.overflow ->
                throw IllegalStateException("Command output exceeded $capBytes bytes")
            !finished -> throw IllegalStateException("Command timed out")
            outReader.error != null -> throw IllegalStateException("Reading command stdout failed", outReader.error)
            errReader.error != null -> throw IllegalStateException("Reading command stderr failed", errReader.error)
        }
        return CommandResult(process.exitValue(), outReader.text(), errReader.text())
    } finally {
        // Always tear down, including on the throw paths above: kill a still-running child so it can't keep a pipe
        // open, wait briefly for its descriptors to release, and join the (daemon) reader threads.
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(READER_JOIN_MS, TimeUnit.MILLISECONDS)
        outThread.join(READER_JOIN_MS)
        errThread.join(READER_JOIN_MS)
    }
}

/** Reads a stream into a capped buffer on its own thread; flags overflow instead of growing unbounded. */
private const val READER_JOIN_MS = 2_000L

internal class BoundedStreamReader(
    private val stream: InputStream,
    private val capBytes: Int,
) : Runnable {
    private val buffer = ByteArrayOutputStream()

    @Volatile
    var overflow = false
        private set

    @Volatile
    var error: Throwable? = null
        private set

    override fun run() {
        try {
            val chunk = ByteArray(8_192)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (buffer.size() + read > capBytes) {
                    overflow = true
                    break
                }
                buffer.write(chunk, 0, read)
            }
        } catch (t: Throwable) {
            error = t
        } finally {
            runCatching { stream.close() }
        }
    }

    fun text(): String = buffer.toString(Charsets.UTF_8.name())
}

/**
 * Pure validation for the privileged write path. Every writable setting carries an explicit
 * per-key value domain — the boundary itself rejects out-of-domain values instead of trusting
 * the adapter layer. Keys must be physically qualified before being added (see the qualification
 * ledger in .claude/rules/privileged-access.md); the OnePlus key is present for a future lab adapter
 * and is not invoked by production code.
 */
internal object SettingWritePolicy {
    private val WRITABLE: Map<String, Map<String, Set<String>>> = mapOf(
        "secure" to mapOf(
            // Pixel charging optimization
            "charge_optimization_mode" to setOf("0", "1"),
            "adaptive_charging_enabled" to setOf("0", "1"),
            // Xiaomi HyperOS charging protection
            "security_pc_secure_protect_mode_key" to setOf("0", "1"),
        ),
        "global" to mapOf(
            // Samsung battery protection
            "protect_battery" to setOf("0", "1", "3"),
            "battery_protection_threshold" to setOf("80", "85", "90", "95"),
        ),
        "system" to mapOf(
            // ColorOS/OxygenOS (Oplus) charging protection (qualification ledger in .claude/rules/privileged-access.md).
            // System namespace: writable only via Shizuku (shell UID), not direct WRITE_SECURE_SETTINGS.
            "regular_charge_protection_switch_state" to setOf("0", "1"),
            "smart_charge_protection_switch_state" to setOf("0", "1"),
        ),
    )

    fun validate(namespace: String, key: String, value: String) {
        val domain = WRITABLE[namespace]?.get(key)
        require(domain != null) { "Setting is not allowlisted for writes" }
        require(value in domain) { "Invalid setting value: not in the key's allowed domain" }
    }
}

/**
 * Write boundary for the LineageOS `content://lineagesettings/system` charge-control keys. Exact-string
 * domains, which also reject non-canonical forms (e.g. leading-zero "080") for free. Mirrors — and is kept
 * in lockstep with — the supported set in `LineageChargingAdapter`; the boundary constrains independently
 * of the adapter, so widen it only when the corresponding policy is supported AND qualified.
 */
internal object LineageSettingWritePolicy {
    const val KEY_ENABLED = "charging_control_enabled"
    const val KEY_MODE = "charging_control_mode"
    const val KEY_LIMIT = "charging_control_charging_limit"

    // v1 supports mode 3 (LIMIT) only; disabling control is via enabled=0, never mode=0 (invalid: provider
    // validates mode 1..3). Limits are the discrete 70..95 ticks the adapter exposes.
    private val WRITABLE: Map<String, Set<String>> = mapOf(
        KEY_ENABLED to setOf("0", "1"),
        KEY_MODE to setOf("3"),
        KEY_LIMIT to setOf("70", "75", "80", "85", "90", "95"),
    )

    fun validate(key: String, value: String) {
        val domain = WRITABLE[key]
        require(domain != null) { "Lineage setting is not allowlisted for writes" }
        require(value in domain) { "Invalid Lineage setting value: not in the key's allowed domain" }
    }
}
