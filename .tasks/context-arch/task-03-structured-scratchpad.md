# Task 03: Structured Scratchpad

**Type:** Code Modification

## Goal

Replace the flat `MutableList<String>` scratchpad with a structured `MutableList<NoteEntry>` that supports priorities (CRITICAL/NORMAL/LOW), anchors (file:line references), and turn tracking — enabling smart retention during compression.

## What to Do

- Create `NoteEntry` data class in `com.github.saeldrit.geai.context`:
  ```kotlin
  enum class NotePriority { CRITICAL, NORMAL, LOW }
  
  data class NoteEntry(
      val text: String,
      val priority: NotePriority = NotePriority.NORMAL,
      val anchor: String? = null,      // e.g., "src/main/Foo.kt:42"
      val turn: Int = 0,               // iteration when note was created
  )
  ```
- Update `AgentSession.scratchpad` (line 32):
  - Change type from `MutableList<String>` to `MutableList<NoteEntry>`
  - Keep as `CopyOnWriteArrayList`
- Update `AgentLoop.recordNote()` (line 532):
  - Parse optional `priority` field from tool args (default: NORMAL)
  - Parse optional `anchor` field from tool args
  - Pass current iteration number as `turn`
  - Create `NoteEntry` instead of plain String
- Update `GeaiToolset.noteSpec()` (line 190):
  - Add `priority` parameter: `enum: [CRITICAL, NORMAL, LOW]`, default NORMAL
  - Add `anchor` parameter: optional string for file:line reference
  - Update description to explain priority semantics
- Update `AgentLoop.appendNotesAsTrailingUser()` (line 574):
  - Sort notes: CRITICAL first, then NORMAL, then LOW
  - Prefix each note with its priority marker: `[!]` for CRITICAL, `[-]` for LOW, no prefix for NORMAL
  - Include anchor if present: `- [!] (src/Foo.kt:42) Found NPE in validation`
- Update `MAX_NOTES_RETAINED` handling:
  - When over limit: drop LOW first, then oldest NORMAL; never drop CRITICAL
- Update `SessionDto` and `SessionCodec`:
  - `scratchpad: List<String>` → `scratchpad: List<NoteEntryDto>`
  - `NoteEntryDto(text, priority, anchor, turn)`
  - Backward compatibility: if loading old format (List<String>), wrap each as `NoteEntry(text, NORMAL)`

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/context/NoteEntry.kt` — NEW: data class + enum
- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentSession.kt` — Change scratchpad type (line 32)
- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentLoop.kt` — Update `recordNote` (line 532), `appendNotesAsTrailingUser` (line 574)
- `src/main/kotlin/com/github/saeldrit/geai/tools/GeaiToolset.kt` — Update `noteSpec()` (line 190)
- `src/main/kotlin/com/github/saeldrit/geai/session/SessionDto.kt` — Update scratchpad serialization with backward compat

## Key Points

- The `note` tool already exists and works — this task extends it, not replaces it
- CRITICAL notes are for invariants the agent discovers ("never call X without Y") — they must survive all compression
- LOW notes are for ephemeral observations ("file X has 200 lines") — disposable
- Backward compatibility in SessionDto is essential — existing sessions stored on disk must load correctly
- The system prompt already says "Use `note` aggressively" — no prompt change needed
- The stuck-loop guard injects notes into scratchpad (line 424) — these should be CRITICAL priority

## Done When

- [ ] `NoteEntry` and `NotePriority` exist and compile
- [ ] `AgentSession.scratchpad` uses `NoteEntry` type
- [ ] `note` tool accepts `priority` and `anchor` parameters
- [ ] Notes are rendered with priority markers in the trailing user message
- [ ] Over-limit retention prefers CRITICAL > NORMAL > LOW
- [ ] Old sessions with `List<String>` scratchpad load correctly (backward compat)
- [ ] Project compiles without errors
