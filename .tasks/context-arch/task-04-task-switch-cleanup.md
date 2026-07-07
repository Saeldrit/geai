# Task 04: Task Switch Scratchpad Cleanup

**Type:** Code Modification

## Goal

When the agent detects a new task (user changes topic), clean up the scratchpad: drop LOW notes, summarize NORMAL notes into a single recap, keep CRITICAL notes. This prevents stale notes from a previous task conflicting with the new one.

## What to Do

- In `AgentLoop.run()`, at the new-task detection block (around line 110-123, after Task 02 changes):
  - When `isNewTask` is true, call a new function `cleanScratchpadForNewTask(session.scratchpad, summarizer?)`
  - This function:
    1. Removes all `NoteEntry` with priority `LOW`
    2. Collects all `NORMAL` notes, summarizes them into a single recap note (priority NORMAL, anchor=null, text="[Recap from previous task] ...")
    3. Keeps all `CRITICAL` notes unchanged
    4. If summarizer is available, use LLM to compress NORMAL notes; otherwise concatenate with ";" separator and truncate to 500 chars
  - Emit `AgentEvent.Info("🧹 Cleared N stale notes, kept M critical notes for new task.")`
- Create `ScratchpadManager` object in `com.github.saeldrit.geai.context`:
  - `cleanForNewTask(scratchpad: MutableList<NoteEntry>, summarizer: ContextCompressor.Summarizer?)`
  - `retainWithinBudget(scratchpad: MutableList<NoteEntry>, maxEntries: Int)` — priority-aware eviction
  - Future-proofs scratchpad operations in one place

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/context/ScratchpadManager.kt` — NEW: scratchpad operations
- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentLoop.kt` — Call `cleanForNewTask` on task switch (line ~115)

## Key Points

- Depends on Task 02 (new-task threshold) and Task 03 (NoteEntry type)
- The summarizer may not be available at task-switch time (it's created later in `run()`); if unavailable, do a simple text join
- CRITICAL notes like "never call X without Y" genuinely transcend tasks — keeping them is correct
- The stuck-loop guard notes (line 424) should be LOW priority — they're ephemeral warnings, not findings
- The `kbSuppressed` note (line 414) should also be LOW — session-scoped hint

## Done When

- [ ] `ScratchpadManager.cleanForNewTask()` exists and correctly partitions notes by priority
- [ ] LOW notes are dropped on task switch
- [ ] NORMAL notes are summarized into a single recap entry
- [ ] CRITICAL notes survive task switch unchanged
- [ ] An info event is emitted showing cleanup stats
- [ ] Project compiles without errors
