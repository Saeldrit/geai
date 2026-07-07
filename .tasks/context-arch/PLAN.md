# Semantic Context Architecture Redesign — Task Execution Plan

## Your Mission

Redesign the context management architecture of the geai IntelliJ plugin agent. Replace mechanical truncation-based compression with semantic, structured context management: structured JSON compression, priority-aware scratchpad, skill extraction, dynamic bundle refresh, and sub-agent context passing.

**Plan File:** `.tasks/context-arch/PLAN.md`
**Tasks Directory:** `.tasks/context-arch/`

---

## Execution Steps

### 1. Read This Plan
Review this file for the next incomplete task, key decisions, and information from previous agents.

### 2. Understand Your Task
Read your task file: `.tasks/context-arch/task-XX-[name].md`
- **Goal** — What you are trying to achieve
- **Key Points** — Important considerations
- **Done When** — Objective acceptance criteria

### 3. Execute the Task
- Make necessary code changes
- Ensure code compiles without errors
- Verify all Done When criteria are met

### 4. Update This Plan
- Mark the task as completed in `## Task Plan`
- Add a 1-2 sentence outcome summary in `## Shared Context`
- Document only critical decisions that affect future tasks

### 5. Await Approval (MANDATORY)
Wait for user confirmation before proceeding to the next task.

### 6. Review Task List (MANDATORY)
Analyze remaining tasks based on what you learned:
- Did you encounter unexpected complexity?
- Should any tasks be split, merged, removed, or reordered?
- Are there missing tasks?

### 7. Present Review Findings (MANDATORY)
Always present your findings — even if no changes are needed — and await user approval before proceeding.

### 8. Update Task Files (if approved)
- Modify/create task files as needed
- Update `## Task Plan` in PLAN.md accordingly

---

## Task Plan

### Phase 1: Semantic Context Compression
- [x] [task-01-structured-compression.md]: Structured Compression Summary
- [x] [task-02-remove-sacred-anchor.md]: Remove messages[0] Sacred Anchor

### Phase 2: Structured Scratchpad
- [x] [task-03-structured-scratchpad.md]: Structured Scratchpad
- [x] [task-04-task-switch-cleanup.md]: Task Switch Scratchpad Cleanup

### Phase 3: Skill Extraction
- [x] [task-05-skill-extraction.md]: Skill Extraction During Compression
- [x] [task-06-skill-tool.md]: Skill Tool

### Phase 4: Dynamic Bundle Refresh
- [x] [task-07-dynamic-bundle-refresh.md]: Dynamic Bundle Refresh
- [x] [task-08-subagent-context.md]: Sub-Agent Context Passing
- [x] [task-09-request-context-tool.md]: request_context Tool

### Phase 5: Observability & Persistence
- [ ] [task-10-context-status-tool.md]: context_status Tool — Show context state, token usage, scratchpad stats (depends on 03)
- [ ] [task-11-session-dto-migration.md]: SessionDto Migration — Serialize NoteEntry with backward compat (depends on 03)

### Phase 6: Verification
- [x] [task-12-integration-verification.md]: Integration Verification

---

## Shared Context

### Overview
The geai plugin is an autonomous AI coding agent running inside IntelliJ IDEA. Its core loop (`AgentLoop.kt`, 907 lines) sends the conversation transcript to an LLM each iteration, compresses when over budget, and executes tool calls. The current compression is mechanical (truncation + flat text summary); the scratchpad is `MutableList<String>` with no priorities; context bundles are built once and never refreshed.

### Architecture (Current State)

**Core loop flow** (AgentLoop.run, line 84):
1. User message → `session.messages.add()`
2. New-task detection (threshold: 30 messages) → `session.activeTask = userText`
3. Build `bundleSuffix` via `ContextBundler.build()` (ONCE, never refreshed)
4. Loop:
   a. `ContextCompressor.compress()` → eager truncation + LLM summarization fallback
   b. `appendNotesAsTrailingUser()` → inject scratchpad as last user message
   c. Inject `activeTask` as VERY LAST user message
   d. LLM call → get tool uses
   e. Execute tools (meta: note/load_tools/delegate → regular: parallel/sequential)
   f. Stuck-loop detection

**Context Compressor** (ContextCompressor.kt, 228 lines):
- `compress()` → under budget? return. Over? Try summarization, fallback to truncation
- `eagerlyTruncateOldToolResults()` → shrink old tool results to `EAGER_TOOL_HEAD=2000` chars (protects last 8)
- `summarizeOldContext()` → keeps messages[0] sacred, summarizes middle, keeps last `KEEP_RECENT=6`
- `truncateToBudget()` → deterministic fallback: truncate tool→assistant→all to `TRUNCATED_HEAD=400` chars

