# Task 11: SessionDto Migration

**Type:** Code Modification

## Goal

Update SessionDto and SessionCodec to serialize/deserialize the new NoteEntry scratchpad and SemanticSummary structures, with full backward compatibility for existing persisted sessions.

## What to Do

- Add `NoteEntryDto` to SessionDto.kt:
  ```kotlin
  data class NoteEntryDto(
      var text: String = "",
      var priority: String = "NORMAL",
      var anchor: String? = null,
      var turn: Int = 0,
  )
  ```
- Update `SessionDto.scratchpad`:
  - Change type to `List<NoteEntryDto>` (from `List<String>`)
- Update `SessionCodec.toDto()` (line 45):
  - Map `NoteEntry` → `NoteEntryDto`
- Update `SessionCodec.fromDto()` (line 58):
  - Map `NoteEntryDto` → `NoteEntry`
  - **Backward compatibility**: Gson will fail to deserialize old `List<String>` into `List<NoteEntryDto>`. Handle this:
    - Use a custom `TypeAdapter` or `JsonDeserializer` that checks if each scratchpad element is a `JsonPrimitive` (old format) or `JsonObject` (new format)
    - Old strings become `NoteEntry(text=string, priority=NORMAL, anchor=null, turn=0)`
- Test deserialization of an old session JSON manually to verify backward compat

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/session/SessionDto.kt` — Add `NoteEntryDto`, update `SessionDto.scratchpad`, update `SessionCodec`
- `src/main/kotlin/com/github/saeldrit/geai/session/SessionStore.kt` — May need custom Gson adapter registration (find the Gson instance)

## Key Points

- Depends on Task 03 (NoteEntry type exists)
- Backward compatibility is CRITICAL — users have existing sessions on disk
- Gson is used for serialization (see the `SessionDto` Kdoc: "Flat, Gson-friendly mirror")
- The simplest backward-compat approach: make `scratchpad` an `Any` in the raw JSON, then handle both shapes in a custom deserializer
- Alternative: keep `scratchpad: List<String>` in DTO but add a separate `scratchpadV2: List<NoteEntryDto>?` field — newer sessions use V2, old sessions have only V1
- Skills don't need DTO changes — they're persisted in `.geai/skills/` not in session JSON
- SemanticSummary doesn't need DTO changes — it's rendered as text in ChatMessage, not a separate DTO field

## Done When

- [ ] `NoteEntryDto` exists in SessionDto.kt
- [ ] `SessionCodec.toDto()` serializes NoteEntry correctly
- [ ] `SessionCodec.fromDto()` deserializes both old (List<String>) and new (List<NoteEntryDto>) formats
- [ ] An old-format session JSON can be loaded without errors
- [ ] A new-format session JSON round-trips correctly
- [ ] Project compiles without errors
