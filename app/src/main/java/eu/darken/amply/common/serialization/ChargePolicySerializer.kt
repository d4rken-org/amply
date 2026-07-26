package eu.darken.amply.common.serialization

import eu.darken.amply.charging.core.ChargePolicy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Persists a [ChargePolicy] as its [ChargePolicy.stableId] string.
 *
 * Deliberately reuses the existing stable-id contract rather than introducing a second encoding:
 * the same strings already travel through service intents, the widget and the tile, so a policy has
 * exactly one wire representation across the whole app. It also keeps the sealed hierarchy free to
 * change shape — only [ChargePolicy.fromStableId] defines what is readable.
 *
 * An unknown id throws [SerializationException]; the calling record decides whether that means "no
 * record" (whole-record fallback) or "this one field is unusable" (per-field validation).
 */
object ChargePolicySerializer : KSerializer<ChargePolicy> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("eu.darken.amply.ChargePolicy", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ChargePolicy) {
        encoder.encodeString(value.stableId)
    }

    override fun deserialize(decoder: Decoder): ChargePolicy {
        val raw = decoder.decodeString()
        return ChargePolicy.fromStableId(raw)
            ?: throw SerializationException("Unknown ChargePolicy stableId: $raw")
    }
}
