package eu.darken.amply.upgrade.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The unlock heuristic hinges on this latch: a resume only counts as "came back from the sponsor
 * page" if the screen was actually backgrounded first, and it counts exactly once.
 */
class SponsorReturnTrackerTest {

    @Test
    fun `a resume without a preceding stop is not a return`() {
        SponsorReturnTracker().consumeResumeReturn() shouldBe false
    }

    @Test
    fun `a stop then resume is a return, and only once`() {
        val tracker = SponsorReturnTracker()

        tracker.onStop()

        tracker.consumeResumeReturn() shouldBe true
        tracker.consumeResumeReturn() shouldBe false
    }

    @Test
    fun `repeated stops still yield a single return`() {
        val tracker = SponsorReturnTracker()

        tracker.onStop()
        tracker.onStop()

        tracker.consumeResumeReturn() shouldBe true
        tracker.consumeResumeReturn() shouldBe false
    }

    @Test
    fun `a seeded tracker reports the pending return it was recreated with`() {
        // After a process death while the browser was in front, the ViewModel's handle is the
        // authority; a blank tracker would swallow the very first return.
        val tracker = SponsorReturnTracker(wentToBackground = true)

        tracker.consumeResumeReturn() shouldBe true
        tracker.consumeResumeReturn() shouldBe false
    }
}
