# Task 08: Sub-Agent Context Passing

**Type:** Code Modification

## Goal

Currently sub-agents spawned via `delegate` get zero parent context — only the task description. Pass the current context bundle and CRITICAL scratchpad notes to sub-agents so they start with the parent's knowledge.

## What to Do

- In `AgentLoop.runDelegate()` (line 592):
  - Currently constructs `prompt = if (hint == null) task else "$task\n\nLeads/anchors to start from: $hint"`
  - Enhance the prompt to include:
    1. The current `bundleSuffix` (the `<context_bundle>` block) — passed as a prefix to the sub-session's system prompt or as part of the task text
    2. CRITICAL notes from the parent's scratchpad — formatted as `<parent_context>\n- [finding1]\n- [finding2]\n</parent_context>`
  - The sub-agent's system prompt should include these so it doesn't re-discover what the parent already knows
- Update `runDelegate` signature to accept the current `bundleSuffix: String` and `scratchpad: List<NoteEntry>`
- Update all call sites (in `executeMetaTools`, around line 724) to pass these
- For the sub-agent's `AgentLoop` constructor:
  - The sub-agent already uses `LoopProfile.sub()` — no change needed
  - The sub-agent's system prompt will include the bundle via the task text (simplest approach)

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentLoop.kt` — `runDelegate` (line 592), `executeMetaTools` (line 678), `runDelegateTimed` (line 761)

## Key Points

- Depends on Task 03 (NoteEntry type) and Task 07 (current bundle available)
- Keep the sub-agent's context lean — only pass CRITICAL notes, not the full scratchpad
- The bundle text goes INTO the task prompt, not as a separate system prompt (sub-agents use a simplified system prompt via `SystemPrompt.doctrine()`)
- The sub-agent has `SUB_MAX_TURN_TOKENS = 25000` — don't blow its budget with a huge context injection
- Cap injected parent context at 2000 chars to stay within budget
- The `hint` parameter in delegate already serves a similar purpose — this generalizes it

## Done When

- [ ] `runDelegate` accepts and injects current bundle text into sub-agent prompt
- [ ] CRITICAL scratchpad notes from parent are included in sub-agent prompt
- [ ] Injected context is capped at 2000 chars
- [ ] Sub-agents receive parent knowledge without exceeding their token budget
- [ ] Project compiles without errors
