package com.github.saeldrit.geai.anchor

import java.security.MessageDigest

data class ResolvedAnchor(
    val ref: String,
    val kind: String,
    val location: String?,
    val signature: String?,
    val content: String,
    val contentHash: String,
) {
    companion object {
        fun of(
            ref: String,
            kind: String,
            location: String?,
            signature: String?,
            content: String,
            hashSource: String = content,
        ): ResolvedAnchor =
            ResolvedAnchor(ref, kind, location, signature, content, sha256(hashSource))

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

class AnchorException(message: String) : Exception(message)
