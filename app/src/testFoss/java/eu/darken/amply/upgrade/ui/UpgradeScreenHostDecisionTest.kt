package eu.darken.amply.upgrade.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The host's two decisions, taken out of the composition so they can be asserted in any frame order —
 * the bug they encode is precisely a stale state surviving into the next entry's first frames.
 *
 * The ViewModel is activity-scoped, so an entry can start with the *previous* visit's binding still
 * in place. The status ("manage") entry must never render the pitch it exists to replace, and must
 * never dismiss itself because an upgraded user opened it.
 */
class UpgradeScreenHostDecisionTest {

    @Test fun `an unbound manage entry renders nothing rather than the pitch`() {
        upgradeViewFor(view = null, manage = true) shouldBe null
    }

    @Test fun `an unbound plain entry keeps rendering the pitch`() {
        upgradeViewFor(view = null, manage = false) shouldBe FossUpgradeView.PITCH
    }

    @Test fun `a bound view always wins`() {
        upgradeViewFor(view = FossUpgradeView.STATUS_UPGRADED, manage = true) shouldBe
            FossUpgradeView.STATUS_UPGRADED
        upgradeViewFor(view = FossUpgradeView.STATUS_FREE, manage = true) shouldBe
            FossUpgradeView.STATUS_FREE
        upgradeViewFor(view = FossUpgradeView.PITCH, manage = false) shouldBe FossUpgradeView.PITCH
    }

    @Test fun `an answered pitch dismisses the plain entry`() {
        shouldDismissUpgradeScreen(view = FossUpgradeView.PITCH, isPro = true, manage = false) shouldBe true
    }

    @Test fun `a stale pitch never dismisses a manage entry`() {
        // The whole bug: an upgraded user opens the status entry while the previous visit's PITCH is
        // still bound, and the screen bounces straight back out.
        shouldDismissUpgradeScreen(view = FossUpgradeView.PITCH, isPro = true, manage = true) shouldBe false
    }

    @Test fun `a free user is never dismissed`() {
        shouldDismissUpgradeScreen(view = FossUpgradeView.PITCH, isPro = false, manage = false) shouldBe false
    }

    @Test fun `the status views never dismiss`() {
        shouldDismissUpgradeScreen(view = FossUpgradeView.STATUS_UPGRADED, isPro = true, manage = true) shouldBe false
        shouldDismissUpgradeScreen(view = FossUpgradeView.STATUS_FREE, isPro = false, manage = true) shouldBe false
        shouldDismissUpgradeScreen(view = null, isPro = true, manage = false) shouldBe false
    }
}
