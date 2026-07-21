package eu.darken.amply.fullcharge.core

import eu.darken.amply.fullcharge.core.ServiceDispatch.CheckResolution
import eu.darken.amply.fullcharge.core.ServiceDispatch.Trigger
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ServiceDispatchTest {

    private fun boot(session: Boolean, pending: Boolean, gesture: Boolean) =
        ServiceDispatch.startAction(Trigger.BOOT, session, pending, gesture)

    private fun foreground(session: Boolean, pending: Boolean, gesture: Boolean) =
        ServiceDispatch.startAction(Trigger.FOREGROUND, session, pending, gesture)

    @Test
    fun `boot dispatch prefers recovery and falls back to monitoring`() {
        boot(session = true, pending = false, gesture = true) shouldBe ChargeSessionService.ACTION_RECOVER
        boot(session = false, pending = true, gesture = false) shouldBe ChargeSessionService.ACTION_RECOVER
        boot(session = false, pending = false, gesture = true) shouldBe ChargeSessionService.ACTION_MONITOR
        boot(session = false, pending = false, gesture = false) shouldBe null
    }

    @Test
    fun `foreground dispatch checks whenever any work exists`() {
        foreground(session = true, pending = false, gesture = false) shouldBe ChargeSessionService.ACTION_CHECK
        foreground(session = false, pending = true, gesture = false) shouldBe ChargeSessionService.ACTION_CHECK
        foreground(session = false, pending = false, gesture = true) shouldBe ChargeSessionService.ACTION_CHECK
        foreground(session = true, pending = true, gesture = true) shouldBe ChargeSessionService.ACTION_CHECK
    }

    @Test
    fun `foreground dispatch never checks a session-restoring action`() {
        // A foreground launch may find a live, healthy session; CHECK reconciles instead of restoring.
        foreground(session = true, pending = true, gesture = true) shouldBe ChargeSessionService.ACTION_CHECK
    }

    @Test
    fun `foreground dispatch with no work does not start the service`() {
        foreground(session = false, pending = false, gesture = false) shouldBe null
    }

    @Test
    fun `check resolution leaves an active recovery alone`() {
        ServiceDispatch.resolveCheck(recoveryActive = true, pendingRecovery = true, sessionExists = true) shouldBe
            CheckResolution.ALREADY_RECOVERING
        ServiceDispatch.resolveCheck(recoveryActive = true, pendingRecovery = false, sessionExists = false) shouldBe
            CheckResolution.ALREADY_RECOVERING
    }

    @Test
    fun `check resolution prefers pending recovery over a coexisting session`() {
        // Both records can coexist after process death inside setPersistentPolicy; the pending
        // target is the newer intent and recovery drops the stale session without restoring.
        ServiceDispatch.resolveCheck(recoveryActive = false, pendingRecovery = true, sessionExists = true) shouldBe
            CheckResolution.START_RECOVERY
        ServiceDispatch.resolveCheck(recoveryActive = false, pendingRecovery = true, sessionExists = false) shouldBe
            CheckResolution.START_RECOVERY
    }

    @Test
    fun `check resolution resumes a session instead of restoring it`() {
        ServiceDispatch.resolveCheck(recoveryActive = false, pendingRecovery = false, sessionExists = true) shouldBe
            CheckResolution.RESUME_SESSION
    }

    @Test
    fun `check resolution without work falls through to gesture monitoring or stop`() {
        ServiceDispatch.resolveCheck(recoveryActive = false, pendingRecovery = false, sessionExists = false) shouldBe
            CheckResolution.MONITOR_OR_STOP
    }
}
