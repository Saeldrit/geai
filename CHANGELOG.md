<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# geai Changelog

## [Unreleased]

### Fixed — hub task ergonomics
- **Fresh session per hub task.** Hub tasks previously ran inside the CURRENT chat session —
  with a large transcript that meant megatokens of cache read on every step (slow, expensive)
  and hub work polluting the user's chat history. Each hub assignment now starts a new session
  (the previous one is saved).
- **Full result delivery.** The agent's final answer used to reach the hub only as truncated
  streamed log lines; `task_result.outputLog` now carries the complete final text (up to 12k
  chars), which the hub shows in the task's dedicated Result panel.

### Added — geai Hub orchestration loops (spoke side)
- **Planner role** (`kind=plan`): the hub delegates objective decomposition to this agent —
  it receives the fleet composition, may inspect the project read-only, and returns a strict
  JSON task graph (`name/description/role/dependsOn/verify/requiredContracts`).
- **Verifier role** (`kind=verify`): runs the relevant tests/build for a completed task
  (no fixes) and returns `{"passed": bool, "summary": …}` — the hub gates SUCCESS on it.
- **Contract rejection**: a task that received an unusable contract emits a structured
  `contract_rejection` JSON; the spoke forwards it as `contract_reject` and requeues instead
  of building on a broken interface.
- **Upstream context**: assignments now carry results of completed dependency tasks
  (artifacts + output tails) and a `reworkReason` on re-dispatch — both are injected into
  the prompt.
- Protocol mirror extended: `contract_reject`, `TaskAssign.upstream`/`UpstreamPayload`,
  `ContractPublish.taskId`.

### Added — geai Hub integration (spoke mode)
- **Working Hub connection.** The wire protocol is rewritten with stable `@SerialName` short names
  and a shared `"type"` discriminator (`HubProtocol.kt`, exact mirror of the hub's copy). The old
  code serialized sealed classes with fully-qualified class names that differed between the hub and
  the plugin, so neither side could decode the other — registration silently failed; `SpokeEvent`
  additionally carried a `type` property that collided with the discriminator.
- **`HubService` (project-level).** Owns the connection; executes `task_assign` through the regular
  `GeaiAgentService` agent loop (real tools, GRACE, configured LLM). Streams progress, live output
  lines (`task_log`) and produced artifacts back to the hub; publishes written OpenAPI files as
  contracts (`contract_publish`); returns `requeue` when busy; announces `ready` only once the loop
  actually finished. Unique stable `spokeId` per project (IDE type + project name + path hash) —
  multiple IDEs no longer overwrite each other in the hub registry.
- **Resilient transport.** `HubClient` now reassembles fragmented WebSocket frames, serializes
  sends (the JDK client allows one outstanding `sendText`), auto-reconnects with exponential
  backoff, heartbeats every 25 s, and handles disconnect-during-connect races.
- **UI/UX.** `Tools → "Geai: Connect to Hub"` is a real toggle (Connect/Disconnect) with balloon
  notifications instead of modal dialogs; hub URL and auto-connect-on-open in
  *Settings → Tools → Geai → Show advanced settings*.

### Removed
- The in-IDE MCP server (`McpToolServer`) is gone. It existed only to expose geai's tools to the
  external Claude Code CLI engine — removed in 0.0.50 — so nothing constructed it any more. The
  external-client tool surface left with the engine that used it.

### Changed
- Documentation synced to the current architecture: structure navigation and the context bundle are
  resolved **live from IntelliJ's PSI/index** (no materialized graph, no `graph_reindex`), search is
  index-backed, and there is a single native agent engine (the Claude Code CLI mode is gone). Updated
  README, `docs/GRACE_ARCHITECTURE.md`, and the plugin-manifest description.

## [0.0.74]

### Added — Phase 1/2 batch: autopsy, instant verification, memory, reports
- **`failure_autopsy`** — one-call structured post-mortem of the current pause: location, the call
  stack (top 15), and locals of the top N frames (per-frame). Use right after `break_on_exception`
  fires: frame #0 is the throw site. Replaces a debug_state + stack + N×debug_variables chain.
- **Instant syntax verification after every edit** — `edit_file`/`write_file` results now include
  a `⚠ SYNTAX ERRORS` block (PSI parse scan, fast, no analysis pass) when the change broke the
  file. A broken edit discovered in the edit result costs one line; discovered N iterations later
  it costs an investigation.
- **Cross-session recall** — on the first turn of a session, past session titles are scored
  against the task (word overlap); the top 3 matches contribute their final answers (heads) to the
  turn-stable bundle as `<past_sessions>` — "we already fixed something like this" saves whole
  re-investigations.
- **Session export** — a topbar button renders the session as a Markdown investigation report
  (`.geai/exports/<stamp>-<title>.md`: dialogue, tool calls with ✓/✗ results, token totals) and
  opens it in the editor — ready for a ticket or PR description.
- Doctrine: for a fix, the agent now offers a regression test reproducing the bug (writes it when
  asked or when a matching test file exists).

## [0.0.73]

### Added — Phase 1 of the roadmap: eyes and trust
- **`read_run_output` — the agent's eyes on program output.** A declarative ExecutionListener
  captures stdout/stderr of every Run/Debug configuration from the moment it starts (bounded ring
  buffers, last 5 processes × 200k chars, stderr lines prefixed `[err]`). The app's prints, stack
  traces, and framework logs are usually the FIRST evidence in a bug hunt — until now the agent was
  blind to them unless it had launched the process itself. In the `run` and `debug` groups;
  doctrine teaches "evidence first: read_run_output before instrumenting anything". Android logcat
  guidance via `run_command adb logcat -d -t 300`.
- **Revert last turn (one click).** A Local History label is taken right before each turn starts;
  the new ↩ topbar button restores ALL project files to that label (confirmation dialog; chat and
  session are kept; refuses while a turn is running; on failure points at the manual Local History
  path). Reversibility is what makes auto-approved edits psychologically safe.
- Tool groups may now share tools (`read_run_output` lives in both `debug` and `run`) — the
  advertised catalog deduplicates by name.

## [0.0.72]

### Added — streaming tool execution (roadmap 4.2)
- **Read-only tools start the moment their JSON finishes streaming** instead of waiting for the
  whole response. New `StreamEvent.ToolUseCompleted` is emitted by both clients (Anthropic:
  `content_block_stop`; OpenAI-compatible: on the next tool-call index, since calls stream in
  order) and the loop launches eligible tools on the shared pool immediately — on a 5-read batch,
  read #1 executes while reads #2-5 are still streaming their arguments. Meta tools (mutate loop
  state) and mutating/interactive tools (ordered approvals) keep the strict post-stream order;
  streamed results are collected unconditionally so a tool_use can never lose its tool_result.
  Kill-switch: `streamToolExecution` in geai.xml (default on).
