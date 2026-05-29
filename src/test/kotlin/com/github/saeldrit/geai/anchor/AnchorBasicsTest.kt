package com.github.saeldrit.geai.anchor

import com.github.saeldrit.geai.spec.SpecItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorBasicsTest {

    @Test
    fun `content hash is deterministic and content-sensitive`() {
        val a = ResolvedAnchor.of("file:x", "file", "x:1-1", null, "hello")
        val b = ResolvedAnchor.of("file:x", "file", "x:1-1", null, "hello")
        val c = ResolvedAnchor.of("file:x", "file", "x:1-1", null, "world")
        assertEquals("same content → same hash", a.contentHash, b.contentHash)
        assertNotEquals("different content → different hash", a.contentHash, c.contentHash)
        assertEquals("sha-256 hex length", 64, a.contentHash.length)
    }

    @Test
    fun `known anchor schemes are registered`() {
        val schemes = AnchorResolvers.schemes()
        assertTrue("file scheme", "file" in schemes)
        assertTrue("psi scheme", "psi" in schemes)
        assertTrue("openapi scheme", "openapi" in schemes)
    }

    @Test
    fun `spec item kinds map by tag and name and flag references`() {
        assertEquals(SpecItemKind.INVARIANT, SpecItemKind.fromName("invariant"))
        assertEquals(SpecItemKind.GOVERNED_BY, SpecItemKind.fromTag("governed-by"))
        assertTrue("CONTRACT is a reference kind", SpecItemKind.CONTRACT.isReference)
        assertFalse("POLICY is a content kind", SpecItemKind.POLICY.isReference)
    }
}
