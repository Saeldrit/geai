package com.github.saeldrit.geai.llm

/**
 * Provider-agnostic description of a callable tool, advertised to the model.
 *
 * [parametersJsonSchema] is a JSON Schema *object* (as a raw JSON string), e.g.
 * `{"type":"object","properties":{...},"required":[...]}`.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)
