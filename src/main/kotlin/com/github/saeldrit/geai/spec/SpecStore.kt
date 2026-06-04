package com.github.saeldrit.geai.spec

import com.github.saeldrit.geai.anchor.AnchorResolvers
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
import kotlin.streams.toList

/**
 * Reads and writes GRACE specs under `<project>/spec/<id>.spec.xml`. One file per spec id. Reference
 * items (Category B) are stamped with the resolved content hash at write time so drift detection
 * has a baseline. All access is synchronized and best-effort: parse/write failures are logged.
 */
@Service(Service.Level.PROJECT)
class SpecStore(private val project: Project) {

    companion object {
        fun getInstance(project: Project): SpecStore = project.service()
        private const val SUFFIX = ".spec.xml"
        private val SAFE_ID = Regex("[A-Za-z0-9._-]+")
    }

    private val lock = Any()

    /** Parsed-spec cache, keyed by a cheap (name+mtime) dir signature. The context bundle calls
     *  list() on every turn; without this it re-parsed every spec file from disk each time. */
    @Volatile
    private var listCache: Pair<String, List<Spec>>? = null

    private fun specDir(): Path {
        val base = project.basePath
        val dir = if (base != null) Paths.get(base, "spec") else Paths.get(PathManager.getSystemPath(), "geai", project.locationHash, "spec")
        Files.createDirectories(dir)
        return dir
    }

    private fun fileFor(specId: String): Path {
        // spec_id comes straight from model output, so reject anything that could escape the spec dir
        // (path separators, traversal): allow only a flat id of safe characters, then verify the
        // resolved path still sits directly under spec/.
        require(specId.isNotBlank() && SAFE_ID.matches(specId)) {
            "Invalid spec id (allowed: letters, digits, '.', '_', '-'): '$specId'"
        }
        val dir = specDir()
        val target = dir.resolve("$specId$SUFFIX").normalize()
        require(target.parent == dir) { "spec id escapes the spec directory: '$specId'" }
        return target
    }

    fun list(): List<Spec> = synchronized(lock) {
        val signature = listSignature()
        listCache?.let { if (it.first == signature) return@synchronized it.second }
        val specs = runCatching {
            Files.list(specDir()).use { stream ->
                stream.filter { it.toString().endsWith(SUFFIX) }.toList()
            }.mapNotNull { parse(it) }.sortedBy { it.id }
        }.getOrDefault(emptyList())
        listCache = signature to specs
        specs
    }

    /** Cheap fingerprint of the spec dir (file names + mtimes) — re-parse only when a spec changed. */
    private fun listSignature(): String = runCatching {
        Files.list(specDir()).use { stream ->
            stream.filter { it.toString().endsWith(SUFFIX) }
                .map { "${it.fileName}:${Files.getLastModifiedTime(it).toMillis()}" }
                .sorted()
                .toList()
                .joinToString("|")
        }
    }.getOrDefault("")

    fun find(specId: String): Spec? = synchronized(lock) {
        // Read path: a malformed id is simply "no such spec", not an error to surface to the model.
        if (!SAFE_ID.matches(specId)) return@synchronized null
        parse(fileFor(specId))
    }

    /** Upsert one item into a spec (creating the file if needed). Reference items get a fresh hash baseline. */
    fun upsertItem(
        specId: String,
        title: String?,
        domain: String?,
        kind: SpecItemKind,
        itemId: String?,
        tags: List<String>,
        ref: String?,
        body: String,
    ): Spec = synchronized(lock) {
        val existing = parse(fileFor(specId))
        val refHash = if (kind.isReference && ref != null) {
            runCatching { AnchorResolvers.resolve(ref, project).contentHash }.getOrNull()
        } else {
            null
        }
        val item = SpecItem(kind, itemId, tags, ref, refHash, body)
        val items = existing?.items?.toMutableList() ?: mutableListOf()
        val index = items.indexOfFirst { it.kind == kind && it.id == itemId && itemId != null }
        if (index >= 0) items[index] = item else items.add(item)

        val spec = Spec(
            id = specId,
            title = title ?: existing?.title ?: specId,
            domain = domain ?: existing?.domain,
            relativePath = "spec/$specId$SUFFIX",
            items = items,
        )
        write(spec)
        spec
    }

    private fun parse(path: Path): Spec? {
        if (!Files.exists(path)) return null
        return runCatching {
            val root = JDOMUtil.load(path)
            val items = root.children.mapNotNull { element ->
                val kind = SpecItemKind.fromTag(element.name) ?: return@mapNotNull null
                SpecItem(
                    kind = kind,
                    id = element.getAttributeValue("id")?.takeIf { it.isNotBlank() },
                    tags = (element.getAttributeValue("tags") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    ref = element.getAttributeValue("ref")?.takeIf { it.isNotBlank() },
                    refHash = element.getAttributeValue("hash")?.takeIf { it.isNotBlank() },
                    body = element.text.orEmpty().trim(),
                )
            }
            Spec(
                id = root.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: path.fileName.toString().removeSuffix(SUFFIX),
                title = root.getAttributeValue("title").orEmpty().ifBlank { path.fileName.toString().removeSuffix(SUFFIX) },
                domain = root.getAttributeValue("domain")?.takeIf { it.isNotBlank() },
                relativePath = "spec/${path.fileName}",
                items = items,
            )
        }.getOrElse {
            thisLogger().warn("Geai: failed to parse spec ${path.fileName}", it)
            null
        }
    }

    private fun write(spec: Spec) {
        val root = Element("spec").apply {
            setAttribute("id", spec.id)
            setAttribute("title", spec.title)
            spec.domain?.let { setAttribute("domain", it) }
        }
        spec.items.forEach { item ->
            val element = Element(item.kind.tag)
            item.id?.let { element.setAttribute("id", it) }
            if (item.tags.isNotEmpty()) element.setAttribute("tags", item.tags.joinToString(","))
            if (item.kind.isReference) {
                item.ref?.let { element.setAttribute("ref", it) }
                item.refHash?.let { element.setAttribute("hash", it) }
            }
            if (item.body.isNotBlank()) element.text = item.body
            root.addContent(element)
        }
        runCatching { JDOMUtil.write(root, fileFor(spec.id)) }
            .onFailure { thisLogger().warn("Geai: failed to write spec ${spec.id}", it) }
        listCache = null
    }
}