**Session** (AgentSession.kt, 42 lines):
- `messages: CopyOnWriteArrayList<ChatMessage>` — full transcript
- `scratchpad: CopyOnWriteArrayList<String>` — flat notes
- `activeTask: String` — current task text

**Serialization** (SessionDto.kt, 96 lines):
- Gson-based, flat DTOs — `scratchpad: List<String>`
- Backward compat is critical (users have sessions on disk)

**System Prompt** (SystemPrompt.kt, 283 lines):
- `build()` = BASE doctrine + GRACE doctrine + routing hint + project snapshot
- Bundle injected as `systemVolatileSuffix` (after cache breakpoint)

**Sub-agents** (AgentLoop.runDelegate, line 592):
- Get ONLY the task text + optional hint
- Use `LoopProfile.sub(maxIter=8, maxTokens=25000)`
- Read-only toolset, no parent context

### Project Structure
```
com.github.saeldrit.geai.agent/     — AgentLoop, AgentSession, SystemPrompt
com.github.saeldrit.geai.context/   — ContextCompressor, TranscriptSummary, ProjectContextGatherer
com.github.saeldrit.geai.bundle/    — ContextBundler (GRACE)
com.github.saeldrit.geai.session/   — SessionDto, SessionCodec, SessionStore
com.github.saeldrit.geai.settings/  — GeaiSettings, GeaiSettingsState
com.github.saeldrit.geai.llm/       — LlmClient, ChatMessage, ContentBlock, Role
com.github.saeldrit.geai.tools/     — GeaiToolset, AgentTool, ToolResult, ToolRegistry
  tools/grace/                      — ContextBundleTool, ResolveRefTool, etc.
  tools/knowledge/                  — KbLookupTool, KbRecordTool
  tools/fs/                         — ReadFileTool, EditFileTool, etc.
```

### Key Data Types
```kotlin
// Messages.kt
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
sealed interface ContentBlock { Text, Image, ToolUse, ToolResult }
data class ChatMessage(val role: Role, val content: List<ContentBlock>)

// AgentSession.kt
class AgentSession(val id, var title, var createdAtEpochMs) {
    val messages: MutableList<ChatMessage>  // CopyOnWriteArrayList
    val scratchpad: MutableList<String>     // CopyOnWriteArrayList — WILL CHANGE to NoteEntry
    var activeTask: String
    var totalUsage: TokenUsage
}

// ContextBundler.kt
data class Bundle(val text: String, val nodeIds: List<String>, val resolved: Int, val rules: Int, val dropped: Int)
```

### Key Constants
- `KEEP_RECENT = 6` — messages protected from summarization
- `EAGER_KEEP_RECENT_TOOLS = 8` — tool results protected from eager truncation
- `EAGER_TOOL_HEAD = 2000` — chars kept when eager-truncating old tool results
- `TRUNCATED_HEAD = 400` — chars kept in fallback truncation
- `MAX_NOTES_RETAINED = 50` — scratchpad note cap
- `maxTranscriptTokens = 48000` — context budget
- `SUB_MAX_ITERATIONS = 8`, `SUB_MAX_TURN_TOKENS = 25000` — sub-agent limits
- `MAX_DELEGATIONS = 16` — parallel sub-agents per turn

### Key Decisions
- SemanticSummary is rendered as text into ChatMessage (not a separate field) — keeps ContextCompressor.Summarizer interface unchanged
- NoteEntry uses an enum priority (CRITICAL/NORMAL/LOW) — simple and sufficient
- Skills are per-project (.geai/skills/) not global — project conventions differ
- Bundle refresh uses note anchors as additional seeds — leverages existing ContextBundler.build() API
- request_context is a META tool (mutates bundleSuffix) — distinguishes it from the existing context_bundle tool
- SessionDto backward compat uses a dual-format deserializer (detect String vs Object per element)
- Consider extracting a `LoopState` holder class if AgentLoop.run() accumulates too many mutable locals

### Caveats & Problems
- AgentLoop.run() is already 360 lines — adding state (bundleRefreshIteration, compressionCount, etc.) risks bloat. Consider `LoopState` extraction if needed.
- `executeMetaTools` already takes 8 parameters — new META tools (request_context, context_status) will need access to more state. A `LoopState` parameter object would help.
- Gson has no built-in polymorphic deserialization — the scratchpad backward compat needs a custom `JsonDeserializer<List<NoteEntryDto>>` registered on the Gson instance.
- The `bundleSuffix` is a local `var` in `run()` — META tools that need to modify it will require either passing a mutable holder or making it a field. A `LoopState` class solves this.
- Sub-agent context injection must be capped (2000 chars) to avoid blowing the sub-agent's 25K token budget.
