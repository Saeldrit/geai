package com.github.saeldrit.geai.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomTest {

    @Test
    fun `renders a whole xml element with attributes and closing tag`() {
        val atom = Atom(id = "psi:com.app.X", tag = "sym", attrs = mapOf("id" to "psi:com.app.X", "anchor" to "psi:com.app.X"), body = "fun x(): Int")
        val out = atom.render()
        assertTrue(out.startsWith("<sym "))
        assertTrue("has unambiguous closing boundary", out.trimEnd().endsWith("</sym>"))
        assertTrue(out.contains("anchor=\"psi:com.app.X\""))
    }

    @Test
    fun `escapes angle brackets so content cannot break the boundary`() {
        val atom = Atom(id = "f", tag = "sym", attrs = emptyMap(), body = "List<String> </sym> trick")
        val out = atom.render()
        assertTrue("inner < is escaped", out.contains("List&lt;String&gt;"))
        assertFalse("no stray closing tag inside body", out.removeSuffix("</sym>").contains("</sym>"))
    }

    @Test
    fun `protected flag is carried for budget protection`() {
        val rule = Atom(id = "r", tag = "rule", attrs = mapOf("kind" to "INVARIANT"), body = "x>=0", protected = true)
        assertTrue(rule.protected)
        assertEquals("rule", rule.tag)
    }
}
