package com.github.saeldrit.geai.graph

import com.github.saeldrit.geai.spec.SpecItem
import com.github.saeldrit.geai.spec.SpecStore
import com.github.saeldrit.geai.tools.fs.FsPaths
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager

/**
 * Builds the derived GRACE graph from two sources: the project's JVM source structure (files ->
 * classes -> methods, plus inheritance) via PSI, and the spec layer (governance edges linking code
 * anchors to the Category-A rules that govern them). Bounded by [MAX_NODES] so a huge project can
 * never produce a runaway graph; the code pass degrades cleanly to nothing on non-JVM hosts.
 *
 * Call edges (calls/reads/writes) are intentionally deferred — they are the expensive pass and are
 * not needed for governance navigation, which is the Ф3 value.
 */
object GraphIndexer {

    private const val MAX_NODES = 8000
    private val SOURCE_EXT = setOf("java", "kt")

    fun reindex(project: Project): CodeGraph {
        val nodes = LinkedHashMap<String, GraphNode>()
        val edges = LinkedHashSet<GraphEdge>()

        indexCode(project, nodes, edges)
        indexSpecs(project, nodes, edges)

        return CodeGraph(nodes.values.toList(), edges.toList())
    }

    private fun indexCode(project: Project, nodes: MutableMap<String, GraphNode>, edges: MutableSet<GraphEdge>) {
        try {
            ReadAction.run<RuntimeException> {
                val fileIndex = ProjectFileIndex.getInstance(project)
                val psiManager = PsiManager.getInstance(project)
                fileIndex.iterateContent { vf ->
                    if (nodes.size >= MAX_NODES) return@iterateContent false
                    if (vf.isDirectory || !fileIndex.isInSourceContent(vf) || vf.extension?.lowercase() !in SOURCE_EXT) {
                        return@iterateContent true
                    }
                    val owner = psiManager.findFile(vf) as? PsiClassOwner ?: return@iterateContent true
                    val fileId = "file:${FsPaths.relativize(project, vf)}"
                    nodes.putIfAbsent(fileId, GraphNode(fileId, NodeKind.FILE, vf.name, "file:${FsPaths.relativize(project, vf)}", null, emptyList()))
                    owner.classes.forEach { psiClass -> indexClass(psiClass, fileId, nodes, edges) }
                    true
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Throwable) {
            // Non-JVM host (no Java PSI) or transient PSI issue: spec subgraph still builds.
        }
    }

    private fun indexClass(psiClass: PsiClass, fileId: String, nodes: MutableMap<String, GraphNode>, edges: MutableSet<GraphEdge>) {
        val fqName = psiClass.qualifiedName ?: return
        val classId = "psi:$fqName"
        nodes.putIfAbsent(classId, GraphNode(classId, NodeKind.SYMBOL, psiClass.name ?: fqName, classId, classSummary(psiClass), emptyList()))
        edges.add(GraphEdge(fileId, classId, EdgeKind.CONTAINS))

        psiClass.superClass?.qualifiedName?.takeIf { it != "java.lang.Object" }?.let { addImplements(classId, it, nodes, edges) }
        psiClass.interfaces.mapNotNull { it.qualifiedName }.forEach { addImplements(classId, it, nodes, edges) }

        psiClass.methods.forEach { method ->
            val methodId = "$classId#${method.name}"
            if (nodes.size < MAX_NODES) {
                nodes.putIfAbsent(methodId, GraphNode(methodId, NodeKind.SYMBOL, "${psiClass.name}#${method.name}", methodId, null, emptyList()))
                edges.add(GraphEdge(classId, methodId, EdgeKind.CONTAINS))
            }
        }
    }

    private fun addImplements(classId: String, superFq: String, nodes: MutableMap<String, GraphNode>, edges: MutableSet<GraphEdge>) {
        val superId = "psi:$superFq"
        nodes.putIfAbsent(superId, GraphNode(superId, NodeKind.SYMBOL, superFq.substringAfterLast('.'), superId, null, emptyList()))
        edges.add(GraphEdge(classId, superId, EdgeKind.IMPLEMENTS))
    }

    private fun classSummary(psiClass: PsiClass): String? {
        val kind = when {
            psiClass.isInterface -> "interface"
            psiClass.isEnum -> "enum"
            else -> "class"
        }
        return "$kind ${psiClass.qualifiedName}"
    }

    private fun indexSpecs(project: Project, nodes: MutableMap<String, GraphNode>, edges: MutableSet<GraphEdge>) {
        SpecStore.getInstance(project).list().forEach { spec ->
            val specId = "spec:${spec.id}"
            nodes.putIfAbsent(specId, GraphNode(specId, NodeKind.SPEC, spec.title, null, spec.domain, emptyList()))
            spec.items.forEachIndexed { index, item ->
                if (item.kind.isReference) linkReference(specId, item, nodes, edges) else addContentItem(specId, spec.id, index, item, nodes, edges)
            }
        }
    }

    private fun addContentItem(specId: String, sid: String, index: Int, item: SpecItem, nodes: MutableMap<String, GraphNode>, edges: MutableSet<GraphEdge>) {
        val nodeKind = runCatching { NodeKind.valueOf(item.kind.name) }.getOrNull() ?: return
        val itemId = "$specId#${item.kind.name}:${item.id ?: index}"
        nodes.putIfAbsent(itemId, GraphNode(itemId, nodeKind, item.id ?: item.kind.name, null, item.body.take(200), item.tags))
        edges.add(GraphEdge(specId, itemId, EdgeKind.CONTAINS))
    }

    private fun linkReference(specId: String, item: SpecItem, nodes: MutableMap<String, GraphNode>, edges: MutableSet<GraphEdge>) {
        val ref = item.ref ?: return
        val targetId = ref // anchors are used verbatim as node ids, matching the code subgraph
        val kind = if (ref.startsWith("openapi:")) NodeKind.CONTRACT else if (ref.startsWith("file:")) NodeKind.FILE else NodeKind.SYMBOL
        nodes.putIfAbsent(targetId, GraphNode(targetId, kind, ref.substringAfterLast('/').ifBlank { ref }, ref, null, item.tags))
        edges.add(GraphEdge(specId, targetId, EdgeKind.REFS))
        edges.add(GraphEdge(targetId, specId, EdgeKind.GOVERNED_BY))
    }
}
