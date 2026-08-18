package eu.darken.amply.upgrade.core.billing.work

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import eu.darken.amply.BuildConfig
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import javax.inject.Provider

/**
 * Driven against a REAL WorkManager (in-memory DB, synchronous executor) rather than a hand-written
 * imitation: what this class has to get right is WorkManager's own unique-work bookkeeping — that a
 * new purchase flow cannot displace a rescue that is already pending for an existing purchase, and
 * that a second flow does replace its own watch instead of stacking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PurchaseAckSchedulerTest {

    private lateinit var workManager: WorkManager

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(Log.DEBUG)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    private fun scheduler() = PurchaseAckScheduler(Provider { workManager })

    // Finished work (a REPLACE'd request that WorkManager kept as CANCELLED) is not what is armed.
    private fun pending(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    private fun pendingId(name: String): UUID = pending(name).single().id

    @Test fun `a billing flow launch arms the launch watch`(): Unit = runBlocking {
        scheduler().armForBillingFlowLaunch()

        // Delayed, so the worker cannot run (and complete the net) while the user may still be
        // standing in the Play sheet the net exists for.
        pending(LAUNCH_NAME).single().state shouldBe WorkInfo.State.ENQUEUED
        pending(RESCUE_NAME) shouldBe emptyList()
    }

    @Test fun `a second billing flow replaces the pending launch watch`(): Unit = runBlocking {
        val scheduler = scheduler()
        scheduler.armForBillingFlowLaunch()
        val first = pendingId(LAUNCH_NAME)

        scheduler.armForBillingFlowLaunch()

        // REPLACE, not KEEP: a genuinely new flow refreshes the watch window, and the sweep covers
        // every unacknowledged purchase anyway, so nothing is lost.
        val second = pendingId(LAUNCH_NAME)
        (second == first) shouldBe false
    }

    @Test fun `a discovered unacknowledged purchase arms the rescue lane and keeps it`(): Unit = runBlocking {
        val scheduler = scheduler()
        scheduler.armForUnackedPurchases(System.currentTimeMillis() + DAY_MS)
        val first = pendingId(RESCUE_NAME)

        scheduler.armForUnackedPurchases(System.currentTimeMillis() + DAY_MS)

        // KEEP: the pending rescue already covers every unacknowledged purchase, and re-arming it
        // on every ack pass would keep pushing its execution out.
        pendingId(RESCUE_NAME) shouldBe first
    }

    @Test fun `a new billing flow never displaces a pending rescue`(): Unit = runBlocking {
        val scheduler = scheduler()
        scheduler.armForUnackedPurchases(System.currentTimeMillis() + DAY_MS)
        val rescue = pendingId(RESCUE_NAME)

        scheduler.armForBillingFlowLaunch()

        // Separate work identities are the whole point: the launch watch's REPLACE must not be able
        // to cancel the rescue armed for a purchase that already exists.
        pendingId(RESCUE_NAME) shouldBe rescue
        pending(LAUNCH_NAME).size shouldBe 1
    }

    @Test fun `a deadline that already passed schedules nothing`(): Unit = runBlocking {
        scheduler().armForUnackedPurchases(System.currentTimeMillis() - DAY_MS)

        // Play has already voided such a purchase; a sweep cannot bring it back.
        pending(RESCUE_NAME) shouldBe emptyList()
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        val LAUNCH_NAME = "${BuildConfig.APPLICATION_ID}.gplay.purchase-ack.launch.v1"
        val RESCUE_NAME = "${BuildConfig.APPLICATION_ID}.gplay.purchase-ack.rescue.v1"
    }
}
