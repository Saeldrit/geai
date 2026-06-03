package com.github.saeldrit.geai.knowledge

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Project knowledge index persisted as tagged, versioned XML at `<project>/.geai/knowledge.xml`.
 * Reads stay cheap (no file scanning); updates use optimistic concurrency (compare-and-swap on
 * [KnowledgeEntry.version]) so parallel agents do not silently clobber each other.
 */
@Service(Service.Level.PROJECT)
class GeaiKnowledgeStore(private val project: Project) {

    companion object {
        fun getInstance(project: Project): GeaiKnowledgeStore = project.service()
    }

    private val lock = Any()

    /** In-memory cache — invalidated on every write so query() always sees the latest state. */
    @Volatile
    private var cache: List<KnowledgeEntry>? = null

    private fun file(): Path {
        val base = project.basePath
        val dir = if (base != null) Paths.get(base, ".geai") else Paths.get(PathManager.getSystemPath(), "geai", project.locationHash)
        Files.createDirectories(dir)
        return dir.resolve("knowledge.xml")
    }

    /** Loads from cache if warm, otherwise reads from disk. Must be called inside [lock]. */
    private fun load(): MutableList<KnowledgeEntry> {
        cache?.let { return it.toMutableList() }
        return loadFromDisk().also { cache = it.toList() }
    }

    private fun loadFromDisk(): MutableList<KnowledgeEntry> {
        val path = file()
        if (!Files.exists(path)) return mutableListOf()
        return runCatching {
            JDOMUtil.load(path).getChildren("entry").map { element ->
                val axisRaw = element.getAttributeValue("axis")
                val axis = runCatching { Axis.valueOf(axisRaw?.uppercase() ?: "") }.getOrElse {
                    thisLogger().warn("Geai: unknown axis '$axisRaw', defaulting to TECH")
                    Axis.TECH
                }
                KnowledgeEntry(
                    id = element.getAttributeValue("id").orEmpty(),
                    axis = axis,
                    tags = (element.getAttributeValue("tags") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    title = element.getAttributeValue("title").orEmpty(),
                    location = element.getAttributeValue("location")?.takeIf { it.isNotBlank() },
                    version = element.getAttributeValue("version")?.toIntOrNull() ?: 1,
                    body = element.getChildText("body").orEmpty(),
                )
            }.toMutableList()
        }.getOrElse {
            thisLogger().warn("Geai: failed to load knowledge.xml", it)
            mutableListOf()
        }
    }

    private fun save(entries: List<KnowledgeEntry>) {
        val root = Element("knowledge")
        entries.forEach { entry ->
            val element = Element("entry").apply {
                setAttribute("id", entry.id)
                setAttribute("axis", entry.axis.name)
                setAttribute("version", entry.version.toString())
                setAttribute("tags", entry.tags.joinToString(","))
                setAttribute("title", entry.title)
                entry.location?.let { setAttribute("location", it) }
                addContent(Element("body").setText(entry.body))
            }
            root.addContent(element)
        }
        runCatching {
            JDOMUtil.write(root, file())
            cache = entries.toList()
        }.onFailure {
            cache = null
            thisLogger().warn("Geai: failed to write knowledge.xml", it)
        }
    }

    fun query(axis: Axis?, tags: List<String>, text: String?): List<KnowledgeEntry> = synchronized(lock) {
        val needle = text?.lowercase()?.takeIf { it.isNotBlank() }
        load().filter { entry ->
            (axis == null || entry.axis == axis) &&
                (tags.isEmpty() || tags.any { tag -> entry.tags.any { it.equals(tag, ignoreCase = true) } }) &&
                (needle == null || entry.matches(needle))
        }
    }

    /** CAS upsert. With a non-null [expectedVersion] that mismatches the stored one, returns [PutResult.Conflict]. */
    fun put(
        id: String,
        axis: Axis,
        tags: List<String>,
        title: String,
        location: String?,
        body: String,
        expectedVersion: Int?,
    ): PutResult = synchronized(lock) {
        val entries = load()
        val index = entries.indexOfFirst { it.id == id }
        val existing = entries.getOrNull(index)
        if (existing != null && expectedVersion != null && existing.version != expectedVersion) {
            return PutResult.Conflict(existing.version)
        }
        val version = (existing?.version ?: 0) + 1
        val entry = KnowledgeEntry(id, axis, tags, title, location, version, body)
        if (index >= 0) entries[index] = entry else entries.add(entry)
        save(entries)
        PutResult.Ok(version)
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val entries = load()
        val removed = entries.removeAll { it.id == id }
        if (removed) save(entries) else Unit
        removed
    }

    /** Force-invalidate the in-memory cache (e.g. after external edits to knowledge.xml). */
    fun invalidateCache() = synchronized(lock) { cache = null }

    private fun KnowledgeEntry.matches(needle: String): Boolean =
        title.lowercase().contains(needle) ||
            body.lowercase().contains(needle) ||
            tags.any { it.lowercase().contains(needle) } ||
            location?.lowercase()?.contains(needle) == true

    sealed interface PutResult {
        data class Ok(val version: Int) : PutResult
        data class Conflict(val currentVersion: Int) : PutResult
    }
}
