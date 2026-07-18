package eu.darken.amply.main.ui.widget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.Button
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.main.ui.MainActivity

@Keep
class AmplyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        val active = entryPoint.sessionStore().currentSession() != null
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ColorProvider(Color(0xFFE5F4ED), Color(0xFF18352A)))
                    .padding(12.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Text(
                    text = if (active) "Amply · 100% once active" else "Amply · charge protection",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF15382B), Color(0xFFE4F5EC)),
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                ) {
                    Button(
                        text = if (active) "Restore 80%" else "Protect 80%",
                        onClick = actionRunCallback<ProtectAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Button(
                        text = if (active) "100% active" else "100% once",
                        onClick = actionRunCallback<FullChargeAction>(),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }
        }
    }
}

@Keep
class AmplyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AmplyWidget()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AmplyWidgetEntryPoint {
    fun sessionStore(): FullChargeStore
    fun chargingRepository(): ChargingRepository
}

@Keep
class ProtectAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        if (entryPoint.sessionStore().currentSession() != null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChargeSessionService::class.java)
                    .setAction(ChargeSessionService.ACTION_RESTORE),
            )
        } else {
            val result = entryPoint.chargingRepository().applyPersistent(ChargePolicy.FixedLimit(80))
            if (!result.success) openApp(context, false)
        }
        AmplyWidget().updateAll(context)
    }
}

@Keep
class FullChargeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AmplyWidgetEntryPoint::class.java,
        )
        if (entryPoint.sessionStore().currentSession() != null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChargeSessionService::class.java)
                    .setAction(ChargeSessionService.ACTION_RESTORE),
            )
        } else if (!canShowNotifications(context)) {
            openApp(context, true)
        } else {
            val state = entryPoint.chargingRepository().refresh()
            if (!state.controlEnabled || state.access?.canControl != true) {
                openApp(context, false)
                AmplyWidget().updateAll(context)
                return
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, ChargeSessionService::class.java)
                    .setAction(ChargeSessionService.ACTION_START),
            )
        }
        AmplyWidget().updateAll(context)
    }
}

private fun canShowNotifications(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
    PackageManager.PERMISSION_GRANTED

private fun openApp(context: Context, requestNotifications: Boolean) {
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_REQUEST_NOTIFICATIONS, requestNotifications)
        },
    )
}