- UI: a tool starting mid-stream no longer finalizes the live text bubble — the model's text keeps
  flowing into the same bubble while the tool group grows below it.

## [0.0.71]

### Fixed — final speed polish (closing out the context/speed workstream)
- **Transient provider failures no longer kill turns.** HTTP 500 and 529 (Anthropic
  "overloaded_error" — the most common failure under load) joined 429/502/503/504 in the retryable
  set; a 1–2s backoff now saves turns that previously hard-aborted mid-investigation.
- **Streaming no longer floods the JCEF bridge.** Fast models emit hundreds of deltas per second
  and each one crossed Kotlin→Chromium as its own executeJavaScript; deltas are now coalesced on a
  ~33ms tick (flushed immediately before any non-delta event to preserve ordering). Smoother
  streaming, less EDT time.
- **Mid-turn session checkpoints moved off the tool pool.** Serializing a large transcript to JSON
  took tens of ms ON the parallel tool-executor thread on every debounced ToolFinished save;
  checkpoints now run on a dedicated single-thread queue (strictly ordered, bursts collapse to the
  latest snapshot). Terminal saves stay synchronous for the hard guarantee.

## [0.0.70]

### Fixed — UX
- **Instant request feedback.** The chat now echoes the user's message and shows an animated
  "thinking…" indicator the moment Send is pressed — previously nothing appeared until the first
  agent event crossed the Kotlin bridge, so it was unclear the request had started at all. The
  indicator reappears between iterations (model thinking) and disappears on the first streamed
  output/tool call. The Swing fallback panel gets the equivalent: Send/Stop button states flip
  immediately and the status shows "Thinking…".
- **Android Studio: diagnosed and addressed the "different, limited UI".** That UI is the Swing
  fallback, used when `JBCefApp.isSupported()` is false. The panel now explains itself with a
  banner: if the `ide.browser.jcef.enabled` registry key is off, a one-click "Enable JCEF…" button
  turns it on and offers a restart; if the boot runtime lacks JCEF, it points at the "Choose Boot
  Java Runtime for the IDE" action. The factory logs the exact reason. The fallback also gained a
  Compact button.

## [0.0.69]

### Added — Debugger 2.0: runtime tracing without stepping
- **Tracepoints** (`set_tracepoint` + `trace_log`) — breakpoints that never stop the program: on
  every hit the recorder evaluates the given expression in the paused frame, appends
  `file:line — expr = value` to a bounded in-memory log, and auto-resumes. Tracing "where does the
  value get lost" becomes: instrument the flow, run the scenario ONCE, read the log — the first
  wrong value marks the divergence. Replaces dozens of step/evaluate LLM round-trips with one call.
  `await_pause`/`debug_step` transparently skip these transient auto-resumed pauses.
