package eu.darken.amply.charging.core.access.shizuku

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream

class BoundedProcessTest {

    @Test
    fun `reader captures the full stream under the cap`() {
        val reader = BoundedStreamReader(ByteArrayInputStream("hello world".toByteArray()), capBytes = 1024)
        reader.run()
        reader.overflow.shouldBeFalse()
        reader.text() shouldBe "hello world"
    }

    @Test
    fun `reader flags overflow past the cap`() {
        val reader = BoundedStreamReader(ByteArrayInputStream(ByteArray(200) { 'a'.code.toByte() }), capBytes = 100)
        reader.run()
        reader.overflow.shouldBeTrue()
        reader.text().length shouldBeLessThanOrEqual 100
    }

    @Test
    fun `reader drains a writer that would otherwise block on a full pipe`() {
        // > the pipe buffer, so a single blocking write() only completes if the reader drains concurrently.
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        val payload = ByteArray(200_000) { 'z'.code.toByte() }
        val reader = BoundedStreamReader(pipeIn, capBytes = 1_000_000)

        val readerThread = Thread(reader).apply { start() }
        val writerThread = Thread {
            pipeOut.use { it.write(payload) }
        }.apply { start() }

        writerThread.join(5_000)
        readerThread.join(5_000)

        reader.overflow.shouldBeFalse()
        reader.text().length shouldBe 200_000
    }

    @Test
    fun `runBoundedProcess drains both streams past pipe capacity without deadlock`() {
        assumeTrue(File("/bin/sh").exists(), "POSIX shell required")
        val result = runBoundedProcess(
            listOf("/bin/sh", "-c", "yes x | head -c 100000; yes y | head -c 100000 1>&2"),
            timeoutMs = 10_000,
            capBytes = 1_000_000,
        )
        result.exitCode shouldBe 0
        result.stdout.length shouldBe 100_000
        result.stderr.length shouldBe 100_000
    }

    @Test
    fun `runBoundedProcess fails on timeout`() {
        assumeTrue(File("/bin/sh").exists(), "POSIX shell required")
        val error = assertThrows<IllegalStateException> {
            runBoundedProcess(listOf("/bin/sh", "-c", "sleep 30"), timeoutMs = 500, capBytes = 1_000_000)
        }
        error.message!! shouldContain "timed out"
    }

    @Test
    fun `runBoundedProcess fails on oversize output`() {
        assumeTrue(File("/bin/sh").exists(), "POSIX shell required")
        val error = assertThrows<IllegalStateException> {
            runBoundedProcess(listOf("/bin/sh", "-c", "yes | head -c 500000"), timeoutMs = 10_000, capBytes = 1_000)
        }
        error.message!! shouldContain "exceeded"
    }
}
