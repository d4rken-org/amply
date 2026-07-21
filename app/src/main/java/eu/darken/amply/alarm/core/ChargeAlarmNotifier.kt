package eu.darken.amply.alarm.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Side-effect seam for the alarm alert, kept separate so [ChargeAlarmWatcher] is unit-testable. */
interface ChargeAlarmNotifier {
    /** Whether an alert would actually reach the user (permission granted, channel not muted). */
    fun canDeliver(): Boolean
    fun show(percent: Int)
    fun cancel()
}

@Singleton
class AndroidChargeAlarmNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChargeAlarmNotifier {
    override fun canDeliver(): Boolean = ChargeAlarmNotifications.canDeliver(context)
    override fun show(percent: Int) = ChargeAlarmNotifications.show(context, percent)
    override fun cancel() = ChargeAlarmNotifications.cancel(context)
}