- **Conditional & temporary breakpoints** — `set_breakpoint` gained `condition` ("pause only when
  `userId == null`", catches the one interesting iteration out of thousands) and `temporary`
  (auto-removed after first hit).
- **`break_on_exception`** — toggles the debugger's exception breakpoints (e.g. Java "Any
  exception", optional filter condition): when the bug is a crash, the session pauses AT the throw
  with live state instead of the agent guessing which line to breakpoint.
- **Rich pause snapshots** — `await_pause` and `debug_step` now include the paused frame's local
  variables in their result, saving a whole `debug_variables` round-trip on every pause (the
  dominant cost of a stepping session).
- Doctrine and `/debug` mode teach technique selection: value-flow → tracepoints; crash →
  break_on_exception; rare state → conditional breakpoint; otherwise classic stepping.

### Added — UI
- **Command chips** — a one-click row above the composer with all slash commands (`/debug`,
  `/explain`, `/implement`, `/review`, …; click prefills the composer) plus a direct **Compact**
  action that folds older context into a summary — previously reachable only by typing.

## [0.0.68]

### Fixed — context architecture rework: "stable prefix, append-only, rare decisive compaction"
- **Prompt caching restored on every provider** (the main speed/cost bug). The per-iteration
  `<system_status>` block and the mid-turn automatic bundle refresh mutated the system suffix every
  step, invalidating the transcript cache prefix on Anthropic/OpenRouter (explicit `cache_control`)
  AND the implicit prefix caches of OpenAI/DeepSeek/Qwen — every iteration re-processed the entire
  transcript at full price and latency. The volatile suffix is now **turn-stable**; the bundle
  refreshes only via an explicit `request_context` call.
- **Compaction is now one summary + verbatim tail.** Instead of rewriting every old message into
  `[COMPRESSED: …]` markers (noise that still cost tokens), compaction replaces old history with ONE
  message: `[CURRENT ACTIVE TASK…]` + optional LLM recap + the deterministic `[SESSION MEMORY…]`
  ledger — then keeps the recent tail verbatim. Triggers at ~70% of the usable window (was ~85% of a
  budget that let transcripts balloon past 150k tokens, where "lost in the middle" attention decay
  made models re-read files that were already in context).
- **Re-reading after compaction is allowed again.** The hard `RE-READ BLOCKED` refusal fought the
  model's only recovery path for verbatim content (`edit_file` needs exact bytes) and its
  deterministic refusal text tripped the stuck-loop guard into aborting turns. A narrow re-read
  costs ~1k tokens — it is recovery, not failure.
- **Guard zoo dismantled.** Removed: read-tracker hard block, read-only-iteration counter (aborted
  legitimate long investigations at 14 steps), post-compression CRITICAL nudge notes, loop-guard
  scratchpad notes, kb_lookup suppression, adaptive mode-directive drop. One guard remains: the
  step-fingerprint ring — nudge via a plain one-off message, abort only after 3 repeats (was 2).
- **"продолжай" no longer wipes working memory.** Task-switch cleanup fires only for substantial
  new messages (≥40 chars) and keeps NORMAL notes verbatim (last 15) instead of squashing them into
  a lossy 500-char recap; only LOW notes are dropped.
- **Active task is no longer re-injected as the newest message every iteration** — that
  over-anchoring made models re-orient (and re-read) from scratch each step. The task lives in the
  compaction summary instead.
- **Window math is provider-safe.** The compaction reserve now covers the request's REAL
  `max_tokens` (was: reserve 16k while sending max_tokens 65k → "prompt too long" near the top).
  Defaults: `maxTokens` 65536→8192, `maxContextTokens` 200k→128k (safe across
  Claude/GPT/DeepSeek/Qwen/GLM). A provider "prompt too long" error now triggers an automatic
  force-compaction + one retry, so an over-estimated window degrades gracefully on any provider.
- **Doctrine cut ~2.5×** and de-contradicted: no turn quotas, no mandatory note-taking cadence, no
  triple-repeated anti-re-read shouting; `read_file` whole-file cap raised 150→400 lines (the old
  cap forced a chain of range-read round-trips on any normal file).
- **Sub-agents are usable:** 8 iterations / 40k tokens (was 4 / 15k — starved delegates returned
  junk the orchestrator then re-did itself) and a lean scout doctrine instead of the full main-loop
  prompt.

### Added — UI & observability
- **Agent memory viewer** — a topbar button opens a read-only overlay with the agent's working
  memory: the active task and every scratchpad note (CRITICAL/NORMAL/LOW markers); `path:line`
  references in notes are clickable and open in the editor. Makes the agent's "what do I already
  know" state inspectable instead of invisible.
- **Context bar shows the compaction threshold** — a tick marks where auto-compaction folds
  history (≈70% of the usable window), colors switch relative to that threshold, and the bar
  refreshes live after every tool step (was: only at turn end, so it sat stale through long turns).
- **Prompt-cache hit rate** in the context bar (`cache N%`, from the provider's cache-read
  counters) — visible proof the restored prefix caching actually works, on any provider that
  reports it (Anthropic, OpenRouter, OpenAI, DeepSeek).

### Fixed — misc
- Replayed sessions no longer render the loop's synthetic user messages (compaction summaries,
  guard nudges, auto-continue prompts) as if the human typed them.
- The per-turn `contextChars` metric stringified every image attachment's base64 (megabytes per
  iteration) and double-counted text blocks — now a cheap typed sum.

## [0.0.67]

### Added
- **Aggressive marker-based context compression** — Veai-inspired redesign of the compression
  system. Tool results are now compressed to lightweight markers `[COMPRESSED: tool_name(status, args)]`
  instead of LLM-generated summaries, preventing the classic re-read loop: agent reads file → details
  lost in summary → agent re-reads → context bloats again. Read-tracking (via `readFileTracker`) and
  post-compression critical nudges keep the agent working from notes rather than re-reading files.
  New system prompt section "Working with compressed context" teaches the model to recognise markers
  and use its notes. Expected: ~60–80% context reduction, significantly fewer re-read loops.

## [0.0.58]

### Changed
- **Context management overhaul** — all major context components upgraded to production-grade quality:
  - **SemanticSummary**: 11→28 regex preference patterns (architecture, async, naming, logging, testing, error handling); quality validation now checks `findingsWithLocation`; quality score 0-100 tracking; `lastQualityReport` metrics field.
  - **Dynamic Bundle**: budget 4000→8000 chars; semantic refresh trigger (3+ new anchors in scratchpad); `msgGrowth` bug fixed (was always 0 in info message); bundle quality metrics (atoms included, dropped, fill rate) in context_status.
  - **Sub-agent Context**: NORMAL notes passed (not just CRITICAL), cap 3000→12000; context bundle forwarded to sub-agents via `executeMetaTools`; structured output hint in prompt.
  - **context_status**: trend tracking (↑ growing / → stable / ↓ shrinking); compression metrics (method, ratio, input→output chars); summary quality score; bundle quality (fill rate); actionable recommendations with thresholds (90% URGENT, 75% warning, 50% info).
  - **Skills**: MAX_SKILLS=20 limit; Cyrillic→Latin transliteration for slug IDs; categorization (STYLE, LANGUAGE, TESTING, ARCHITECTURE, OTHER); active conflict detection with domain tracking (7 conflict pairs: indentation, language, style, verbosity, comments, async, error handling); `SaveResult` data class with conflict info; warning messages in SkillTool and AgentLoop.

