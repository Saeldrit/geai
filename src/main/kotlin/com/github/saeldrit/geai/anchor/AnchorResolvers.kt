package com.github.saeldrit.geai.anchor

import com.github.saeldrit.geai.anchor.resolvers.FileAnchorResolver
import com.github.saeldrit.geai.anchor.resolvers.OpenApiAnchorResolver
import com.github.saeldrit.geai.anchor.resolvers.PsiAnchorResolver
import com.intellij.openapi.project.Project

object AnchorResolvers {

    private val resolvers: List<AnchorResolver> = listOf(
        FileAnchorResolver,
        PsiAnchorResolver,
        OpenApiAnchorResolver,
    )

    private val byScheme: Map<String, AnchorResolver> = resolvers.associateBy { it.scheme }

    fun schemes(): Set<String> = byScheme.keys

    fun resolve(ref: String, project: Project): ResolvedAnchor {
        val separator = ref.indexOf(':')
        if (separator <= 0) {
            throw AnchorException("Malformed anchor '$ref' — expected '<scheme>:<locator>'. Schemes: ${schemes().joinToString()}")
        }
        val scheme = ref.substring(0, separator)
        val locator = ref.substring(separator + 1)
        val resolver = byScheme[scheme]
            ?: throw AnchorException("Unknown anchor scheme '$scheme'. Known: ${schemes().joinToString()}")
        if (locator.isBlank()) throw AnchorException("Empty locator in anchor '$ref'.")
        return resolver.resolve(locator, project)
    }
}
