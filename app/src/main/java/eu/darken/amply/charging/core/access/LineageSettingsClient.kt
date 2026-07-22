package eu.darken.amply.charging.core.access

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.toCaString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The three LineageOS charge-control values read as ONE consistent snapshot, or a typed failure. */
sealed interface LineageChargeReadout {
    /** A single consistent read of all three keys; a null field means that key was absent. */
    data class Values(val enabled: String?, val mode: String?, val limit: String?) : LineageChargeReadout

    /** Provider/security failure, missing columns, or an unexpected (duplicate) row — never guessed from. */
    data class Unreadable(val reason: CaString) : LineageChargeReadout
}

/**
 * Reads LineageOS's charge-control keys from `content://lineagesettings/system` in a single query, so the
 * three values are a **consistent snapshot** — separate per-key reads could interleave with an external
 * change and fabricate a state that never existed. An interface so the adapter is unit-testable without
 * Android.
 */
interface LineageChargeReader {
    suspend fun readChargeControl(): LineageChargeReadout
}

/**
 * Unprivileged ContentResolver implementation of [LineageChargeReader]. Reads carry no permission (the
 * provider declares no readPermission), so this works regardless of Shizuku/WSS — only *writes* are
 * privileged (Shizuku, see `ChargingControlUserService.writeLineageSetting`). Requires the
 * `<queries><provider authorities="lineagesettings"/>` manifest entry so package visibility on API 30+
 * doesn't hide the provider.
 */
@Singleton
class LineageSettingsClient @Inject constructor(
    @ApplicationContext private val context: Context,
) : LineageChargeReader {
    private val systemUri: Uri = Uri.parse("content://${DeviceInfo.LINEAGE_SETTINGS_AUTHORITY}/system")

    override suspend fun readChargeControl(): LineageChargeReadout = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                systemUri,
                arrayOf(COL_NAME, COL_VALUE),
                "$COL_NAME IN (?,?,?)",
                arrayOf(KEY_ENABLED, KEY_MODE, KEY_LIMIT),
                null,
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(COL_NAME)
                val valueIdx = cursor.getColumnIndex(COL_VALUE)
                // A missing expected column is a genuine failure — never fall back to a positional guess.
                if (nameIdx < 0 || valueIdx < 0) return@use unreadable()
                val values = HashMap<String, String>()
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    // name is UNIQUE; a duplicate is an unexpected provider state we refuse rather than guess from.
                    if (values.putIfAbsent(name, cursor.getString(valueIdx).orEmpty()) != null) return@use unreadable()
                }
                LineageChargeReadout.Values(
                    enabled = values[KEY_ENABLED],
                    mode = values[KEY_MODE],
                    limit = values[KEY_LIMIT],
                )
            } ?: unreadable()
        }.getOrElse {
            LineageChargeReadout.Unreadable(
                when (it) {
                    is SecurityException -> R.string.access_read_blocked.toCaString()
                    else -> (it.message ?: it.javaClass.simpleName).toCaString()
                },
            )
        }
    }

    private fun unreadable() = LineageChargeReadout.Unreadable(R.string.charging_reason_settings_unreadable.toCaString())

    companion object {
        const val COL_NAME = "name"
        const val COL_VALUE = "value"
        const val KEY_ENABLED = "charging_control_enabled"
        const val KEY_MODE = "charging_control_mode"
        const val KEY_LIMIT = "charging_control_charging_limit"
    }
}
