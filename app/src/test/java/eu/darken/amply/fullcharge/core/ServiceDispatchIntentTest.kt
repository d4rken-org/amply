package eu.darken.amply.fullcharge.core

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceDispatchIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `start intent targets the session service explicitly with the given action`() {
        val intent = ServiceDispatch.startIntent(context, ChargeSessionService.ACTION_CHECK)

        intent.component?.className shouldBe ChargeSessionService::class.java.name
        intent.component?.packageName shouldBe context.packageName
        intent.action shouldBe ChargeSessionService.ACTION_CHECK
    }

    @Test
    fun `start intent carries recovery and monitor actions unchanged`() {
        ServiceDispatch.startIntent(context, ChargeSessionService.ACTION_RECOVER).action shouldBe
            ChargeSessionService.ACTION_RECOVER
        ServiceDispatch.startIntent(context, ChargeSessionService.ACTION_MONITOR).action shouldBe
            ChargeSessionService.ACTION_MONITOR
    }
}
