# Task 06: Skill Tool

**Type:** Code Modification

## Goal

Add a `skill` agent tool that lets the agent (or user via the agent) explicitly save, list, or remove user preferences/skills, complementing the automatic extraction in Task 05.

## What to Do

- Create `SkillTool` in `com.github.saeldrit.geai.tools.knowledge`:
  - Name: `skill`
  - Parameters:
    - `action`: enum `save | list | remove` (required)
    - `description`: string (required for save — the preference text)
    - `id`: string (required for remove — the skill ID to delete)
  - Actions:
    - `save`: Generate ID from description, create `Skill(source="explicit")`, persist via `SkillStore.save()`
    - `list`: Return all skills formatted as a bullet list
    - `remove`: Delete skill by ID via `SkillStore.delete()`
  - Not mutating (writes to .geai/, not project source)
- Register `SkillTool` in `GeaiToolset.CORE` (line 59) — always available, not on-demand
- Update system prompt `BASE` to mention the `skill` tool:
  - Add a brief line in the "Tools" section: "Use `skill` to save user preferences you detect (language, style, conventions) so they persist across sessions."

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/tools/knowledge/SkillTool.kt` — NEW: tool implementation
- `src/main/kotlin/com/github/saeldrit/geai/tools/GeaiToolset.kt` — Register in CORE (line 59)
- `src/main/kotlin/com/github/saeldrit/geai/agent/SystemPrompt.kt` — Brief mention in BASE (line 77)

## Key Points

- Depends on Task 05 (`SkillStore` exists)
- The tool should be simple — just a thin wrapper around `SkillStore`
- `save` should validate description is non-empty and not duplicate an existing skill
- `list` returns "No skills saved yet." when empty
- `remove` returns an error if the ID doesn't exist
- The tool is NOT mutating (doesn't change project source code) — no approval needed

## Done When

- [ ] `SkillTool` exists with save/list/remove actions
- [ ] Tool is registered in `GeaiToolset.CORE`
- [ ] System prompt mentions the tool
- [ ] Project compiles without errors
