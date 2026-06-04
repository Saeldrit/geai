<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# geai Changelog

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
