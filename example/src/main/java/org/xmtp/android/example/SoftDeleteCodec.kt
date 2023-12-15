package org.xmtp.android.example

import com.google.protobuf.kotlin.toByteStringUtf8
import org.xmtp.android.library.codecs.ContentCodec
import org.xmtp.android.library.codecs.ContentTypeId
import org.xmtp.android.library.codecs.ContentTypeIdBuilder
import org.xmtp.android.library.codecs.EncodedContent

val ContentTypeSoftDelete = ContentTypeIdBuilder.builderFromAuthorityId(
    "babak.eth",
    "delete",
    versionMajor = 1,
    versionMinor = 0
)
data class SoftDeleteCodec(override var contentType: ContentTypeId = ContentTypeSoftDelete): ContentCodec<String> {
    override fun decode(content: EncodedContent): String {
        return content.content.toStringUtf8()
    }

    override fun fallback(content: String): String {
        return "message with Id $content is deleted by sender"
    }

    override fun encode(content: String): EncodedContent {
        return EncodedContent.newBuilder().also {
            it.type = ContentTypeSoftDelete
            it.content = content.toByteStringUtf8()
        }.build()
    }
}