package com.github.saeldrit.geai.context

data class Skill(
    val id: String,
    val description: String,
    val source: String,
    val createdAtEpochMs: Long,
    val enabled: Boolean = true,
)
