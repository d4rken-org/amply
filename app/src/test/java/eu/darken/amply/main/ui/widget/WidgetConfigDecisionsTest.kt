package eu.darken.amply.main.ui.widget

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WidgetConfigDecisionsTest {

    @Test
    fun `an owned widget id is configured`() {
        resolveWidgetConfigEntry(42, providerMatches = true) shouldBe WidgetConfigEntry.Proceed(42)
    }

    @Test
    fun `an invalid id finishes without touching anything`() {
        resolveWidgetConfigEntry(0, providerMatches = true) shouldBe WidgetConfigEntry.Finish
    }

    /** The activity is exported: a widget belonging to another provider is not ours to configure. */
    @Test
    fun `a foreign provider finishes`() {
        resolveWidgetConfigEntry(42, providerMatches = false) shouldBe WidgetConfigEntry.Finish
        resolveWidgetConfigEntry(0, providerMatches = false) shouldBe WidgetConfigEntry.Finish
    }

    @Test
    fun `a state with nothing to save still returns OK`() {
        resolveWidgetConfigCompletion(saveAttempted = false, saveSucceeded = false) shouldBe
            WidgetConfigCompletion(updateWidget = true, result = WidgetConfigResult.FINISH_OK)
    }

    @Test
    fun `a successful save returns OK and renders the widget`() {
        resolveWidgetConfigCompletion(saveAttempted = true, saveSucceeded = true) shouldBe
            WidgetConfigCompletion(updateWidget = true, result = WidgetConfigResult.FINISH_OK)
    }

    @Test
    fun `a failed save keeps the result cancelled and skips the render`() {
        resolveWidgetConfigCompletion(saveAttempted = true, saveSucceeded = false) shouldBe
            WidgetConfigCompletion(updateWidget = false, result = WidgetConfigResult.STAY_RETRY)
    }
}
