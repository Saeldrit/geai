package com.github.saeldrit.geai.anchor.resolvers

import com.github.saeldrit.geai.anchor.AnchorException
import com.github.saeldrit.geai.anchor.AnchorResolver
import com.github.saeldrit.geai.anchor.ResolvedAnchor
import com.github.saeldrit.geai.tools.fs.FsPaths
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Resolves `openapi:<doc>#<json-pointer>` against a generated OpenAPI document — the live API
 * contract, never a hand-copied duplicate. `<doc>` is a project-relative path or a short name
 * matched against common locations; the pointer follows RFC 6901 (`~1`=/, `~0`=~). Spring and
 * peers generate this from annotations, so the resolved fragment is always current.
 */
object OpenApiAnchorResolver : AnchorResolver {

    override val scheme = "openapi"

    private val PRETTY = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private const val MAX_CONTENT = 4000

    private val FALLBACKS = listOf(
        "openapi.json", "api-docs.json", "v3/api-docs.json",
        "src/main/resources/openapi.json", "src/main/resources/static/openapi.json",
        "build/openapi/openapi.json", "build/resources/main/openapi.json",
    )

    override fun resolve(locator: String, project: Project): ResolvedAnchor {
        val hash = locator.indexOf('#')
        val docPart = (if (hash >= 0) locator.substring(0, hash) else locator).trim()
        val pointer = if (hash >= 0) locator.substring(hash + 1) else ""

        return ReadAction.compute<ResolvedAnchor, RuntimeException> {
            val candidates = buildList {
                if (docPart.contains('/') || docPart.contains('.')) add(docPart) else add("$docPart.json")
                addAll(FALLBACKS)
            }
            val file = candidates.firstNotNullOfOrNull { FsPaths.resolve(project, it) }
                ?: throw AnchorException("openapi: document not found. Tried: ${candidates.joinToString()}")
            if (file.extension.equals("yaml", true) || file.extension.equals("yml", true)) {
                throw AnchorException("openapi: YAML not supported yet — point to the JSON form (e.g. /v3/api-docs).")
            }

            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: throw AnchorException("openapi: cannot load ${file.path}")
            val root = runCatching { JsonParser.parseString(document.charsSequence.toString()) }
                .getOrElse { throw AnchorException("openapi: not valid JSON: ${file.path}") }

            val node = walk(root, pointer)
            val rel = FsPaths.relativize(project, file)
            ResolvedAnchor.of(
                ref = "openapi:$locator",
                kind = "contract",
                location = "$rel#$pointer",
                signature = node.takeIf { it.isJsonObject }?.asJsonObject?.get("summary")?.asString,
                content = PRETTY.toJson(node).take(MAX_CONTENT),
            )
        }
    }

    private fun walk(root: JsonElement, pointer: String): JsonElement {
        if (pointer.isBlank() || pointer == "/") return root
        var current = root
        pointer.trimStart('/').split('/').forEach { rawToken ->
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when {
                current.isJsonObject -> current.asJsonObject.get(token)
                    ?: throw AnchorException("openapi: JSON pointer dead-ends at '$token'")

                current.isJsonArray -> {
                    val index = token.toIntOrNull()
                        ?: throw AnchorException("openapi: array index expected, got '$token'")
                    current.asJsonArray.takeIf { index in 0 until it.size() }?.get(index)
                        ?: throw AnchorException("openapi: array index out of range: $index")
                }

                else -> throw AnchorException("openapi: cannot descend into '$token' (leaf value)")
            }
        }
        return current
    }
}
