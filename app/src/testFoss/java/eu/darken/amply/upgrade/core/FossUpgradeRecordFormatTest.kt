package eu.darken.amply.upgrade.core

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.datastore.kotlinxReader
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The FOSS unlock is a **stored format**: it outlives the process that wrote it and there is no
 * receipt to re-derive it from. Pinning the exact JSON here means a rename or a reordered enum fails
 * this test instead of silently un-upgrading a supporter on the next launch.
 */
class FossUpgradeRecordFormatTest {

    private val json: Json = SerializationModule.json()

    @Test
    fun `the unlock encodes to the pinned shape`() {
        val record = FossUpgrade(
            upgradedAt = Instant.ofEpochMilli(1_700_000_000_000L),
            upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
        )

        json.encodeToString(FossUpgrade.serializer(), record) shouldBe
            """{"upgradedAt":"2023-11-14T22:13:20Z","upgradeType":"GITHUB_SPONSORS"}"""
    }

    @Test
    fun `the unlock decodes from stored JSON`() {
        val stored = """{"upgradedAt":"1970-01-01T00:00:00Z","upgradeType":"GITHUB_SPONSORS"}"""

        json.decodeFromString(FossUpgrade.serializer(), stored) shouldBe FossUpgrade(
            upgradedAt = Instant.EPOCH,
            upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
        )
    }

    @Test
    fun `a record written by a newer build still decodes`() {
        val stored = """{"upgradedAt":"1970-01-01T00:00:00Z","upgradeType":"GITHUB_SPONSORS","tier":"gold"}"""

        json.decodeFromString(FossUpgrade.serializer(), stored).upgradeType shouldBe
            FossUpgrade.Type.GITHUB_SPONSORS
    }

    /**
     * The key is read with `fallbackToDefault = true`, so a corrupt value reads as "no unlock"
     * rather than killing the entitlement flow for good. The user can re-run the sponsor flow, which
     * rewrites the record.
     */
    @Test
    fun `a corrupt record reads as absent instead of throwing`() {
        val reader = kotlinxReader<FossUpgrade?>(json, defaultValue = null, fallbackToDefault = true)
        val key = stringPreferencesKey("upgrade.foss.v1")

        reader(mutablePreferencesOf(key to "{not json")[key]) shouldBe null
        reader(mutablePreferencesOf(key to """{"upgradeType":"PATREON"}""")[key]) shouldBe null
        reader(null) shouldBe null
    }
}
