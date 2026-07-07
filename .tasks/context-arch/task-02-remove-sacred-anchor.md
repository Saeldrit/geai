# Task 02: Remove messages[0] Sacred Anchor

**Type:** Code Modification

## Goal

Remove the special treatment of `messages[0]` (the first user message) as a protected anchor. The first message should be summarized like any other old message. `activeTask` — already injected as the last message — becomes the sole task anchor.

## What to Do

- In `ContextCompressor.summarizeOldContext()` (line 107):
  - Currently keeps `messages[0]` verbatim and summarizes `messages.subList(1, tailStart)`
  - Change to summarize `messages.subList(0, tailStart)` — include messages[0] in the summarizable middle
  - The summary message replaces the entire old segment (no special index-0 preservation)
  - Result: `[summaryMessage] + messages.subList(tailStart, n)`
- In `ContextCompressor.compactRange()` (line 176):
  - Currently `if (index == 0) continue` — remove this skip
  - All messages in the compactable range are eligible for truncation
- In `AgentLoop.run()` (lines 110-120, new-task detection):
  - Lower threshold from `session.messages.size > 30` to `session.messages.size > 10`
  - Remove the `session.messages.removeAt(0)` call — no longer needed since messages[0] is not sacred
  - The new-task detection should just update `session.activeTask = userText` (which already happens at line 123)
- Verify that `activeTask` injection at line 243 (`[Current task — do NOT revisit any earlier request:]`) is sufficient as the sole anchor

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/context/ContextCompressor.kt` — lines 107-142 (`summarizeOldContext`), lines 176-189 (`compactRange`)
- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentLoop.kt` — lines 110-123 (new-task detection and anchor logic)

## Key Points

- The `activeTask` is always injected as the LAST user message (AgentLoop line 243) — this already serves as the task anchor
- After this change, compression can summarize the entire early transcript including the original user question
- The new-task threshold of 10 messages means the agent detects task switches much earlier (within ~5 tool call rounds)
- The `removeAt(0)` was a workaround for messages[0] being sacred — removing sacredness makes this unnecessary
- Must ensure the summary message is still role=USER so transcript role alternation stays valid

## Done When

- [ ] `summarizeOldContext` includes messages[0] in the summarizable segment
- [ ] `compactRange` no longer skips index 0
- [ ] New-task threshold changed from 30 to 10
- [ ] `removeAt(0)` call removed from new-task detection
- [ ] Project compiles without errors
- [ ] `activeTask` remains injected as the last message (no regression)
