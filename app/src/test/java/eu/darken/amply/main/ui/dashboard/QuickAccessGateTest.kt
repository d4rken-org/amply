package eu.darken.amply.main.ui.dashboard

import eu.darken.amply.main.core.QuickAccessState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QuickAccessGateTest {

    private fun show(
        directReady: Boolean = true,
        presenceChecked: Boolean = true,
        dismissed: Boolean = false,
        widgetAdded: Boolean = false,
        tileAdded: Boolean = false,
    ) = shouldShowQuickAccess(
        directReady = directReady,
        presenceChecked = presenceChecked,
        quickAccess = QuickAccessState(
            dismissed = dismissed,
            widgetAdded = widgetAdded,
            tileAdded = tileAdded,
        ),
    )

    @Test
    fun `shows once setup is done and nothing is discovered`() {
        show() shouldBe true
    }

    @Test
    fun `hidden while setup is incomplete`() {
        show(directReady = false) shouldBe false
    }

    @Test
    fun `hidden until the widget presence check has completed`() {
        show(presenceChecked = false) shouldBe false
    }

    @Test
    fun `dismissal hides regardless of discovery state`() {
        show(dismissed = true) shouldBe false
        show(dismissed = true, widgetAdded = true) shouldBe false
    }

    @Test
    fun `one discovered surface keeps the card for the other`() {
        show(widgetAdded = true) shouldBe true
        show(tileAdded = true) shouldBe true
    }

    @Test
    fun `both surfaces discovered hides the card`() {
        show(widgetAdded = true, tileAdded = true) shouldBe false
    }
}
