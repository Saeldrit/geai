# Task 05: Skill Extraction During Compression

**Type:** Code Modification

## Goal

During transcript compression, detect user preferences and behavioral patterns ("no comments", "answer in Russian", "use tabs", "prefer functional style") and persist them as skill files in `.geai/skills/` so they survive across sessions and are injected into the system prompt.

## What to Do

- Create `Skill` data class and `SkillStore` in `com.github.saeldrit.geai.context`:
  ```kotlin
  data class Skill(
      val id: String,           // kebab-case identifier, e.g., "answer-in-russian"
      val description: String,  // human-readable: "User prefers responses in Russian"
      val source: String,       // "extracted" or "explicit" (from skill tool)
      val createdAtEpochMs: Long = System.currentTimeMillis(),
  )
  ```
- `SkillStore` object:
  - `load(project: Project): List<Skill>` — reads all `.geai/skills/*.json` files
  - `save(project: Project, skill: Skill)` — writes a single skill file
  - `delete(project: Project, id: String)` — removes a skill file
  - `renderForPrompt(skills: List<Skill>): String` — formats skills as a `<user_preferences>` block
- Create a skill extraction prompt in `SemanticCompressor` (from Task 01):
  - During compression, add a secondary instruction: "Also extract any user preferences or behavioral patterns you see"
  - Parse extracted preferences from the structured JSON response
  - Only persist NEW preferences not already in the skill store (dedup by semantic similarity — simple substring match is fine)
- Update `SystemPrompt.build()` (line 10):
  - After the project snapshot, inject `SkillStore.renderForPrompt(SkillStore.load(project))`
  - Only if skills exist; no empty block

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/context/Skill.kt` — NEW: data class
- `src/main/kotlin/com/github/saeldrit/geai/context/SkillStore.kt` — NEW: persistence + rendering
- `src/main/kotlin/com/github/saeldrit/geai/context/SemanticCompressor.kt` — Add preference extraction to compression prompt
- `src/main/kotlin/com/github/saeldrit/geai/agent/SystemPrompt.kt` — Inject skills into prompt (line 10)

## Key Points

- Skills are persisted as JSON files in the PROJECT's `.geai/skills/` directory (not global)
- This means skills are per-project, which is correct — "use tabs" may be project-specific
- Skill extraction is opportunistic — happens during compression, not every turn
- Deduplication should be simple: if a skill with the same `id` exists, skip it
- The skill ID should be generated from the description (slugify first 5 words)
- Skill extraction adds ~200 tokens to the compression prompt — acceptable overhead
- Depends on Task 01 (SemanticCompressor exists)

## Done When

- [ ] `Skill` data class and `SkillStore` exist and compile
- [ ] `SkillStore` can read/write/delete skill files from `.geai/skills/`
- [ ] Compression prompt includes preference extraction instruction
- [ ] Extracted skills are persisted (with dedup) during compression
- [ ] `SystemPrompt.build()` injects skills when they exist
- [ ] Project compiles without errors
