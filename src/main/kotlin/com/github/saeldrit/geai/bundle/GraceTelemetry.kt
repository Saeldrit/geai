package com.github.saeldrit.geai.bundle

import com.github.saeldrit.geai.settings.GeaiSettings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Dev-only observability for context assembly: appends one JSON line per bundle to
 * `<project>/.geai/telemetry/bundles.jsonl` — which atoms were pulled, their sizes, which were
 * dropped under budget. Lets the author see "where it wastes / what shape fits". Gated by
 * [GeaiSettings.graceTelemetry] (off by default → prod stays lean); the file is gitignored.
 */
@Service(Service.Level.PROJECT)
class GraceTelemetry(private val project: Project) {

    companion object {
        fun getInstance(project: Project): GraceTelemetry = project.service()
    }

    fun enabled(): Boolean = GeaiSettings.getInstance().state.graceTelemetry

    fun recordBundle(query: String, atoms: List<Pair<Atom, Boolean>>, budgetChars: Int, usedChars: Int, epochMs: Long) {
        if (!enabled()) return
        runCatching {
            val record = JsonObject().apply {
                addProperty("ts", epochMs)
                addProperty("query", query.take(200))
                addProperty("budgetChars", budgetChars)
                addProperty("usedChars", usedChars)
                addProperty("included", atoms.count { it.second })
                addProperty("dropped", atoms.count { !it.second })
                add("atoms", JsonArray().apply {
                    atoms.forEach { (atom, included) ->
                        add(JsonObject().apply {
                            addProperty("id", atom.id)
                            addProperty("tag", atom.tag)
                            addProperty("chars", atom.chars)
                            addProperty("protected", atom.protected)
                            addProperty("included", included)
                        })
                    }
                })
            }
            Files.writeString(
                file(),
                record.toString() + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }.onFailure { thisLogger().warn("Geai telemetry write failed", it) }
    }

    private fun file(): Path {
        val base = project.basePath
        val dir = if (base != null) Paths.get(base, ".geai", "telemetry") else Paths.get(PathManager.getSystemPath(), "geai", project.locationHash, "telemetry")
        Files.createDirectories(dir)
        return dir.resolve("bundles.jsonl")
    }
}