## [0.0.57]

### Fixed
- **CI build broken** — `modelSupportsVision()` was called in `AgentLoop` but the companion function
  in `LlmProvider` was an uncommitted local change, never pushed. Committed the missing definition.
- **Stuck-loop guard bypass** — `stepSignature` hashed `read_file` input including `start_line`/`end_line`,
  so reading the same file with different ranges always produced a new fingerprint and the guard never
  fired. Now normalizes volatile params before hashing; also excludes `note` results (whose "Noted N
  total" text changes every call) and increased the ring from 5 → 10 to catch longer alternation cycles.
- **Vision retry loop** — the `isVisionError` catch retried indefinitely. Now limited to one retry;
  a second vision error propagates normally.

## [0.0.50]

### Removed
- The opt-in **Claude Code CLI engine** is gone. It bypassed the entire GRACE/PSI stack — no semantic
  navigation, no autonomous debugger, no live context bundle — a second-class, divergent code path that
  was the source of several engine-drift bugs and an external `claude`-binary dependency. GEAI is now a
  single, coherent native agent; cost-conscious users are served by the product's actual thesis (GRACE +
  cheap models + tiered routing). Removes the `useClaudeCodeEngine` / `claudeCliPath` settings and the
  session's `claudeSessionId`.

## [0.0.49]

### Fixed
- The agent no longer hangs on a wedged stream. If a provider stopped sending mid-response, the turn used
  to block until the 10-minute request timeout — and Stop couldn't interrupt it (the cancel check only ran
  between lines). The SSE body is now read on a worker thread with a responsive poll: Stop takes effect in
  ~150ms, and a stream that goes silent for 180s (or 5 min before the first byte, allowing cold/heavy
  providers to start) aborts cleanly with a clear message instead of hanging.
- A transient connect error (429/502/503/504) on the streaming endpoint now retries (honoring
  `Retry-After`) instead of hard-failing the turn — matching the non-streaming path.

## [0.0.48]

### Changed
- The GRACE context bundle and all structural navigation now run on IntelliJ's **live PSI/index** instead
  of a materialized code graph. The bundle seeds and builds its neighbourhood (class methods, super types,
  file siblings, governing specs) from PSI on demand — always fresh, no full-graph build, and it works on a
  cold project with no wait. Natural-language seed-finding is now tokenized (it was a whole-string substring
  that almost never matched a class name, so the bundle was usually empty on real tasks).

### Removed
- The parallel materialized code graph is gone — `GeaiGraphStore`, `GraphIndexer`, `GraphRefresher`, and
  `graph_reindex` — it duplicated what IntelliJ's PSI already holds live. This also removes the per-edit
  reindex (every `edit_file`/`write_file`/`run_command` used to trigger a debounced full-PSI walk) and the
  first-use cold-start build. `SpecStore.list()` is now parse-cached. Net: less work per turn, nothing stale.

## [0.0.47]

### Changed
- `graph_query` now finds code symbols (classes by name) live from IntelliJ's class index and spec
  headers from the store, instead of the materialized graph — fresh, and works with no graph build. With
  0.0.46 (`graph_neighbors`), structural navigation is now PSI-native; the parallel code graph is on its
  way out. (Methods → `find_symbol`; spec items → `spec_lookup`.)

## [0.0.46]

### Changed
- `graph_neighbors` now resolves code structure from IntelliJ's **live PSI** (a class's declared methods,
  its super types) and governance from the spec overlay, instead of a materialized graph snapshot. So it
  is always fresh and works immediately on a cold project — no waiting for a graph build. (Who-implements
  lookups stay in `find_implementations`.) First step of moving structural navigation onto IntelliJ's
  native model rather than maintaining a parallel graph.

## [0.0.45]

### Performance
- A project-wide regex `search_text` now runs through IntelliJ's native Find-in-Path engine
  (trigram-narrowed candidate files + the platform regex matcher) instead of reading every file. Together
  with 0.0.44's substring path, `search_text` is fully index-backed for both modes — the comment/pattern
  scans that dominate a big-project "clean up" turn are no longer O(project) per call. Falls back to the
  bounded scan if the project isn't indexed yet.

## [0.0.44]

### Performance
- `search_text` no longer reads every file in the project on each call. For a plain substring it now asks
  IntelliJ's word/trigram index which files could contain the query (milliseconds, regardless of project
  size) and scans only those — the substring necessarily contains the indexed word, so results are
  identical. Regex, word-less queries, and not-yet-indexed projects fall back to the previous bounded
  scan. On a large project this turns each substring search from seconds into milliseconds — the start of
  moving navigation onto IntelliJ's native index instead of hand-rolled scans.

## [0.0.43]

### Fixed
- GRACE drift detection now fingerprints the FULL resolved contract/symbol, not the truncated display
  slice. A change past the cap (a large OpenAPI path object, or a symbol over ~2000 chars) could drift
  while `spec_validate` still reported OK — silently defeating the drift guarantee. (`file:` anchors were
  already correct since 0.0.34.)
