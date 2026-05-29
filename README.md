# geai

![Build](https://github.com/Saeldrit/geai/workflows/Build/badge.svg)

<!-- Plugin description -->
**geai** is an autonomous debugging and coding agent that lives inside IntelliJ IDEA. Open its
tool window, describe a bug or task in plain language, and geai navigates the project, traces
data flow, sets breakpoints, drives the debugger, reads and edits files, and applies fixes that
match your codebase's style.

It connects to **Claude** (Anthropic) or any **OpenAI-compatible** endpoint (DeepSeek, Qwen,
OpenRouter, local models via Ollama / LM Studio / vLLM), or runs through the **Claude Code CLI**
using your subscription login with no per-token cost.
<!-- Plugin description end -->

## Installation

Install the `.zip` from [Releases](https://github.com/Saeldrit/geai/releases) via
**Settings → Plugins → ⚙ → Install Plugin from Disk**, or build from source (see below).

Requires IntelliJ IDEA 2025.2+.

## Engines

Two modes, configured in **Settings → Tools → Geai**:

**API key mode** — geai runs its own agent loop and calls the provider HTTP API directly.
Works with Anthropic Claude, DeepSeek, Qwen/DashScope, OpenRouter, or any OpenAI-compatible
server. API keys are stored in the IDE credential store (PasswordSafe), never in plain config.

**Claude Code engine** — enable *"Use Claude Code CLI as the engine"*. geai hosts an in-IDE
MCP server exposing all its tools and delegates the loop to your locally installed `claude` CLI,
which authenticates with your Claude Pro/Max subscription. No API key, no per-token cost.
Requires the [Claude Code CLI](https://code.claude.com) installed and logged in.

## GRACE

GRACE (Graph-RAG Anchored Code Engineering) is the context protocol that keeps the agent
grounded in project-specific rules and live code contracts instead of guessing.

It operates on two categories:

**Category A** — hard invariants, policies, and formulas stored in `spec/*.spec.xml`. The agent
reads these before implementing anything and must never violate them.

**Category B** — live contracts and symbols. Never stored statically; resolved on demand via
`resolve_ref` using URI schemes `psi:` (live JVM symbol via PSI), `file:` (source range), or
`openapi:` (endpoint schema from generated OpenAPI). This keeps contracts current with the
actual codebase and prevents hallucinated signatures.

The code graph is built with `graph_reindex` (FILE → class → method, inheritance, governance
edges) and navigated with `graph_query`, `graph_neighbors`, and `context_bundle`. The bundle
assembles a focused slice of anchors, specs, and neighborhood for a given task — the cheapest
path to "enough context to act" without reading whole files.

`spec_validate` detects drift: it re-resolves all Category-B anchors and reports OK / DRIFT /
BROKEN so regressions are caught mechanically.

## Tiered model routing

One provider, one API key, two model roles. Enable with *"GRACE tiered routing"* in settings.

The **navigator** (cheap model, e.g. `deepseek-chat` or `claude-haiku-4-5`) drives the agent
loop: planning, tool calls, graph navigation, context assembly. The **author** (strong model,
e.g. `claude-sonnet-4-6`) is invoked only via `escalate_author` when code must be written, and
receives a pre-assembled context bundle so it spends tokens on authoring, not orientation.

When tiering is off, all steps use the single configured model.

## Usage

Open the **Geai** tool window (right dock) and type a task:

> "Invalid data reaches the UI, I can't find where we lose it."

geai will orient itself, locate the relevant code, trace the value from source to sink,
optionally set breakpoints and start a debug session to observe real runtime state, and report
the root cause with concrete `file:line` evidence and a recommended fix.

When the agent genuinely cannot proceed without your input — ambiguous branch, destructive
action, whether to start a debug session — it calls `ask_user` and shows a focused dialog.
Routine tool calls run without interruption.

**Tool approval** — mutating tools (write, edit, run, self-patch) are auto-approved by default.
Disable in settings to get a per-call dialog with three options: Allow once / Allow for session
/ Deny.

## Build

```bash
git clone https://github.com/Saeldrit/geai.git
cd geai
./gradlew buildPlugin      # produces build/distributions/geai-*.zip
./gradlew runIde           # launches a sandbox IDE with geai installed
./gradlew test             # runs tests
```

Targets IntelliJ IDEA 2025.2 (build 252), JDK 21. The build daemon is pinned to JDK 21 via
`gradle/gradle-daemon-jvm.properties`.

## Architecture

```
settings/       provider, model, keys (PasswordSafe), settings UI
llm/            provider-agnostic message/tool model + Anthropic & OpenAI-compatible clients
tools/
  fs/           read / list / search / find / write / edit
  debug/        breakpoints, debug-session control, variable inspection, expression eval
  system/       run_command
  selfmod/      self_info / self_patch
  grace/        resolve_ref, spec_*, graph_*, context_bundle, escalate_author
  knowledge/    kb_lookup / kb_record / kb_forget (persistent NAV/STYLE/TECH/LESSON index)
  interaction/  ask_user
agent/          AgentLoop (background, cancellable), system prompt, approval policy
context/        project snapshot + transcript compaction
session/        JSON persistence + resume
anchor/         AnchorResolver SPI + file: / psi: / openapi: resolvers
spec/           SpecStore — reads/writes spec/*.spec.xml (Category A)
graph/          GeaiGraphStore + GraphIndexer (PSI + governance edges)
bundle/         ContextBundler + Ranker SPI (deterministic default, vector shim)
toolWindow/     JCEF chat UI + Swing fallback
mcp/            in-IDE MCP server (for Claude Code engine)
```

## Limitations

- Responses are non-streaming; the UI updates after each complete agent step.
- Concurrent sessions across multiple project windows are not supported.
- GRACE graph operations require a fully indexed project — run `graph_reindex` after large
  refactors or initial clone.

---

See [docs/GRACE_ARCHITECTURE.md](docs/GRACE_ARCHITECTURE.md) for the full GRACE design.

Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
