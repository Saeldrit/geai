# geai

![Build](https://github.com/Saeldrit/geai/workflows/Build/badge.svg)

<!-- Plugin description -->
**geai** is an autonomous debugging agent that lives inside IntelliJ IDEA. Open its tool window,
describe a bug in plain language — _"invalid data reaches the UI, I can't find where we lose it"_ —
and geai navigates your project, traces the data flow, sets breakpoints, drives the debugger, and
proposes (or applies) a fix that matches your codebase's style.

It connects to **Claude** (Anthropic) or any **OpenAI-compatible** endpoint (DeepSeek, Qwen/DashScope,
OpenRouter, or a local model via Ollama / LM Studio / vLLM). It runs a real tool-using agent loop with
IDE-backed tools: project navigation, full-text search, file read/edit, breakpoint control, debug-session
steering, and command execution for rebuilds and tests. Sessions are persisted, so a disconnect or IDE
restart resumes the investigation where it left off. geai can even extend itself: when it hits a missing
capability it can patch its own source, rebuild, and ask you to reload.
<!-- Plugin description end -->

## Features

- **Conversational debugging** — a chat tool window (right dock) where you describe the problem; geai
  asks clarifying questions when needed, otherwise works autonomously.
- **Provider-agnostic LLM** — Anthropic Claude (native Messages API) or any OpenAI-compatible server.
  API keys are stored in the IDE credential store (PasswordSafe), never in plain config.
- **IDE-native tools** (function-calling):
  - Navigation & reading: `project_overview`, `find_files`, `list_files`, `read_file`, `search_text`
  - Editing: `write_file`, `edit_file` (undoable write-commands, exact-match edits)
  - Debugging: `set_breakpoint`, `remove_breakpoint`, `list_breakpoints`, `debug_state`,
    `start_debug`, `await_pause`
  - System & self-modification: `run_command`, `self_info`, `self_patch`
- **Context management** — a compact project snapshot seeds the system prompt; long transcripts are
  compacted deterministically (oldest tool outputs truncated, recent turns kept) so investigations
  survive without losing the thread.
- **Resumable sessions** — persisted to `<project>/.geai/sessions/` after every tool result and turn.
- **Safety** — mutating tools (edit/run/self-patch/start-debug) prompt for approval unless you opt into
  auto-approval in settings.

## Setup

1. **Settings | Tools | Geai**
2. Pick a **Provider** (Anthropic or OpenAI-compatible), set the **Model** and **Base URL** if you want
   to override the defaults, and paste your **API key**.
3. (Optional) Set **Geai source path** to this plugin's own checkout to enable self-modification.

Defaults: Anthropic → `claude-sonnet-4-6`; OpenAI-compatible → `https://api.deepseek.com` / `deepseek-chat`.

## Engines: API key vs Claude Code subscription

geai can run in two modes (Settings | Tools | Geai):

- **Built-in agent (API key)** — geai runs its own agent loop and calls the provider HTTP API directly with your API key. Provider-agnostic (Claude / DeepSeek / Qwen / local).
- **Claude Code engine (subscription login)** — enable *"Use Claude Code CLI as the engine"*. geai then hosts an in-IDE MCP server exposing all its tools and delegates the loop to your locally installed `claude` CLI, which authenticates with **your Claude Pro/Max subscription** (no API key, no per-token API cost). Requires the [Claude Code CLI](https://code.claude.com) installed and logged in (`claude` on PATH, or set the path in settings). geai still gates its own mutating tools via the approval prompt at the MCP layer. Note: from **June 15, 2026**, subscription usage through the CLI/SDK draws from a separate monthly Agent SDK credit.

The raw Anthropic API only accepts API keys, which is why subscription login must go through the Claude Code CLI rather than a bearer token — and reusing subscription tokens directly in third-party clients violates Anthropic's terms.

## Usage

Open the **Geai** tool window (right side) and type, e.g.:

> На UI уходят невалидные данные, не пойму где их теряем. Это во вкладке профиля.

geai will orient itself (`project_overview`), locate the relevant code, trace the value from source to
the UI sink, optionally set breakpoints and start a debug session to observe the real runtime state, and
report the root cause with concrete `file:line` evidence and a recommended fix. Press **Stop** to cancel a
turn; **New session** to start fresh. Previous sessions auto-resume on reopen.

## Building & running

This plugin targets IntelliJ IDEA 2025.2 (Java 21). The build daemon is pinned to JDK 21 via
`gradle/gradle-daemon-jvm.properties` (Kotlin 2.1.0 cannot run on JDK 25+), so it builds correctly even if
your `JAVA_HOME` points at a newer JDK.

```bash
./gradlew buildPlugin      # produces build/distributions/geai-*.zip
./gradlew runIde           # launches a sandbox IDE with geai installed
./gradlew test             # runs tests
```

Install the built zip via **Settings | Plugins | ⚙ | Install plugin from disk…**

## Self-modification

When geai lacks a capability for a task, it can grow one: it calls `self_info` to learn its own layout,
writes a new `AgentTool` with `self_patch`, registers it in `GeaiToolset`, rebuilds with `run_command`,
and asks you to reload the plugin (a running plugin can't hot-swap its own classes). After the restart,
the new capability is available and you can ask it to continue.

## Architecture

```
settings/   provider, model, keys (PasswordSafe), settings UI
llm/        provider-agnostic message/tool model + Anthropic & OpenAI-compatible clients (JDK HttpClient + Gson)
tools/      AgentTool framework + registry
  fs/       read / list / search / find / write / edit
  project/  project overview
  debug/    breakpoints + debug-session control (XDebugger)
  system/   run_command
  selfmod/  self_info / self_patch
agent/      AgentLoop (background, cancellable), system prompt, approval policy, GeaiAgentService
context/    project snapshot + transcript compaction
session/    JSON persistence + resume
toolWindow/ Swing chat UI
```

## Limitations & roadmap

- LLM responses are non-streaming in v1 (correct tool-calling prioritized); token streaming is a planned
  enhancement.
- Debugger integration sets breakpoints and reports the paused location; deep variable/stack evaluation at
  a breakpoint is the next increment (a natural candidate for self-modification).
- Account/OAuth login is not available for third-party API access; geai uses API keys.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
