package com.github.saeldrit.geai.session

import com.github.saeldrit.geai.agent.AgentSession
import com.github.saeldrit.geai.llm.http.JsonSupport
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.streams.toList

/**
 * Persists sessions as JSON under `<project>/.geai/sessions/` so an investigation survives
 * disconnects and IDE restarts. Falls back to the IDE system directory when there is no project
 * base path. All access is best-effort: persistence failures are logged, never fatal.
 */
@Service(Service.Level.PROJECT)
class GeaiSessionStore(private val project: Project) {

    companion object {
        fun getInstance(project: Project): GeaiSessionStore = project.service()
    }

    private fun sessionsDir(): Path {
        val base = project.basePath
        val dir = if (base != null) {
            Paths.get(base, ".geai", "sessions")
        } else {
            Paths.get(PathManager.getSystemPath(), "geai", project.locationHash, "sessions")
        }
        Files.createDirectories(dir)
        return dir
    }

    private val saveLock = Any()

    fun save(session: AgentSession) {
        // Saves can fire concurrently from the parallel tool pool (ToolFinished), so serialize them and
        // write atomically (temp file + move) — a reader never sees a half-written file and two writers
        // can't interleave into corruption.
        synchronized(saveLock) {
            runCatching {
                val json = JsonSupport.gson.toJson(SessionCodec.toDto(session))
                val dir = sessionsDir()
                val tmp = Files.createTempFile(dir, session.id, ".json.tmp")
                Files.writeString(tmp, json, StandardCharsets.UTF_8)
                Files.move(tmp, dir.resolve("${session.id}.json"), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { thisLogger().warn("Geai: failed to save session ${session.id}", it) }
        }
    }

    fun load(id: String): AgentSession? = runCatching {
        val file = sessionsDir().resolve("$id.json")
        if (!Files.exists(file)) return null
        val dto = JsonSupport.gson.fromJson(Files.readString(file), SessionDto::class.java)
        SessionCodec.fromDto(dto)
    }.getOrNull()

    fun loadMostRecent(): AgentSession? {
        val newest = runCatching {
            Files.list(sessionsDir()).use { stream ->
                stream.filter { it.toString().endsWith(".json") }
                    .max(compareBy { Files.getLastModifiedTime(it).toMillis() })
                    .orElse(null)
            }
        }.getOrNull() ?: return null
        return runCatching {
            SessionCodec.fromDto(JsonSupport.gson.fromJson(Files.readString(newest), SessionDto::class.java))
        }.getOrNull()
    }

    fun listMeta(): List<SessionMeta> = runCatching {
        Files.list(sessionsDir()).use { stream ->
            stream.filter { it.toString().endsWith(".json") }.toList()
        }.mapNotNull { path ->
            runCatching {
                val dto = JsonSupport.gson.fromJson(Files.readString(path), SessionDto::class.java)
                SessionMeta(dto.id, dto.title, Files.getLastModifiedTime(path).toMillis(), dto.messages.size)
            }.getOrNull()
        }.sortedByDescending { it.updatedAtEpochMs }
    }.getOrDefault(emptyList())

    fun delete(id: String) {
        runCatching { Files.deleteIfExists(sessionsDir().resolve("$id.json")) }
    }
}
