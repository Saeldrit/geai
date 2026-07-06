package com.github.saeldrit.geai.llm

import com.github.saeldrit.geai.llm.http.JsonSupport
import com.google.gson.JsonElement

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
) {
    /** [PERF] Lazily parsed JSON Schema element — avoids re-parsing the same schema string every request. */
    val parsedSchema: JsonElement by lazy { JsonSupport.parseElement(parametersJsonSchema) }
}
