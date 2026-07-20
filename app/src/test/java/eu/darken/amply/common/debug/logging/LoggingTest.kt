package eu.darken.amply.common.debug.logging

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LoggingTest {
    @Test
    fun installedLoggersReceiveStructuredMessages() {
        val received = mutableListOf<String>()
        val logger = object : Logging.Logger {
            override fun log(
                priority: Logging.Priority,
                tag: String,
                message: String,
                metadata: Map<String, Any>?,
            ) {
                received += "${priority.shortLabel}|$tag|$message|${metadata?.get("attempt")}"
            }
        }

        Logging.install(logger)
        try {
            log(
                tag = logTag("Test"),
                priority = Logging.Priority.INFO,
                metadata = mapOf("attempt" to 2),
            ) { "recorded" }
        } finally {
            Logging.remove(logger)
        }

        received shouldBe listOf("I|AMP:Test|recorded|2")
    }
}
