<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# geai Changelog

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
