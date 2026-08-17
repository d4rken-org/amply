package eu.darken.amply.main.ui.widget

import android.appwidget.AppWidgetManager

/**
 * Whether the configuration activity may touch any state at all. The activity is exported (the
 * AppWidget host has to be able to launch it), so a forged or foreign id must be rejected **before**
 * anything is read, written or updated — a widget belonging to another provider is not ours to
 * configure.
 */
internal sealed interface WidgetConfigEntry {
    data class Proceed(val appWidgetId: Int) : WidgetConfigEntry
    data object Finish : WidgetConfigEntry
}

internal fun resolveWidgetConfigEntry(appWidgetId: Int, providerMatches: Boolean): WidgetConfigEntry = when {
    appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID -> WidgetConfigEntry.Finish
    !providerMatches -> WidgetConfigEntry.Finish
    else -> WidgetConfigEntry.Proceed(appWidgetId)
}

/**
 * What happens after the user's confirming action.
 *
 * `RESULT_OK` is what makes the host keep a newly placed widget, so every exit the user can reach —
 * including the states with nothing to configure — has to be able to produce it (minSdk is 26 and
 * `configuration_optional` only exists from API 31, so on API 26–30 a cancelled configuration
 * discards the widget). The one exception is a failed save: the user asked for a configuration that
 * isn't stored, so the screen stays with a retryable error and the result stays CANCELED.
 *
 * The widget-update outcome is deliberately **not** an input: rendering is best-effort (the next
 * broadcast re-renders anyway) and must never turn a stored configuration into a discarded widget.
 */
internal enum class WidgetConfigResult {
    FINISH_OK,
    STAY_RETRY,
}

internal data class WidgetConfigCompletion(
    val updateWidget: Boolean,
    val result: WidgetConfigResult,
)

internal fun resolveWidgetConfigCompletion(
    saveAttempted: Boolean,
    saveSucceeded: Boolean,
): WidgetConfigCompletion = if (saveAttempted && !saveSucceeded) {
    WidgetConfigCompletion(updateWidget = false, result = WidgetConfigResult.STAY_RETRY)
} else {
    WidgetConfigCompletion(updateWidget = true, result = WidgetConfigResult.FINISH_OK)
}
