package com.github.saeldrit.geai.llm.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSupportTest {

    @Test
    fun `humanError extracts the provider error message`() {
        assertEquals(
            "model not found: deepseek-v4-pro",
            JsonSupport.humanError("""{"type":"error","error":{"type":"not_found_error","message":"model not found: deepseek-v4-pro"}}"""),
        )
        assertEquals(
            "Incorrect API key provided",
            JsonSupport.humanError("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""),
        )
    }

    @Test
    fun `humanError falls back to a trimmed raw body for non-JSON`() {
        val out = JsonSupport.humanError("<html>  <body>502 Bad Gateway</body>\n</html>")
        assertTrue("keeps the gist", out.contains("502 Bad Gateway"))
        assertTrue("whitespace collapsed and bounded", out.length <= 500 && !out.contains("\n"))
    }
}
