package eu.darken.amply.main.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SurfaceUpdaterTest {

    @Test
    fun `a tile failure is isolated so the widget still updates`() = runTest {
        var widgetRan = false
        runSurfaceUpdate(
            tile = { throw IllegalStateException("tile boom") },
            widget = { widgetRan = true },
        )
        widgetRan shouldBe true
    }

    @Test
    fun `an ordinary widget failure propagates so a worker can retry`() = runTest {
        shouldThrow<IllegalStateException> {
            runSurfaceUpdate(tile = {}, widget = { throw IllegalStateException("widget boom") })
        }
    }

    @Test
    fun `cancellation from the widget update propagates and is never swallowed`() = runTest {
        shouldThrow<CancellationException> {
            runSurfaceUpdate(tile = {}, widget = { throw CancellationException() })
        }
    }

    @Test
    fun `cancellation from the tile update propagates and is never swallowed`() = runTest {
        shouldThrow<CancellationException> {
            runSurfaceUpdate(tile = { throw CancellationException() }, widget = {})
        }
    }
}
