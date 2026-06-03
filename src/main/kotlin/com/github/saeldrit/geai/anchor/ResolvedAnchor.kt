package com.github.saeldrit.geai.anchor

import java.security.MessageDigest

/**
 * The live ground truth behind a GRACE anchor. Category-B knowledge (signatures, contracts, DTOs)
 * is never cached as prose — it is resolved on demand into this shape, so the agent always sees
 * what the code actually says right now. [contentHash] backs drift detection: if a re-resolve
 * produces a different hash, anything that referenced the old value is stale.
 */
data class ResolvedAnchor(
    val ref: String,
    /** Coarse category of what was resolved: "file" | "symbol" | "contract". */
    val kind: String,
    /** Human-pointable location, e.g. "src/Foo.kt:40-58" or "openapi.json#/paths/...". */
    val location: String?,
    /** One-line signature/declaration when applicable (symbol header, endpoint summary). */
    val signature: String?,
    /** The resolved live content (may be truncated by the resolver to stay context-cheap). */
    val content: String,
    /** SHA-256 of [content] at resolution time — the drift fingerprint. */
    val contentHash: String,
) {
    companion object {
        fun of(
            ref: String,
            kind: String,
            location: String?,
            signature: String?,
            content: String,
            /** Text to fingerprint for drift; defaults to [content]. Pass display-stripped text when the
             *  shown content carries positional decoration (e.g. line-number prefixes) that must not
             *  affect the drift hash. */
            hashSource: String = content,
        ): ResolvedAnchor =
            ResolvedAnchor(ref, kind, location, signature, content, sha256(hashSource))

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

/** Raised by a resolver when an anchor is malformed, unknown, or cannot be resolved. */
class AnchorException(message: String) : Exception(message)
