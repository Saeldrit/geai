<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# geai Changelog

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
