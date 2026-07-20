package eu.darken.amply.main.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.common.debug.DebugLogManager
import eu.darken.amply.common.debug.DebugLogState
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.theming.ThemeColor
import eu.darken.amply.common.theming.ThemeMode
import eu.darken.amply.common.theming.ThemeSettings
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.common.theming.ThemeStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themeSettings: ThemeSettings,
    private val debugLogManager: DebugLogManager,
) : ViewModel() {
    val themeState: StateFlow<ThemeState> = themeSettings.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ThemeState(),
    )
    val debugState: StateFlow<DebugLogState> = debugLogManager.state

    fun setThemeMode(value: ThemeMode) = viewModelScope.launch {
        log(TAG) { "Theme mode: $value" }
        themeSettings.setMode(value)
    }

    fun setThemeStyle(value: ThemeStyle) = viewModelScope.launch {
        log(TAG) { "Theme style: $value" }
        themeSettings.setStyle(value)
    }

    fun setThemeColor(value: ThemeColor) = viewModelScope.launch {
        log(TAG) { "Theme color: $value" }
        themeSettings.setColor(value)
    }

    fun startDebugLog() = viewModelScope.launch {
        debugLogManager.start()
    }

    fun stopDebugLog() = viewModelScope.launch {
        debugLogManager.stop()
    }

    fun clearDebugLogs() = viewModelScope.launch {
        debugLogManager.clear()
    }

    fun shareLatestDebugLog() = viewModelScope.launch {
        val uri = debugLogManager.latestShareUri()
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.settings_support_no_log), Toast.LENGTH_SHORT).show()
            return@launch
        }
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                context.getString(R.string.settings_support_share_chooser),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            log(TAG, Logging.Priority.WARN) { "Could not open $url: ${it.message}" }
            Toast.makeText(context, context.getString(R.string.settings_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    fun contactSupport() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:support@darken.eu")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_support_email_subject))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.settings_no_email), Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        val TAG = logTag("Settings", "VM")
    }
}
