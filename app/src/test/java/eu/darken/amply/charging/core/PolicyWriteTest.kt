package eu.darken.amply.charging.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class PolicyWriteTest {

    @Test
    fun `a write timeout is a failed write, not a cancelled coroutine`() = runTest {
        var timeouts = 0
        runPolicyWrite(
            write = { withTimeout(1) { awaitCancellation() } },
            onTimeout = { timeouts++ },
        ) shouldBe false
        timeouts shouldBe 1
    }

    @Test
    fun `an ordinary write failure is a failed write`() = runTest {
        var timeouts = 0
        runPolicyWrite(
            write = { throw IllegalStateException("write boom") },
            onTimeout = { timeouts++ },
        ) shouldBe false
        timeouts shouldBe 0
    }

    @Test
    fun `cancellation that is not a timeout propagates`() = runTest {
        var timeouts = 0
        shouldThrow<CancellationException> {
            runPolicyWrite(
                write = { throw CancellationException() },
                onTimeout = { timeouts++ },
            )
        }
        timeouts shouldBe 0
    }

    @Test
    fun `a successful write passes through`() = runTest {
        runPolicyWrite(write = { true }, onTimeout = { }) shouldBe true
        runPolicyWrite(write = { false }, onTimeout = { }) shouldBe false
    }
}
