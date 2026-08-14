package eu.darken.amply.upgrade.core

import eu.darken.amply.common.serialization.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * The stored FOSS unlock. A persisted wire format: every property and enum constant carries an
 * explicit `@SerialName`, because a Kotlin-side rename must never silently reset a supporter's
 * unlock (and their "supporter since" date) to nothing.
 */
@Serializable
data class FossUpgrade(
    @SerialName("upgradedAt") @Serializable(with = InstantSerializer::class) val upgradedAt: Instant,
    @SerialName("upgradeType") val upgradeType: Type,
) {
    @Serializable
    enum class Type {
        @SerialName("GITHUB_SPONSORS") GITHUB_SPONSORS,
        ;
    }
}
