package eu.darken.amply.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.R
import eu.darken.amply.common.debug.logging.Logging.Priority.ERROR
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens a web page, reporting whether an activity was actually started.
 *
 * The return value is the point: the FOSS sponsor unlock only arms its return heuristic when the
 * page really opened, and a fire-and-forget `startActivity` cannot tell the caller that. Ported
 * from SD Maid SE's `WebpageTool` — existing ad-hoc URL intents in the app are left as they are.
 */
@Singleton
open class WebpageTool @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // `open` so a test can decide whether the page opened without having to install a fake browser
    // into the test runtime's package manager — the return value is the interesting part here.
    open fun open(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Android TV has no browser: a system stub consumes browser intents and shows an unhelpful
        // toast, so resolving to one counts as "nothing handled it".
        val handler = intent.resolveActivity(context.packageManager)
        if (handler != null && handler.packageName in STUB_PACKAGES) {
            log(TAG, ERROR) { "Only a stub handler ($handler) is available for $url" }
            showNoAppToast()
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            log(TAG, ERROR) { "No compatible activity for $url" }
            showNoAppToast()
            false
        } catch (e: SecurityException) {
            // A resolved handler can still deny the start (restricted profile, guarded activity).
            log(TAG, ERROR) { "Failed to launch $url: $e" }
            false
        }
    }

    private fun showNoAppToast() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, R.string.settings_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private val TAG = logTag("WebpageTool")
        private val STUB_PACKAGES = setOf(
            "com.android.tv.frameworkpackagestubs",
            "com.google.android.tv.frameworkpackagestubs",
        )
    }
}