- Resolving an OpenAPI node whose `summary` is `null` or non-string no longer throws (both are legal) —
  it previously aborted the whole resolve with an UnsupportedOperationException.
- `spec_validate` and the context bundle now degrade a single unresolvable anchor to BROKEN/[unresolved]
  instead of aborting the entire batch on any non-`AnchorException`.
- The Claude Code CLI engine threads the `tool_use` id through tool events, so parallel same-named calls
  no longer collide in the chat UI (a step stuck on the ⏳ spinner) — matching the native loop (0.0.39).

## [0.0.42]

### Fixed
- The stuck-loop guard no longer aborts legitimate autonomous debugging. Debugger poll/step tools
  (`await_pause`, `debug_state`, `debug_step`) return the same result while runtime state advances, so the
  doctrine-ordered wait/step loop looked like A/B/A/B thrashing to the v0.0.38 guard and was killed
  mid-investigation. Those tools are now flagged `idempotentPoll` and all-poll steps are exempt; genuine
  read/search thrashing still aborts. (Caught by an adversarial self-review of the v0.0.34–v0.0.41 batch.)
- A failed atomic session save no longer leaks a `*.json.tmp` file (deleted on write/move failure).
- The "Unknown tool" recovery hint in the system prompt now lists the `specs` group too.

## [0.0.41]

### Fixed
- Provider API errors now show the actual reason instead of up to 2000 characters of raw JSON. A
  model-not-found, a bad key, or a rate-limit now reads as its `error.message`; non-JSON bodies (e.g. an
  HTML 502 page) are whitespace-collapsed and bounded.

## [0.0.40]

### Added
- The GRACE governance tools are now reachable: `load_tools specs` exposes `spec_list` / `spec_lookup`
  (read the Category-A rules that govern code), `spec_validate` (drift-check specs against live code), and
  `spec_record` (author a rule as an anchor). They were implemented and tested but orphaned from the
  registry; grouping them on-demand completes the governance moat at zero per-turn cost.

## [0.0.39]

### Fixed
- The chat UI now shows WHY a tool failed. A failed step rendered only a bare red ✗ with no reason, even
  though the failure text already reached the webview — failed steps now show a one-line red reason.
- Tool steps are keyed by tool_use id instead of tool name, so two same-name calls in one turn (routine
  for parallel reads) no longer leave a step stuck on the ⏳ spinner — which read as a hang even though
  the agent was fine.

## [0.0.38]

### Fixed
- Context compaction now folds the old transcript into its recap from the FULL tool output, not the
  800-char eager-truncation head — so a finding deep in a large result survives the fold instead of being
  lost before the summarizer sees it. The recap is a long run's working memory; this was gutting it.
- The stuck-loop guard fingerprints each step over its FULL result and remembers the last few steps, so
  it catches thrashing the old single-slot / 400-char-head guard missed: large results that differ only
  deep in the body, and A/B/A/B cycles (two calls alternating with no progress).

## [0.0.37]

### Fixed
- The agent no longer instructs itself to call tools that don't exist. With GRACE on (the default), the
  system prompt and several tool descriptions referenced `context_bundle` / `graph_neighbors` /
  `graph_reindex`, which the loop rejected as "Unknown tool" — a guaranteed dead-end for the cheap models
  GEAI targets. Those three tools are now registered (they were implemented and tested but orphaned), so
  graph navigation (query → neighbors) and on-demand re-bundling actually work.
- Resolved a first-move contradiction: Operating Principle #1 said "use find_files/search_text" while the
  GRACE doctrine said "do NOT" — now conditioned on whether a `<context_bundle>` is present.
- `graph_query` self-heals on an empty graph (kicks the background build) instead of dead-ending.
- `ask_user` runs on the serial lane, so two clarifying questions in one turn can no longer stack two
  modal dialogs from parallel worker threads.

### Added
- System-prompt sections for recovering from a tool error (notably `edit_file` "old_string not found" —
  the top cheap-model loop trigger) and an explicit "you are done when…" stop condition.

### CI
- Pushes to main no longer cancel an in-flight run (`cancel-in-progress` applies to PRs only) — a rapid
  second version bump had cancelled the prior release job, so that version never published.

## [0.0.36]

### Performance
- The GRACE graph now carries id and endpoint indexes, built once per snapshot. `graph_neighbors` was
  O(edges × nodes) — it scanned every node to label each returned edge — and the context bundle rebuilt
  the full adjacency map on every turn. Both are now O(degree) lookups, cutting latency on graph-heavy
  navigation and on every turn's bundle assembly. Behaviour is unchanged (parity-tested against the old
  full scan).

## [0.0.35]

### Fixed
- `run_command` now refreshes the IDE virtual file system and the GRACE graph after a command runs, so
  files written by external tools (codegen, npm/gradle, git checkout/pull) are immediately visible to
  read_file/edit_file instead of stale.
- `edit_file` is confined to the project tree (mirrors write_file), so an absolute path can no longer
  reach a file outside the project under auto-approve.
- A read-only spec lookup with a malformed id now returns "not found" instead of surfacing an
  "Invalid spec id" error — the strict validation added in 0.0.31 gates writes only.

## [0.0.34]

### Fixed
- Streaming turns now report real token usage. Anthropic input/cache tokens were always 0 (they arrive
  in `message_start`, not `message_delta`), and OpenAI-compatible usage was dropped because the final
  usage-only chunk has an empty `choices` array and was skipped before the usage was read. Both now
  capture full usage — restoring the cost guard and the sub-agent token budget.
