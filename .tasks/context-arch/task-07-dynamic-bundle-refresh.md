# Task 07: Dynamic Bundle Refresh

**Type:** Code Modification

## Goal

The context bundle (GRACE `<context_bundle>`) is currently built once at the start of `AgentLoop.run()` and never refreshed, even as the agent discovers new relevant code areas. Implement periodic re-bundling that uses recent scratchpad notes as additional seed context.

## What to Do

- In `AgentLoop.run()`, at the start of each loop iteration (around line 204):
  - Track when the bundle was last built: `var bundleBuiltAtIteration = 0`
  - Every N iterations (N=5, configurable), OR when the agent has added 3+ notes since last bundle:
    1. Extract file paths and symbol names from recent CRITICAL+NORMAL scratchpad notes (parse `anchor` fields)
    2. Combine with the original user query to build new seeds
    3. Call `ContextBundler.build()` with the enriched seeds
    4. Replace `bundleSuffix` with the new bundle text
    5. Emit `AgentEvent.Info("🔄 Refreshed context bundle with recent findings.")`
- Modify the `bundleSuffix` construction (currently lines 139-158):
  - Extract bundle building into a reusable function: `buildBundleSuffix(project, query, notes, settings, command): String`
  - Call it for initial build AND for refresh
- Add a `BUNDLE_REFRESH_INTERVAL = 5` constant in `AgentLoop.companion`

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentLoop.kt` — Bundle refresh logic in the loop (around line 204), extract bundle building helper
- `src/main/kotlin/com/github/saeldrit/geai/bundle/ContextBundler.kt` — May need to accept additional seed IDs from notes

## Key Points

- Depends on Task 03 (NoteEntry with anchors)
- Bundle refresh should be NON-BLOCKING to the loop — if it fails, keep the old bundle
- The refresh uses notes' `anchor` fields as additional `seedIds` for `ContextBundler.build()`
- Refreshing too often wastes tokens; 5 iterations is ~10 messages, enough for the agent to explore new areas
- The bundle char budget should stay the same (4000 chars) — don't bloat the system prompt
- Must handle the case where GRACE is disabled (`settings.graceEnabled = false`) — skip refresh entirely
- The `bundleSuffix` variable is already mutable (`var`) and updated for adaptive routing kill-switch (line 388) — same pattern

## Done When

- [ ] Bundle refreshes every 5 iterations when the agent has new findings
- [ ] Recent note anchors are used as additional seeds for `ContextBundler.build()`
- [ ] Bundle building is extracted into a reusable function
- [ ] Refresh is skipped when GRACE is disabled
- [ ] Refresh failure doesn't break the loop (keeps old bundle)
- [ ] Project compiles without errors
