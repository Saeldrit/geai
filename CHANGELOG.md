<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# geai Changelog

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
