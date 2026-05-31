package com.github.saeldrit.geai.bundle

/**
 * One atomic context unit, rendered as a self-contained XML element. XML (not MD/YAML) because the
 * closing `</tag>` gives the model an UNAMBIGUOUS boundary — the atom is whole or absent, never torn
 * mid-content. Identity lives in attributes (id/anchor) so the atom is addressable. Body is escaped
 * so a stray `<`/`</tag>` inside content can't break the boundary.
 */
data class Atom(
    val id: String,
    val tag: String,
    val attrs: Map<String, String>,
    val body: String,
    /** Protected atoms (governing rules) are never dropped under budget pressure. */
    val protected: Boolean = false,
) {
    fun render(): String {
        val attrStr = attrs.entries.joinToString("") { " ${it.key}=\"${escape(it.value)}\"" }
        val inner = escape(body).trim()
        return "<$tag$attrStr>\n$inner\n</$tag>"
    }

    /** Rendered size in characters — the unit the budget is measured in. */
    val chars: Int get() = render().length

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
