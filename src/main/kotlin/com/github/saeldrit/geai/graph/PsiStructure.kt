package com.github.saeldrit.geai.graph

import com.github.saeldrit.geai.spec.SpecStore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/** A neighbour edge resolved live (no materialized graph). [outgoing] = edge points away from the node. */
data class LiveEdge(val kind: EdgeKind, val outgoing: Boolean, val otherId: String, val label: String)

/** A node resolved live: a code class (psi:) or a spec header (spec:). */
data class LiveNode(val id: String, val kind: NodeKind, val name: String, val anchor: String?, val summary: String?)

/**
 * Resolves graph neighbours from IntelliJ's LIVE PSI (code structure) plus the spec overlay
 * (governance) — instead of a materialized snapshot, so it is always fresh and works before/without a
 * graph build (no cold-start wait). Code: CONTAINS (class <-> its methods), IMPLEMENTS (class -> its
 * super types). Governance: GOVERNED_BY (code -> the specs that reference it) and REFS (spec -> its
 * anchors), read from the cheap spec store. Inheritor (IMPLEMENTS-in) lookups stay in `find_implementations`.
 */
object PsiStructure {

    fun neighbors(
        project: Project,
        nodeId: String,
        kind: EdgeKind?,
        direction: GeaiGraphStore.Direction,
        max: Int,
    ): List<LiveEdge> {
        val out = direction != GeaiGraphStore.Direction.IN
        val incoming = direction != GeaiGraphStore.Direction.OUT
        val edges = ArrayList<LiveEdge>()

        if (nodeId.startsWith("psi:") && !DumbService.isDumb(project)) {
            runCatching {
                ReadAction.run<RuntimeException> { resolveCode(project, nodeId, kind, out, incoming, max, edges) }
            }
        }
        if (kind == null || kind == EdgeKind.GOVERNED_BY || kind == EdgeKind.REFS) {
            runCatching { resolveGovernance(project, nodeId, kind, out, edges) }
        }
        return edges.take(max)
    }

    private fun resolveCode(
        project: Project,
        nodeId: String,
        kind: EdgeKind?,
        out: Boolean,
        incoming: Boolean,
        max: Int,
        edges: MutableList<LiveEdge>,
    ) {
        val locator = nodeId.removePrefix("psi:")
        val hash = locator.indexOf('#')
        val fqName = (if (hash >= 0) locator.substring(0, hash) else locator).trim()
        val member = if (hash >= 0) locator.substring(hash + 1).trim().ifBlank { null } else null
        val psiClass = JavaPsiFacade.getInstance(project).findClass(fqName, GlobalSearchScope.allScope(project)) ?: return

        if (member == null) {
            if ((kind == null || kind == EdgeKind.CONTAINS) && out) {
                psiClass.methods.asSequence().mapNotNull { it.name }.distinct().take(max).forEach { m ->
                    edges.add(LiveEdge(EdgeKind.CONTAINS, true, "psi:$fqName#$m", "[SYMBOL] ${psiClass.name}#$m"))
                }
            }
            if ((kind == null || kind == EdgeKind.IMPLEMENTS) && out) {
                psiClass.supers.mapNotNull { it.qualifiedName }
                    .filter { it != "java.lang.Object" && it != "kotlin.Any" }
                    .forEach { s -> edges.add(LiveEdge(EdgeKind.IMPLEMENTS, true, "psi:$s", "[SYMBOL] ${s.substringAfterLast('.')}")) }
            }
        } else if ((kind == null || kind == EdgeKind.CONTAINS) && incoming) {
            // A member's container is its class.
            edges.add(LiveEdge(EdgeKind.CONTAINS, false, "psi:$fqName", "[SYMBOL] ${psiClass.name}"))
        }
    }

    private fun resolveGovernance(project: Project, nodeId: String, kind: EdgeKind?, out: Boolean, edges: MutableList<LiveEdge>) {
        val specs = SpecStore.getInstance(project).list()
        if (nodeId.startsWith("spec:")) {
            if ((kind == null || kind == EdgeKind.REFS) && out) {
                val spec = specs.firstOrNull { it.id == nodeId.removePrefix("spec:") } ?: return
                spec.items.filter { it.kind.isReference && it.ref != null }
                    .forEach { item -> edges.add(LiveEdge(EdgeKind.REFS, true, item.ref!!, "[ref] ${item.ref}")) }
            }
        } else if ((kind == null || kind == EdgeKind.GOVERNED_BY) && out) {
            // Which specs govern this code node? (the inverse of REFS)
            specs.forEach { spec ->
                if (spec.items.any { it.kind.isReference && it.ref == nodeId }) {
                    edges.add(LiveEdge(EdgeKind.GOVERNED_BY, true, "spec:${spec.id}", "[SPEC] ${spec.title}"))
                }
            }
        }
    }

    /**
     * Find nodes live: project classes by name substring (IntelliJ's PsiShortNamesCache — index-backed,
     * no graph build) plus spec headers from the store. Code methods are reachable via graph_neighbors
     * (CONTAINS) and find_symbol; spec items via spec_lookup. Used by graph_query and as bundle seeds.
     */
    fun findNodes(project: Project, kind: NodeKind?, query: String?, limit: Int): List<LiveNode> {
        val needle = query?.lowercase()?.takeIf { it.isNotBlank() }
        val nodes = ArrayList<LiveNode>()
        val wantCode = kind == null || kind == NodeKind.SYMBOL || kind == NodeKind.CONTRACT || kind == NodeKind.FILE
        val wantSpec = kind == null || kind == NodeKind.SPEC

        if (wantCode && !DumbService.isDumb(project)) {
            runCatching {
                ReadAction.run<RuntimeException> {
                    val cache = PsiShortNamesCache.getInstance(project)
                    val scope = GlobalSearchScope.projectScope(project)
                    for (name in cache.allClassNames) {
                        if (nodes.size >= limit) break
                        if (needle != null && !name.lowercase().contains(needle)) continue
                        for (cls in cache.getClassesByName(name, scope)) {
                            val fq = cls.qualifiedName ?: continue
                            nodes.add(LiveNode("psi:$fq", NodeKind.SYMBOL, cls.name ?: fq, "psi:$fq", classSummary(cls)))
                            if (nodes.size >= limit) break
                        }
                    }
                }
            }
        }
        if (wantSpec) {
            runCatching {
                SpecStore.getInstance(project).list().forEach { spec ->
                    if (nodes.size < limit) {
                        val hay = "${spec.id} ${spec.title} ${spec.domain ?: ""}".lowercase()
                        if (needle == null || hay.contains(needle)) {
                            nodes.add(LiveNode("spec:${spec.id}", NodeKind.SPEC, spec.title, null, spec.domain))
                        }
                    }
                }
            }
        }
        return nodes.take(limit)
    }

    private fun classSummary(cls: PsiClass): String = when {
        cls.isInterface -> "interface ${cls.qualifiedName}"
        cls.isEnum -> "enum ${cls.qualifiedName}"
        else -> "class ${cls.qualifiedName}"
    }
}
