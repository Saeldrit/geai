package com.github.saeldrit.geai.anchor

import com.intellij.openapi.project.Project

/**
 * Resolves one anchor scheme to live ground truth. This is GRACE's universality seam: the graph,
 * specs, and doctrine never change across stacks or IDEs — only the set of registered resolvers
 * does. IntelliJ contributes PSI-backed resolvers; a future LSP/CLI host can contribute its own
 * for the same schemes without touching anything above this interface.
 */
interface AnchorResolver {
    /** The scheme this resolver owns, e.g. "file", "psi", "openapi". */
    val scheme: String

    /** Resolve the locator part (everything after `scheme:`). Throws [AnchorException] on failure. */
    fun resolve(locator: String, project: Project): ResolvedAnchor
}
