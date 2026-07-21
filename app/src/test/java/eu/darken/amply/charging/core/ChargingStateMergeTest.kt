package eu.darken.amply.charging.core

import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.common.ca.toCaString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChargingStateMergeTest {

    private fun status(ready: Boolean) =
        BackendStatus(available = ready, granted = ready, detail = "x".toCaString())

    private val freshAccess = AccessSnapshot(direct = status(true), shizuku = status(false))

    @Test
    fun `an in-flight WSS grant keeps its spinner and message through a concurrent refresh`() {
        val prev = ChargingState(
            busy = true,
            grantingWss = true,
            message = "granting…".toCaString(),
            access = AccessSnapshot(direct = status(false), shizuku = status(false)),
        )
        // A refresh builds a fresh state with busy=false, grantingWss=false, message=null by default.
        val built = ChargingState(access = freshAccess)

        val merged = mergeRefreshedState(prev, built)

        // Transient grant cue survives so the poll/battery/resume refresh can't clear it mid-grant.
        merged.grantingWss shouldBe true
        merged.busy shouldBe true
        merged.message shouldBe prev.message
        // Non-transient fields still come from the fresh state.
        merged.access shouldBe freshAccess
    }

    @Test
    fun `without a grant in flight the fresh state is published verbatim`() {
        val prev = ChargingState(grantingWss = false, message = "old".toCaString())
        val built = ChargingState(access = freshAccess, message = "new".toCaString())

        mergeRefreshedState(prev, built) shouldBe built
    }
}
