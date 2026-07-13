package com.github.saeldrit.geai.graph

import com.github.saeldrit.geai.spec.SpecStore
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/** A neighbour edge resolved live (no materialized graph). [outgoing] = edge points away from the node. */
data class LiveEdge(val kind: EdgeKind, val outgoing: Boolean, val otherId: String, val label: String)

data class LiveNode(val id: String, val kind: NodeKind, val name: String, val anchor: String?, val summary: String?)

enum class Direction { IN, OUT, BOTH }

/**
 * Resolves graph neighbours from IntelliJ's LIVE PSI (code structure) plus the spec overlay
 * (governance) — instead of a materialized snapshot, so it is always fresh and works before/without a
 * graph build (no cold-start wait). Code: CONTAINS (class <-> its methods), IMPLEMENTS (class -> its
 * super types). Governance: GOVERNED_BY (code -> the specs that reference it) and REFS (spec -> its
 * anchors), read from the cheap spec store. Inheritor (IMPLEMENTS-in) lookups stay in `find_implementations`.
 */
object PsiStructure {

    private val NON_WORD = Regex("[^a-z0-9]+")

    fun neighbors(
        project: Project,
        nodeId: String,
        kind: EdgeKind?,
        direction: Direction,
        max: Int,
    ): List<LiveEdge> {
        val out = direction != Direction.IN
        val incoming = direction != Direction.OUT
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
        val terms = query?.lowercase()?.split(NON_WORD)?.filter { it.length >= 3 }?.distinct().orEmpty()
        fun score(haystack: String): Int = if (terms.isEmpty()) 1 else terms.count { haystack.contains(it) }

        val wantCode = kind == null || kind == NodeKind.SYMBOL || kind == NodeKind.CONTRACT || kind == NodeKind.FILE
        val wantSpec = kind == null || kind == NodeKind.SPEC
        val scored = ArrayList<Pair<LiveNode, Int>>()

        if (wantCode && !DumbService.isDumb(project)) {
            runCatching {
                ReadAction.run<RuntimeException> {
                    val cache = PsiShortNamesCache.getInstance(project)
                    val scope = GlobalSearchScope.projectScope(project)
                    for (name in cache.allClassNames) {
                        if (scored.size >= limit * 8) break
                        val hits = score(name.lowercase())
                        if (hits == 0) continue
                        for (cls in cache.getClassesByName(name, scope)) {
                            val fq = cls.qualifiedName ?: continue
                            scored.add(LiveNode("psi:$fq", NodeKind.SYMBOL, cls.name ?: fq, "psi:$fq", classSummary(cls)) to hits)
                        }
                    }
                }
            }.onFailure { if (it is ProcessCanceledException) throw it }
        }
        if (wantSpec) {
            runCatching {
                SpecStore.getInstance(project).list().forEach { spec ->
                    val hits = score("${spec.id} ${spec.title} ${spec.domain ?: ""}".lowercase())
                    if (hits > 0) scored.add(LiveNode("spec:${spec.id}", NodeKind.SPEC, spec.title, null, spec.domain) to hits)
                }
            }
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun classSummary(cls: PsiClass): String = when {
        cls.isInterface -> "interface ${cls.qualifiedName}"
        cls.isEnum -> "enum ${cls.qualifiedName}"
        else -> "class ${cls.qualifiedName}"
    }


    /** Resolve a node id (psi: class/member, spec:, or other anchor) to a [GraphNode] descriptor. */
    fun resolveNode(project: Project, id: String): GraphNode? {
        if (id.startsWith("spec:")) {
            val spec = SpecStore.getInstance(project).find(id.removePrefix("spec:")) ?: return null
            return GraphNode(id, NodeKind.SPEC, spec.title, null, spec.domain, emptyList())
        }
        if (id.startsWith("psi:") && !DumbService.isDumb(project)) {
            return runCatching {
                ReadAction.compute<GraphNode?, RuntimeException> {
                    val (fq, member) = splitPsi(id)
                    val cls = JavaPsiFacade.getInstance(project).findClass(fq, GlobalSearchScope.allScope(project))
                        ?: return@compute null
                    if (member == null) GraphNode(id, NodeKind.SYMBOL, cls.name ?: fq, id, classSummary(cls), emptyList())
                    else GraphNode(id, NodeKind.SYMBOL, "${cls.name}#$member", id, null, emptyList())
                }
            }.getOrElse { if (it is ProcessCanceledException) throw it else null }
        }
        return GraphNode(id, NodeKind.FILE, id.substringAfterLast('/').ifBlank { id }, id, null, emptyList())
    }

    fun bundleSeeds(project: Project, query: String, seedIds: List<String>, limit: Int): List<GraphNode> =
        if (seedIds.isNotEmpty()) {
            seedIds.mapNotNull { resolveNode(project, it) }
        } else {
            findNodes(project, null, query, limit)
                .map { GraphNode(it.id, it.kind, it.name, it.anchor, it.summary, emptyList()) }
        }

    /**
     * Build a small [CodeGraph] live from PSI around [seeds] (BFS to [hops] over class->methods and
     * class->super edges) plus the governance edges (code -> the specs that reference it, specs loaded
     * ONCE). Replaces the full materialized snapshot for the context bundle: fresh, no full PSI walk,
     * and the bundle's downstream expand/rank/resolve/pack stay unchanged.
     */
    fun localGraph(project: Project, seeds: List<GraphNode>, hops: Int, maxNodes: Int): CodeGraph {
        val nodes = LinkedHashMap<String, GraphNode>()
        val edges = LinkedHashSet<GraphEdge>()
        seeds.forEach { nodes[it.id] = it }

        if (!DumbService.isDumb(project)) {
            runCatching {
                ReadAction.run<RuntimeException> {
                    var frontier = seeds.map { it.id }
                    for (depth in 1..hops.coerceAtLeast(1)) {
                        if (nodes.size >= maxNodes) break
                        val next = ArrayList<String>()
                        frontier.forEach { id ->
                            codeEdges(project, id).forEach { (edge, other) ->
                                edges.add(edge)
                                if (other.id !in nodes && nodes.size < maxNodes) {
                                    nodes[other.id] = other
                                    next.add(other.id)
                                }
                            }
                        }
                        frontier = next
                    }
                }
            }.onFailure { if (it is ProcessCanceledException) throw it }
        }

        val specs = SpecStore.getInstance(project).list()
        if (specs.isNotEmpty()) {
            nodes.keys.toList().forEach { id ->
                if (!id.startsWith("spec:")) {
                    specs.forEach { spec ->
                        if (spec.items.any { it.kind.isReference && it.ref == id }) {
                            val specId = "spec:${spec.id}"
                            nodes.putIfAbsent(specId, GraphNode(specId, NodeKind.SPEC, spec.title, null, spec.domain, emptyList()))
                            edges.add(GraphEdge(id, specId, EdgeKind.GOVERNED_BY))
                        }
                    }
                }
            }
        }
        return CodeGraph(nodes.values.toList(), edges.toList())
    }

    private fun codeEdges(project: Project, id: String): List<Pair<GraphEdge, GraphNode>> {
        if (!id.startsWith("psi:")) return emptyList()
        val (fq, member) = splitPsi(id)
        if (member != null) return emptyList()
        val cls = JavaPsiFacade.getInstance(project).findClass(fq, GlobalSearchScope.allScope(project)) ?: return emptyList()
        val result = ArrayList<Pair<GraphEdge, GraphNode>>()
        cls.methods.asSequence().mapNotNull { it.name }.distinct().forEach { m ->
            val mid = "psi:$fq#$m"
            result.add(GraphEdge(id, mid, EdgeKind.CONTAINS) to GraphNode(mid, NodeKind.SYMBOL, "${cls.name}#$m", mid, null, emptyList()))
        }
        cls.supers.mapNotNull { it.qualifiedName }
            .filter { it != "java.lang.Object" && it != "kotlin.Any" }
            .forEach { s ->
                val sid = "psi:$s"
                result.add(GraphEdge(id, sid, EdgeKind.IMPLEMENTS) to GraphNode(sid, NodeKind.SYMBOL, s.substringAfterLast('.'), sid, "class $s", emptyList()))
            }
        (cls.containingFile as? PsiClassOwner)?.classes?.forEach { sib ->
            val sfq = sib.qualifiedName
            if (sfq != null && sfq != fq) {
                val siblingId = "psi:$sfq"
                result.add(GraphEdge(id, siblingId, EdgeKind.CONTAINS) to GraphNode(siblingId, NodeKind.SYMBOL, sib.name ?: sfq, siblingId, classSummary(sib), emptyList()))
            }
        }
        return result
    }

    private fun splitPsi(id: String): Pair<String, String?> {
        val locator = id.removePrefix("psi:")
        val hash = locator.indexOf('#')
        val fq = (if (hash >= 0) locator.substring(0, hash) else locator).trim()
        val member = if (hash >= 0) locator.substring(hash + 1).trim().ifBlank { null } else null
        return fq to member
    }
}