- Session saves are serialized and written atomically (temp file + move). Tool results finish on
  parallel worker threads, so two saves could write the same file at once and corrupt it (then silently
  reset the session on next load). The save-debounce is now an atomic compare-and-set, and the
  scratchpad is copy-on-write like the transcript.
- `file:` GRACE anchors are fingerprinted without the line-number prefix, so editing lines above a
  range no longer reports a false drift.

## [0.0.33]

### Fixed
- Background session saves (and UI reads) no longer race the running turn. The transcript is now a
  copy-on-write list, so iterating it — to save or to render — while the loop appends can no longer
  throw `ConcurrentModificationException` (which was swallowed, silently dropping the save).
- The "already working" guard is an atomic compare-and-set, closing a check-then-set window where two
  near-simultaneous submits could both start a turn.

## [0.0.32]

### Changed
- Tool dispatch is now safety-aware. Read-only tools still run in parallel, but mutating tools
  (edit / write / run) run sequentially — one approval at a time, a deterministic write order, and no
  same-file clobber or shared-resource race when several land in the same turn.

### Internal
- The agent loop gained a test seam (an injectable client) with a scripted `FakeLlmClient` and a
  `BasePlatformTestCase` harness that exercises the real loop (turn termination, the stuck-loop guard,
  and the read-only/mutating dispatch split).

## [0.0.31]

### Security
- `spec_record` could write outside the spec directory — the model-supplied spec id flowed into a file
  path unchecked (path traversal). The id is now validated and the resolved path is asserted to stay
  under `spec/`.

### Fixed
- `kb_lookup` empty-result suppression matched results to calls by position, but tool results are
  ordered [meta]+[regular] while calls are interleaved — so it mis-counted whenever a note/load_tools/
  delegate shared the turn. It now matches by tool_use id.
- The "Dropped tiered-routing hint" notice no longer repeats every iteration; it fires once, and only
  when there is actually a mode directive to drop.
