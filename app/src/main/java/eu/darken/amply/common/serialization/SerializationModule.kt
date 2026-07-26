package eu.darken.amply.common.serialization

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * The single [Json] used for everything Amply persists. There is exactly one, so it needs no
 * qualifier.
 *
 * `ignoreUnknownKeys` lets an older build read a record a newer one wrote. It does **not** cover
 * renames, which is why every persisted property and enum constant carries an explicit `@SerialName`
 * — these records are a stored wire format, and a Kotlin-side rename must never silently reset a
 * user's charge-session or restore state to defaults.
 */
@Module
@InstallIn(SingletonComponent::class)
object SerializationModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
