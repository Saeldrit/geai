package com.github.saeldrit.geai.context

/**
 * A user preference / skill stored persistently in `.geai/skills/`. Skills capture durable
 * instructions the user wants the agent to always follow — naming conventions, tech preferences,
 * interaction style, etc. They are injected into the system prompt as `<user_preferences>`.
 *
 * The [id] is a kebab-case slug derived from the first five words of [description].
 */
data class Skill(
    val id: String,
    val description: String,
    val source: String,
    val createdAtEpochMs: Long,
)
