# Task 09: request_context Tool

**Type:** Code Modification

## Goal

Add a `request_context` tool that lets the agent explicitly request a fresh context bundle mid-conversation with a specific query focus, replacing the on-demand `context_bundle` tool with a version that also refreshes the system prompt's bundle suffix.

## What to Do

- Evaluate whether this is a new tool or an enhancement to existing `context_bundle` tool (in `ContextBundleTool.kt`, line 35):
  - The existing `context_bundle` tool returns the bundle as a tool result (content in transcript)
  - `request_context` should ALSO update `bundleSuffix` so subsequent turns see the refreshed bundle in the system prompt
  - Decision: make it a META tool (handled in `executeMetaTools`) that both returns the bundle AND updates `bundleSuffix`
- Add `request_context` to `META_TOOL_NAMES` (line 671)
- Handle in `executeMetaTools` (line 678):
  - Call `ContextBundler.build(project, query, seedIds)` with the tool's args
  - Update `bundleSuffix` with the new bundle
  - Return the bundle text as the tool result so the model sees it immediately
- Register the tool spec:
  - Name: `request_context`
  - Parameters: `query` (string, required), `seed_ids` (list of strings, optional)
  - Description: "Rebuild and refresh the context bundle with a new focus. The bundle is updated in the system prompt AND returned."
- Add to `GeaiToolset.GRACE` (line 74) — only available when GRACE is enabled

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentLoop.kt` — Add to `META_TOOL_NAMES`, handle in `executeMetaTools`, update `bundleSuffix`
- `src/main/kotlin/com/github/saeldrit/geai/tools/GeaiToolset.kt` — Add tool spec, register in GRACE list

## Key Points

- This is a META tool because it mutates loop state (`bundleSuffix`)
- The existing `context_bundle` tool in GRACE returns the bundle as content but doesn't update the system prompt suffix — this tool does both
- Must capture `bundleSuffix` as a mutable reference accessible from `executeMetaTools` — currently it's a local `var` in `run()`, so pass it or use a holder
- Alternative: make `bundleSuffix` a field on a new `LoopState` class that `executeMetaTools` receives — cleaner than passing many vars
- Only available when GRACE is enabled — guarded by `settings.graceEnabled`
- Depends on Task 07 (bundle refresh infrastructure)

## Done When

- [ ] `request_context` tool is registered and callable
- [ ] Calling it rebuilds the context bundle with the specified query
- [ ] The system prompt's bundle suffix is updated for subsequent turns
- [ ] The bundle text is also returned as the tool result
- [ ] Tool is only available when GRACE is enabled
- [ ] Project compiles without errors
