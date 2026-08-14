package eu.darken.amply.upgrade.core.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import eu.darken.amply.upgrade.core.billing.BillingManager.Companion.tryMapUserFriendly
import eu.darken.amply.upgrade.core.billing.client.BillingClientException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * Response codes are the only thing Play tells us about a failure, so the mapping from code to type
 * is what decides which copy the user gets — and whether a cancelled sheet is treated as an error.
 */
class BillingErrorMappingTest {

    private fun clientError(code: Int) = BillingClientException(
        BillingResult.newBuilder().setResponseCode(code).setDebugMessage("test").build(),
    )

    @Test
    fun `a cancelled sheet maps to the silent type`() {
        clientError(BillingResponseCode.USER_CANCELED).tryMapUserFriendly()
            .shouldBeInstanceOf<UserCanceledBillingException>()
    }

    @Test
    fun `every unreachable-play code maps to the same user-facing type`() {
        @Suppress("DEPRECATION")
        val unreachable = listOf(
            BillingResponseCode.BILLING_UNAVAILABLE,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_DISCONNECTED,
            BillingResponseCode.SERVICE_TIMEOUT,
        )

        unreachable.forEach { code ->
            clientError(code).tryMapUserFriendly().shouldBeInstanceOf<GplayServiceUnavailableException>()
        }
    }

    @Test
    fun `the remaining mapped codes keep their own types`() {
        clientError(BillingResponseCode.ERROR).tryMapUserFriendly()
            .shouldBeInstanceOf<InternalBillingException>()
        clientError(BillingResponseCode.NETWORK_ERROR).tryMapUserFriendly()
            .shouldBeInstanceOf<NetworkBillingException>()
        clientError(BillingResponseCode.ITEM_ALREADY_OWNED).tryMapUserFriendly()
            .shouldBeInstanceOf<ItemAlreadyOwnedBillingException>()
    }

    @Test
    fun `an unmapped code stays the original client exception`() {
        val original = clientError(BillingResponseCode.DEVELOPER_ERROR)

        original.tryMapUserFriendly() shouldBeSameInstanceAs original
    }

    @Test
    fun `a non-billing failure is passed through untouched`() {
        val other = IllegalStateException("something else entirely")

        other.tryMapUserFriendly() shouldBeSameInstanceAs other
    }

    @Test
    fun `the mapped exception keeps the original as its cause`() {
        val original = clientError(BillingResponseCode.NETWORK_ERROR)

        original.tryMapUserFriendly().cause shouldBe original
    }
}
