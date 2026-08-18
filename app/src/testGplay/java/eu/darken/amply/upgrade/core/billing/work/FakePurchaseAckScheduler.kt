package eu.darken.amply.upgrade.core.billing.work

import androidx.work.WorkManager
import javax.inject.Provider

/**
 * Records what the billing stack asked the safety net to do, in call order, without standing up
 * WorkManager. The [calls] list is shareable so a test can interleave it with other recorded events
 * (an ack round-trip, a Play launch) and assert the ORDER — the property that makes the net worth
 * anything when the process dies mid-purchase.
 *
 * The real scheduler's WorkManager provider is never resolved here; a resolution would be a bug in
 * the code under test, so it fails loudly rather than silently returning a stand-in.
 */
internal class FakePurchaseAckScheduler(
    val calls: MutableList<String> = mutableListOf(),
) : PurchaseAckScheduler(
    Provider<WorkManager> { error("FakePurchaseAckScheduler must not resolve WorkManager") },
) {

    /** When set, every arm throws this — the fail-open contract of both trigger sites. */
    var failure: (() -> Throwable)? = null

    override suspend fun armForBillingFlowLaunch() {
        calls.add(LAUNCH)
        failure?.let { throw it() }
    }

    override suspend fun armForUnackedPurchases(expiresAt: Long) {
        calls.add("$RESCUE:$expiresAt")
        failure?.let { throw it() }
    }

    companion object {
        const val LAUNCH = "arm-launch"
        const val RESCUE = "arm-rescue"
    }
}
