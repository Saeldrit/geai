package com.github.saeldrit.geai.context

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class SaveResult(
    val skill: Skill?,
    val conflicts: List<Skill> = emptyList(),
    val conflictDomain: String? = null,
) {
    val saved: Boolean get() = skill != null
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

class SkillStore private constructor(private val project: Project) {

    companion object {
        private val instances = java.util.concurrent.ConcurrentHashMap<String, SkillStore>()
        private const val MAX_SKILLS = 20

        fun getInstance(project: Project): SkillStore =
            instances.getOrPut(project.locationHash) { SkillStore(project) }

        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val SKILL_TYPE = object : TypeToken<Skill>() {}.type
    }

    private val lock = Any()

    private fun skillsDir(): Path {
        val base = project.basePath
        val dir = if (base != null) {
            Paths.get(base, ".geai", "skills")
        } else {
            Paths.get(PathManager.getSystemPath(), "geai", project.locationHash, "skills")
        }
        Files.createDirectories(dir)
        return dir
    }

    fun loadAll(): List<Skill> = synchronized(lock) {
        val dir = skillsDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.newDirectoryStream(dir, "*.json").use { ds ->
                ds.mapNotNull { path ->
                    runCatching {
                        GSON.fromJson(Files.readString(path), SKILL_TYPE) as Skill
                    }.onFailure {
                        thisLogger().warn("SkillStore: failed to parse ${path.fileName}", it)
                    }.getOrNull()
                }.sortedBy { it.createdAtEpochMs }
            }
        }.getOrElse {
            thisLogger().warn("SkillStore: failed to list skills directory", it)
            emptyList()
        }
    }

    fun save(description: String, source: String = "user"): SaveResult = synchronized(lock) {
        val trimmed = description.trim()
        if (trimmed.isBlank()) {
            thisLogger().warn("SkillStore: rejected save with empty description")
            return SaveResult(null)
        }
        val id = slugify(trimmed)
        val existing = loadAll()
        if (existing.size >= MAX_SKILLS && existing.none { it.id == id }) {
            thisLogger().warn("SkillStore: max skills ($MAX_SKILLS) reached, rejecting new skill '$id'")
            return SaveResult(null)
        }
        val (conflicts, domain) = detectConflicts(trimmed, existing)
        if (conflicts.isNotEmpty()) {
            thisLogger().info("SkillStore: new skill '$id' conflicts with: ${conflicts.joinToString { it.id }} (domain: $domain)")
        }
        val duplicate = existing.find { it.id == id }
        if (duplicate != null) {
            thisLogger().debug("SkillStore: skill '$id' already exists, updating description")
            val updated = duplicate.copy(description = trimmed, source = source)
            val path = skillsDir().resolve("$id.json")
            runCatching {
                Files.writeString(path, GSON.toJson(updated, SKILL_TYPE))
            }.onFailure {
                thisLogger().warn("SkillStore: failed to update $id.json", it)
            }
            return SaveResult(updated, conflicts, domain)
        }
        val skill = Skill(
            id = id,
            description = trimmed,
            source = source,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val path = skillsDir().resolve("$id.json")
        runCatching {
            Files.writeString(path, GSON.toJson(skill, SKILL_TYPE))
        }.onFailure {
            thisLogger().warn("SkillStore: failed to write $id.json", it)
        }
        SaveResult(skill, conflicts, domain)
    }

    fun toggle(id: String): Skill? = synchronized(lock) {
        val existing = loadAll().find { it.id == id } ?: return null
        val updated = existing.copy(enabled = !existing.enabled)
        val path = skillsDir().resolve("$id.json")
        runCatching {
            Files.writeString(path, GSON.toJson(updated, SKILL_TYPE))
        }.onFailure {
            thisLogger().warn("SkillStore: failed to toggle $id.json", it)
        }
        updated
    }

    fun update(id: String, newDescription: String): Skill? = synchronized(lock) {
        val trimmed = newDescription.trim()
        if (trimmed.isBlank()) return null
        val existing = loadAll().find { it.id == id } ?: return null
        val updated = existing.copy(description = trimmed)
        val path = skillsDir().resolve("$id.json")
        runCatching {
            Files.writeString(path, GSON.toJson(updated, SKILL_TYPE))
        }.onFailure {
            thisLogger().warn("SkillStore: failed to update $id.json", it)
        }
        updated
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val path = skillsDir().resolve("$id.json")
        runCatching { Files.deleteIfExists(path) }.getOrElse {
            thisLogger().warn("SkillStore: failed to delete $id.json", it)
            false
        }
    }

    private val CONFLICT_PAIRS = listOf(
        Triple(Regex("tab", RegexOption.IGNORE_CASE), Regex("space", RegexOption.IGNORE_CASE), "indentation"),
        Triple(Regex("russian|русск", RegexOption.IGNORE_CASE), Regex("english|англ", RegexOption.IGNORE_CASE), "language"),
        Triple(Regex("functional|функционал", RegexOption.IGNORE_CASE), Regex("imperative|императив", RegexOption.IGNORE_CASE), "style"),
        Triple(Regex("verbose|подробно", RegexOption.IGNORE_CASE), Regex("brief|кратко|tersely", RegexOption.IGNORE_CASE), "verbosity"),
        Triple(Regex("comment|комментар", RegexOption.IGNORE_CASE), Regex("no.?(?:comment|комментар)", RegexOption.IGNORE_CASE), "comments"),
        Triple(Regex("coroutine|корутин", RegexOption.IGNORE_CASE), Regex("rxjava|reactive", RegexOption.IGNORE_CASE), "async"),
        Triple(Regex("exception|исключен", RegexOption.IGNORE_CASE), Regex("result.?(?:type|<)", RegexOption.IGNORE_CASE), "error handling"),
    )

    private fun detectConflicts(newDescription: String, existing: List<Skill>): Pair<List<Skill>, String?> {
        val newLower = newDescription.lowercase()
        val conflicting = mutableListOf<Skill>()
        var detectedDomain: String? = null
        for (skill in existing) {
            val existingLower = skill.description.lowercase()
            for ((a, b, domain) in CONFLICT_PAIRS) {
                if ((a.containsMatchIn(newLower) && b.containsMatchIn(existingLower)) ||
                    (b.containsMatchIn(newLower) && a.containsMatchIn(existingLower))) {
                    conflicting.add(skill)
                    if (detectedDomain == null) detectedDomain = domain
                }
            }
        }
        return conflicting to detectedDomain
    }

    fun renderForPrompt(): String {
        val skills = loadAll().filter { it.enabled }
        if (skills.isEmpty()) return ""
        val categorized = skills.groupBy { categorize(it.description) }
        return buildString {
            appendLine("<user_preferences>")
            for ((category, categorySkills) in categorized.toSortedMap()) {
                if (category != SkillCategory.OTHER) {
                    appendLine("  [${category.name.lowercase()}]")
                }
                categorySkills.forEach { s ->
                    appendLine("  - ${s.description}")
                }
            }
            append("</user_preferences>")
        }
    }

    private enum class SkillCategory {
        STYLE, LANGUAGE, TESTING, ARCHITECTURE, OTHER
    }

    private fun categorize(description: String): SkillCategory {
        val lower = description.lowercase()
        return when {
            lower.contains(Regex("(style|format|indent|tab|space|bracket|comment|brief|verbose|detailed)")) -> SkillCategory.STYLE
            lower.contains(Regex("(kotlin|java|python|english|russian|language|respond|write)")) -> SkillCategory.LANGUAGE
            lower.contains(Regex("(test|tdd|spec|coverage|mock|assert)")) -> SkillCategory.TESTING
            lower.contains(Regex("(architecture|pattern|mvvm|repository|clean|di|inject|sealed|data class|coroutine|reactive)")) -> SkillCategory.ARCHITECTURE
            else -> SkillCategory.OTHER
        }
    }

    private fun slugify(description: String): String {
        val words = description.trim().split(Regex("\\s+")).take(5)
        val raw = words.joinToString("-") { it.lowercase() }
        val transliterated = StringBuilder()
        for (c in raw) {
            when {
                c.code in 0x00..0x7F -> transliterated.append(c)
                c.code in 0x0400..0x04FF -> transliterated.append(CYRILLIC_MAP[c] ?: "-")
                else -> transliterated.append('-')
            }
        }
        return transliterated.toString()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .ifBlank { "skill-${System.currentTimeMillis() % 10000}" }
    }

    private val CYRILLIC_MAP: Map<Char, String> = buildMap {
        put('а', "a"); put('б', "b"); put('в', "v"); put('г', "g"); put('д', "d"); put('е', "e"); put('ё', "e")
        put('ж', "zh"); put('з', "z"); put('и', "i"); put('й', "y"); put('к', "k"); put('л', "l"); put('м', "m")
        put('н', "n"); put('о', "o"); put('п', "p"); put('р', "r"); put('с', "s"); put('т', "t"); put('у', "u")
        put('ф', "f"); put('х', "kh"); put('ц', "ts"); put('ч', "ch"); put('ш', "sh"); put('щ', "shch")
        put('ъ', ""); put('ы', "y"); put('ь', ""); put('э', "e"); put('ю', "yu"); put('я', "ya")
    }
}
