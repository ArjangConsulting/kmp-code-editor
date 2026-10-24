package com.arjangconsulting.codeeditor

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
private data class SerializedTextRange(val start: TextPosition, val end: TextPosition)

internal object TextRangeSerializer : KSerializer<TextRange> {
    override val descriptor: SerialDescriptor = SerializedTextRange.serializer().descriptor

    override fun serialize(encoder: Encoder, value: TextRange) {
        encoder.encodeSerializableValue(SerializedTextRange.serializer(), SerializedTextRange(value.start, value.end))
    }

    override fun deserialize(decoder: Decoder): TextRange {
        val range = decoder.decodeSerializableValue(SerializedTextRange.serializer())
        require(range.start <= range.end) { "Completion edit range must be ordered" }
        return TextRange(range.start, range.end)
    }
}
