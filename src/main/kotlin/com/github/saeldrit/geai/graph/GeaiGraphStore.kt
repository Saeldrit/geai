package com.github.saeldrit.geai.graph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Persists the derived GRACE graph at `<project>/.geai/graph/graph.xml` and serves cheap queries
 * from an in-memory cache. The file is rebuildable from code plus specs, so it is gitignored, not a
 * source of truth. All access is synchronized; load/write failures are logged, never fatal.
 */
@Service(Service.Level.PROJECT)
class GeaiGraphStore(private val project: Project) {

    companion object {
        fun getInstance(project: Project): GeaiGraphStore = project.service()
    }

    private val lock = Any()

    @Volatile
    private var cache: CodeGraph? = null

    @Volatile
    private var building = false

    /**
     * Build the graph in the BACKGROUND if it is not yet populated — never block the caller. The full
     * PSI reindex can take many seconds on a large project, so doing it inline on the agent's first
     * turn stalled the whole turn before the model was even called. This kicks the reindex onto a
     * pooled thread (idempotent: a second call while one is running is a no-op) so the bundle is ready
     * for the NEXT turn; the current turn proceeds without it and the model navigates normally.
     */
    fun ensureBuiltInBackground() {
        synchronized(lock) {
            val current = cache ?: load().also { cache = it }
            if (current.nodes.isNotEmpty() || building) return
            building = true
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val graph = GraphIndexer.reindex(project)
                if (graph.nodes.isNotEmpty()) replaceAll(graph)
            } catch (_: ProcessCanceledException) {
                // IDE was indexing / cancelled — leave the graph empty so the next turn retries.
            } catch (t: Throwable) {
                thisLogger().warn("Geai: background graph build failed", t)
            } finally {
                building = false
            }
        }
    }

    private fun file(): Path {
        val base = project.basePath
        val dir = if (base != null) Paths.get(base, ".geai", "graph") else Paths.get(PathManager.getSystemPath(), "geai", project.locationHash, "graph")
        Files.createDirectories(dir)
        return dir.resolve("graph.xml")
    }

    fun graph(): CodeGraph = synchronized(lock) {
        cache?.let { return it }
        val loaded = load()
        cache = loaded
        loaded
    }

    fun replaceAll(graph: CodeGraph) = synchronized(lock) {
        write(graph)
        cache = graph
    }

    fun findNode(id: String): GraphNode? = graph().nodes.firstOrNull { it.id == id }

    fun query(kind: NodeKind?, query: String?, tag: String?, limit: Int): List<GraphNode> {
        val needle = query?.lowercase()?.takeIf { it.isNotBlank() }
        return graph().nodes.asSequence()
            .filter { node ->
                (kind == null || node.kind == kind) &&
                    (tag == null || node.tags.any { it.equals(tag, ignoreCase = true) }) &&
                    (needle == null || node.matches(needle))
            }
            .take(limit)
            .toList()
    }

    /** Edges touching [nodeId] in the requested [direction], optionally filtered by [edgeKind]. */
    fun neighbors(nodeId: String, edgeKind: EdgeKind?, direction: Direction): List<GraphEdge> =
        graph().edges.filter { edge ->
            (edgeKind == null || edge.kind == edgeKind) && when (direction) {
                Direction.OUT -> edge.from == nodeId
                Direction.IN -> edge.to == nodeId
                Direction.BOTH -> edge.from == nodeId || edge.to == nodeId
            }
        }

    enum class Direction { IN, OUT, BOTH }

    private fun GraphNode.matches(needle: String): Boolean =
        name.lowercase().contains(needle) ||
            id.lowercase().contains(needle) ||
            anchor?.lowercase()?.contains(needle) == true ||
            summary?.lowercase()?.contains(needle) == true ||
            tags.any { it.lowercase().contains(needle) }

    private fun load(): CodeGraph {
        val path = file()
        if (!Files.exists(path)) return CodeGraph.EMPTY
        return runCatching {
            val root = JDOMUtil.load(path)
            val nodes = root.getChild("nodes")?.getChildren("node")?.map { element ->
                GraphNode(
                    id = element.getAttributeValue("id").orEmpty(),
                    kind = runCatching { NodeKind.valueOf(element.getAttributeValue("kind") ?: "SYMBOL") }.getOrDefault(NodeKind.SYMBOL),
                    name = element.getAttributeValue("name").orEmpty(),
                    anchor = element.getAttributeValue("anchor")?.takeIf { it.isNotBlank() },
                    summary = element.getAttributeValue("summary")?.takeIf { it.isNotBlank() },
                    tags = (element.getAttributeValue("tags") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                )
            }.orEmpty()
            val edges = root.getChild("edges")?.getChildren("edge")?.mapNotNull { element ->
                val kind = runCatching { EdgeKind.valueOf(element.getAttributeValue("kind") ?: "") }.getOrNull() ?: return@mapNotNull null
                GraphEdge(element.getAttributeValue("from").orEmpty(), element.getAttributeValue("to").orEmpty(), kind)
            }.orEmpty()
            CodeGraph(nodes, edges)
        }.getOrElse {
            thisLogger().warn("Geai: failed to load graph.xml", it)
            CodeGraph.EMPTY
        }
    }

    private fun write(graph: CodeGraph) {
        val root = Element("graph")
        val nodes = Element("nodes")
        graph.nodes.forEach { node ->
            nodes.addContent(Element("node").apply {
                setAttribute("id", node.id)
                setAttribute("kind", node.kind.name)
                setAttribute("name", node.name)
                node.anchor?.let { setAttribute("anchor", it) }
                node.summary?.let { setAttribute("summary", it.take(300)) }
                if (node.tags.isNotEmpty()) setAttribute("tags", node.tags.joinToString(","))
            })
        }
        val edges = Element("edges")
        graph.edges.forEach { edge ->
            edges.addContent(Element("edge").apply {
                setAttribute("from", edge.from)
                setAttribute("to", edge.to)
                setAttribute("kind", edge.kind.name)
            })
        }
        root.addContent(nodes)
        root.addContent(edges)
        runCatching { JDOMUtil.write(root, file()) }
            .onFailure { thisLogger().warn("Geai: failed to write graph.xml", it) }
    }
}
