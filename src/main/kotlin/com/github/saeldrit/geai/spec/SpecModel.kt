package com.github.saeldrit.geai.spec

/**
 * GRACE spec model — Category-A knowledge that the code cannot express: invariants, formulas,
 * state machines, design intent, policies, plus anchored references to Category-B contracts/symbols
 * (stored as a `ref`, never a copy). Specs live in `<project>/spec/<id>.spec.xml` and are the
 * source of truth for intent; references are validated against live ground truth via the anchor layer.
 */
enum class SpecItemKind(val tag: String, val isReference: Boolean) {
    INVARIANT("invariant", false),
    FORMULA("formula", false),
    STATE_MACHINE("state-machine", false),
    INTENT("intent", false),
    POLICY("policy", false),
    CONTRACT("contract", true),
    GOVERNED_BY("governed-by", true);

    companion object {
        fun fromTag(tag: String): SpecItemKind? = entries.firstOrNull { it.tag == tag }
        fun fromName(name: String): SpecItemKind? = entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * One spec entry. Content kinds carry [body] (read verbatim); reference kinds carry [ref] (an anchor
 * like `openapi:…` / `psi:…`) plus [refHash] — the resolved content hash recorded at write time, used
 * by drift detection.
 */
data class SpecItem(
    val kind: SpecItemKind,
    val id: String?,
    val tags: List<String>,
    val ref: String?,
    val refHash: String?,
    val body: String,
)

/** One spec document = one `spec/<id>.spec.xml` file. */
data class Spec(
    val id: String,
    val title: String,
    val domain: String?,
    val relativePath: String,
    val items: List<SpecItem>,
)
