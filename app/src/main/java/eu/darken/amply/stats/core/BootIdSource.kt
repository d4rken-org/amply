package eu.darken.amply.stats.core

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the device boot count, used to tell a same-boot process restart (resume the open session)
 * from a reboot (seal it — elapsed-time continuity can't survive a power-off). The value can only
 * change across a reboot, which restarts this process, so it is read once and cached.
 *
 * A device that doesn't report [Settings.Global.BOOT_COUNT] yields the [UNAVAILABLE] sentinel, which
 * two different boots would compare *equal* on. So the sentinel is not merely a degraded id — it is
 * explicitly disqualifying: [StatsSessionEngine.evaluateResume] refuses to resume an open session
 * when either side is [UNAVAILABLE], because a resume across an unnoticed reboot would splice two
 * boots' [android.os.SystemClock.elapsedRealtime] readings into one bogus duration. Such a device
 * always falls back to sealing, which is lossy but never wrong.
 */
@Singleton
class BootIdSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cached: Long by lazy { read() }

    fun current(): Long = cached

    private fun read(): Long = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNAVAILABLE.toInt()).toLong()
    }.getOrDefault(UNAVAILABLE)

    companion object {
        /** Boot identity could not be determined — never usable to prove two observations share a boot. */
        const val UNAVAILABLE = -1L
    }
}
