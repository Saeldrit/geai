# Task 10: context_status Tool

**Type:** Code Modification

## Goal

Add a `context_status` tool that shows the agent its current context state: transcript token usage, scratchpad note counts by priority, compression history, and active task — enabling self-aware context management.

## What to Do

- Create `ContextStatusTool` — a META tool handled in `executeMetaTools`:
  - Name: `context_status`
  - No parameters (reads current state)
  - Returns a formatted report:
    ```
    Transcript: ~12,400 tokens (25% of 48,000 budget)
    Messages: 18 (6 protected from compression)
    Scratchpad: 7 notes (2 CRITICAL, 4 NORMAL, 1 LOW)
    Active task: "Fix NPE in UserService"
    Bundle: present (built at iteration 3, 12 nodes)
    Compressions: 2 (last at iteration 8)
    Skills: 3 loaded
    ```
- Add `context_status` to `META_TOOL_NAMES` (line 671)
- Handle in `executeMetaTools` — needs access to:
  - `session.messages` (for token estimate via `ContextCompressor.estimatedTokens`)
  - `session.scratchpad` (for note counts by priority)
  - `session.activeTask`
  - `settings.transcriptWindow()` (for budget percentage)
  - `bundleSuffix` (whether a bundle is present)
  - Compression count (add a counter to the loop)
- Add a `compressionCount` counter to the main loop (increment when compression fires)
- Register the tool spec in `GeaiToolset` — always available (CORE), not on-demand

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentLoop.kt` — Add to `META_TOOL_NAMES`, handle in `executeMetaTools`, add compression counter
- `src/main/kotlin/com/github/saeldrit/geai/tools/GeaiToolset.kt` — Register tool spec in CORE

## Key Points

- This is a META tool because it reads loop-internal state not accessible to regular tools
- The tool is purely informational — no side effects
- Helps the agent decide when to request a context refresh or when to be more aggressive with notes
- Token estimate uses the existing `ContextCompressor.estimatedTokens()` method
- Depends on Task 03 (NoteEntry for priority breakdown)
- Consider introducing a `LoopState` holder class to bundle all the mutable loop state (`bundleSuffix`, `compressionCount`, `iteration`, etc.) — cleaner than passing 10 parameters to `executeMetaTools`

## Done When

- [ ] `context_status` tool is registered and callable
- [ ] Returns accurate transcript token usage and budget percentage
- [ ] Shows scratchpad note counts by priority
- [ ] Shows active task, bundle presence, compression count
- [ ] Project compiles without errors
