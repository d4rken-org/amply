package eu.darken.amply.monitor.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.alarm.core.ChargeAlarmWatcher
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration guard for the `@IntoSet` watcher seam: a wrong/absent binding would make the injected
 * watcher set empty, silently disabling the charge alarm. This asserts the real Hilt graph the
 * charge-session service consumes actually contains the alarm watcher.
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class ChargeMonitorWatcherGraphTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WatcherEntryPoint {
        fun watchers(): Set<@JvmSuppressWildcards ChargeMonitorWatcher>
    }

    @Test
    fun `the charge alarm watcher is bound into the monitor watcher set`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val watchers = EntryPointAccessors.fromApplication(
            context,
            WatcherEntryPoint::class.java,
        ).watchers()

        watchers.map { it.id } shouldContain "charge_alarm"
        watchers.any { it is ChargeAlarmWatcher } shouldBe true
    }
}
