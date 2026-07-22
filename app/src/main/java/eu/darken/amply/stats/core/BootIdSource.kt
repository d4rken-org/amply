package eu.darken.amply.stats.core

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device boot count, used to tell a same-boot process restart (resume the open session)
 * from a reboot (seal it — elapsed-time continuity can't survive a power-off). The value can only
 * change across a reboot, which restarts this process, so it is read once and cached. A device that
 * doesn't report [Settings.Global.BOOT_COUNT] yields a constant sentinel; reboots then look like
 * same-boot restarts, which degrades to a partial/interrupted seal rather than anything unsafe.
 */
@Singleton
class BootIdSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cached: Long by lazy { read() }

    fun current(): Long = cached

    private fun read(): Long = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNAVAILABLE).toLong()
    }.getOrDefault(UNAVAILABLE.toLong())

    private companion object {
        const val UNAVAILABLE = -1
    }
}