- The session scratchpad (the agent's durable working notes) is now persisted, so a restart resumes
  with its findings instead of losing them.

### Changed
- OpenAI-compatible streaming now requests `stream_options.include_usage`, so streamed turns report
  token counts (previously they could read as zero, blinding the cost guard).

## [0.0.30]

### Fixed
- Anthropic tool calls with arguments are no longer corrupted. Streaming seeded the tool-input buffer
  with the empty `{}` from `content_block_start` and then appended the real arguments, producing
  `{}{…}` — which crashed (strict Gson) or silently dropped the arguments (so e.g. `list_files` ignored
  its path and the model looped). The buffer now starts empty and accumulates only the streamed deltas.
  This had broken Claude tool use since 0.0.28.
- JSON parsing no longer throws `MalformedJsonException` on newer IDEs. The plugin compiles against
  Gson 2.10.1 but runs against the IDE's bundled Gson (2.11+ in 2026.1), which is strict by default;
  parsing is now explicitly lenient, robust across IDE Gson versions.

### Changed
- The stuck-loop guard nudges the model once ("you already have this result — move on") before aborting,
  so a single repeated call no longer kills the turn; it stops only on a second identical repeat.
- Doctrine: emphasizes batching independent tool calls into one turn (N reads/searches cost one
  round-trip) and forbids repeating an identical call — orient once, then read specific code and edit.

### Performance
- Advertised tool specs are cached and rebuilt only when the tool surface changes; context compaction is
  skipped while the transcript stays well under budget.

## [0.0.29]

### Fixed
- Claude API streaming no longer crashes with `NoClassDefFoundError` — the Anthropic streaming client
  used `kotlinx.atomicfu`, which is not on the plugin's runtime classpath. Replaced with plain locals
  (the SSE loop is single-threaded), keeping the HTTP layer dependency-free.
- Edit/cleanup tasks now edit files directly instead of fanning out read-only review sub-agents that
  cannot change anything — the doctrine separates editing from auditing.

### Changed
- Delegated sub-agents run in parallel (bounded pool) instead of sequentially, so multi-file audits
  finish in about the slowest sub-agent's time rather than the sum of all of them.
- The tool-window UI is fully in English. The settings screen hides dev-only knobs (GRACE telemetry,
  vector ranker, model prices) behind sound defaults.

### Added
- Type `/` in the composer to discover commands (`/debug`, `/run`, `/explain`, `/implement`,
  `/refactor`, `/test`, `/review`, `/security`) with one-line descriptions and keyboard navigation.
- Crisp SVG toolbar icons with reliable hover tooltips (native `title=` tooltips are unreliable in JCEF).

## [0.0.28]

### Added
- Streaming responses: the assistant's reply appears in real time as it is generated — text streams into
  a single bubble and is re-rendered as markdown when complete (no more one-bubble-per-token); thinking
  streams into a collapsible block.
- `debug_trace`: walk the debugger N steps in ONE call, evaluating a watched expression at each stop.
  Returns the full trace (file:line + the value + locals per step) — instead of calling debug_step +
  debug_evaluate repeatedly.
- `debug_step` gained a `repeat` parameter to take several steps in one call.

### Changed
- `await_pause` no longer blocks for its whole timeout when the debug session has ended — it returns as
  soon as there is no live session left to wait for (a short grace still covers start_debug's launch).
- Adaptive context savers: drop the tiered-routing hint when the model isn't using `escalate_author`,
  and stop suggesting `kb_lookup` once the knowledge store is consistently empty.
- Performance: tool specs are cached, session saves are debounced, and eager compaction is skipped on
  tiny transcripts.

### Fixed
- Anthropic streaming lost tool names (every streamed tool call became "tool"); the name is now carried
  from the stream. Removed unused session bookkeeping.

## [0.0.27]

### Added
- `debug_dump_object`: dump an object and its fields several levels deep (default 3, max 5) in ONE
  call. Instead of probing `food.foodItems().get(0).rawText()` field-by-field over a dozen round-trips,
  the model gets the whole tree at once — large collections are capped per level.
- `debug_evaluate` now takes an array of `expressions` and evaluates them all in one call (the frame
  and evaluator are resolved once), so the model stops calling it one expression at a time.

### Changed
- Faster, more reliable value resolution at a breakpoint: lazy/proxy values (jOOQ, Hibernate, deferred
  fields) are pulled through the debugger's full-value evaluator instead of waiting out the
  "Collecting data…" placeholder, with a `java.lang.String.valueOf(...)` backstop for stubborn proxies.
  `debug_variables` shares the same resolution path.

## [0.0.26]

### Fixed
- Debugger evaluation now returns real values for lazy/proxy objects (jOOQ records, Hibernate
  collections, deferred fields) instead of a "Collecting data…" placeholder or a blank. `debug_evaluate`
  and `debug_variables` now wait for the resolved presentation (pulling the full value via the debugger's
  full-value evaluator), and when a value still won't materialise they force it with
  `java.lang.String.valueOf(...)` — which works in both Java and Kotlin frames and is null-safe. This is
  what was making the agent "see" empty/missing data at a breakpoint.

## [0.0.25]

### Changed
- Much faster start. The GRACE graph is no longer reindexed inline on the first turn — a full-project
  PSI reindex could stall the turn for minutes before the model was even called. It now runs in the
  background and the turn proceeds immediately (the bundle is ready from the next turn on).

### Added
- Slash commands select a working MODE. A leading `/debug`, `/run`, `/explain`, `/implement`,
  `/refactor`, `/test`, `/review`, or `/security` pre-loads the tools that mode needs (no extra
  `load_tools` round-trip) and steers the agent with a focused directive. `/debug` goes straight to
  locating the path, setting breakpoints, and driving the debugger — instead of orienting first.
- The debug tools are advertised automatically on a follow-up turn while a debug session is live, so
  "continue" turns don't spend a round-trip re-loading them.
- The welcome-screen presets now insert their `/command`, so the buttons teach the commands.

## [0.0.24]

### Added
- Autonomous debugger stepping: the agent drives the debugger itself via the new `debug_step` tool
  (`over` / `into` / `out` / `resume`). It issues the step and waits for the next pause, returning the
  new `file:line`, so it walks the suspect path breakpoint-by-breakpoint instead of asking you to step.
- Clickable source references in the chat: `file:line` mentions open the file in the editor (with a
  project-wide filename fallback), so you can jump straight to a cited class or a breakpoint location.

### Changed
- The agent loop now runs continuously until the task is done: the per-turn token ceiling was removed
  and the iteration cap is now a high anti-runaway backstop. Long turns are handled by persistent
  context compaction (the old transcript is folded into a recap once and reused) instead of stopping
  the turn and asking you to type "continue".
- Debugging is autonomous: `await_pause` now waits up to 10 minutes, and the doctrine instructs the
  agent to wait for a user-triggered request itself, step through the code, and remove the breakpoints
  it set when the investigation is done.
- The stuck-loop guard now compares both the call and its result, so legitimately repeated calls
  (stepping the debugger, polling for a pause) are no longer mistaken for a stuck loop.

## [0.0.12]

### Added
- Tool calls in the chat are now grouped into a single collapsible block per agent turn.
  Each call shows the tool name, argument preview, and a status icon (⏳ → ✓ / ✗).
  The block closes automatically when the agent finishes.
- Markdown rendering in assistant messages: headings `##`/`###`, lists, tables, bold, inline code.

## [0.0.11]

### Added
- `ask_user` tool: the agent can pause and ask a clarifying question (yes/no or free text)
  for genuinely ambiguous or destructive situations. Not used for routine tool approvals.
- Approval dialog redesigned: three options — Allow once / Allow for session / Deny — plus
  a checkbox to persist "allow always" to settings. "Allow for session" adds the tool to an
  in-memory allow-list for the IDE session.

### Changed
- `autoApproveEditTools` default changed from `false` to `true`. Mutating tools run without
  dialogs out of the box. Disable in settings to restore per-call confirmation.

## [0.0.10]

### Fixed
- `graph_reindex` returned 0 nodes: `com.intellij.modules.java` was not declared as an optional
  dependency in `plugin.xml`, so IntelliJ did not load Java PSI classes into the plugin
  classloader. Fixed with an optional `<depends>` + `geai-java.xml`.
- `psi:` anchors failed with "JVM language support not available": same root cause. Now degrades
  cleanly in non-JVM IDEs.
- `kb_lookup` with `axis` filter returned no results: `query()` read from disk independently of
  `put()`, causing a race on Windows. Fixed with an in-memory cache invalidated atomically on
  write. Also fixed axis parsing (`uppercase()` before `valueOf()`).

## [0.0.9]

### Fixed
- `graph_reindex` returned 0 nodes when IDE indexing was not complete: added `DumbService.isDumb()`
  guard and replaced `isInSourceContent()` with `isInSource() || isInContent()`.
  PSI failures are now logged instead of silently swallowed.

## [0.0.8]

### Added
- **GRACE** (Graph-RAG Anchored Code Engineering) — full implementation across five phases:
  - Phase 1: `AnchorResolver` SPI + `file:` / `psi:` / `openapi:` resolvers + `resolve_ref` tool.
  - Phase 2: `SpecStore` (`spec/*.spec.xml`) + `spec_list` / `spec_lookup` / `spec_record` /
    `spec_validate` tools. Drift detection via SHA-256 baseline on Category-B anchors.
  - Phase 3: `CodeGraph` + `GraphIndexer` (PSI + governance edges) + `graph_query` /
    `graph_neighbors` / `graph_reindex` tools.
  - Phase 4: `ContextBundler` (seed → expand → rank → resolve → pack) + `Ranker` SPI
    (deterministic default, vector shim) + `context_bundle` tool.
  - Phase 5: tiered model routing (`navigatorModel` + `escalate_author` tool). One provider/key,
    two model names. Navigator drives the loop; author writes code on demand.
- Settings UI: GRACE tiered routing toggle, navigator model field, vector ranker toggle.
- Anthropic prompt caching (`cache_control: ephemeral` on system block and last tool).
- HTTP retries with exponential backoff on 429 / 502 / 503 / 504.
- MCP server: per-session bearer token (SecureRandom, 24 bytes).
- `write_file` and `run_command` confined to the project root.
- `kotlin.code.style = official` in `gradle.properties`.
- `since-build = 252` in plugin manifest.

### Fixed
- OpenAI tool-call id collision on fallback id generation.
- MCP `serverInfo` version now reflects the actual plugin version.
- `ContextCompressor` budget now derived from the configured model context window.

## [0.0.7]

### Fixed
- Dark, theme-matched scrollbars in the JCEF UI (no more white scrollbar); page-level scroll
  disabled so only the transcript scrolls. Tool/info lines now wrap instead of overflowing
  horizontally, so context is never clipped.

## [0.0.6]

### Added
- Project knowledge index ("axes"): a persistent, tagged, versioned `.geai/knowledge.xml` with
  optimistic CAS updates, and tools `kb_lookup` / `kb_record` / `kb_forget`. Axes: NAV (symbol ->
  file:line), STYLE (conventions), TECH (stack/invariants), LESSON (what not to do). The agent
  consults the index before searching/reading, so navigation costs little context or tokens.

## [0.0.5]

### Added
- Debugger can now inspect runtime state at a breakpoint: `debug_variables` reads the current
  frame's locals and `debug_evaluate` evaluates an expression in the frame (XDebugger value API).
  This closes the gap where geai could pause but not see values — added via the self-extension path.

## [0.0.4]

### Added
- Reasoning capture is now wired end to end: the Claude Code engine's `thinking` blocks are
  emitted as a dedicated event and rendered in the collapsible block (previously dropped).
- The composer input can be resized vertically by dragging its corner grip (up to 60% viewport).

### Changed
- System prompt: stricter, terser output register (no filler/flattery/hedging, answer only what
  was asked, prefer short bullets).

## [0.0.3]

### Added
- The model's reasoning/thinking is shown as a collapsible block (hidden by default) in both the
  JCEF and Swing UIs, on the Claude Code engine.

### Changed
- Tighter, more professional system-prompt register (terse, direct, no filler).

## [0.0.2]

### Changed
- Settings: the Model field is now an editable dropdown of suggested models per provider (still
  free-typed for custom endpoints).
- Settings: enabling the Claude Code engine now disables the provider/model/key/token fields that
  do not apply, and the engine toggle moved to the top — so it is clear what to configure.
- Original geai iconography (plugin icon, tool-window icon, in-UI mark) replacing the placeholder.

## [Unreleased]

### Added
- Autonomous debugging agent in a chat tool window.
- Two engines, switchable in settings:
  - **Built-in agent** — geai's own loop calling the provider HTTP API with an API key.
  - **Claude Code engine** — delegates the loop to the local `claude` CLI using your Claude
    subscription login; geai's tools are exposed to it via an in-IDE MCP server (HTTP), with
    `--resume` session continuity. No API key or per-token cost.
- Provider-agnostic LLM client: Anthropic Claude (Messages API) and OpenAI-compatible endpoints
  (DeepSeek, Qwen/DashScope, OpenRouter, local models). API keys stored via PasswordSafe.
- Tool-using agent loop with IDE-backed tools:
  - Navigation/reading: `project_overview`, `find_files`, `list_files`, `read_file`, `search_text`.
  - Editing: `write_file`, `edit_file` (undoable, exact-match).
  - Debugging: `set_breakpoint`, `remove_breakpoint`, `list_breakpoints`, `debug_state`,
    `start_debug`, `await_pause` (generic XDebugger API).
  - System & self-modification: `run_command`, `self_info`, `self_patch`.
- Deterministic transcript compaction and a compact project-context snapshot for the system prompt.
- Resumable sessions persisted to `<project>/.geai/sessions/`.
- Per-tool approval gate for mutating actions, with auto-approval opt-ins in settings.
- Settings page under **Settings | Tools | Geai**.
- JCEF (embedded-browser) chat UI: welcome screen with skill-card presets and NEW/BETA badges,
  message bubbles with code rendering, toolbar (new / history / settings), engine-and-token footer,
  and theme-adaptive colors. Falls back to the Swing chat panel when JCEF is unavailable.

### Changed
- Replaced the IntelliJ Platform Plugin Template sample code with the geai implementation.
- Pinned the Gradle build daemon to JDK 21 via `gradle/gradle-daemon-jvm.properties`.
