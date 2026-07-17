# geai

![Build](https://github.com/Saeldrit/geai/workflows/Build/badge.svg)

<!-- Plugin description -->
**geai** is an autonomous debugging and coding agent that lives inside IntelliJ IDEA. Open its
tool window, describe a bug or task in plain language, and geai navigates the project, traces
data flow, sets breakpoints, drives the debugger, reads and edits files, and applies fixes that
match your codebase's style.

It connects to **Claude** (Anthropic) or any **OpenAI-compatible** endpoint — DeepSeek, Qwen,
OpenRouter, or local models via Ollama / LM Studio / vLLM.
<!-- Plugin description end -->

## Installation

Install the `.zip` from [Releases](https://github.com/Saeldrit/geai/releases) via
**Settings → Plugins → ⚙ → Install Plugin from Disk**, or build from source (see below).

Requires IntelliJ IDEA 2025.2+.

## Models & providers

geai runs its own agent loop and calls the provider's HTTP API directly — there is no external
CLI or sidecar process. Configure it in **Settings → Tools → Geai**: Anthropic Claude,
DeepSeek, Qwen/DashScope, OpenRouter, or any OpenAI-compatible server (including local Ollama /
LM Studio / vLLM). Responses stream token-by-token. API keys are stored in the IDE credential
store (PasswordSafe), never in plain config.

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

Code structure is read **live from IntelliJ's PSI and index** — geai keeps no separate
materialized graph. `graph_query` finds symbols by name and the governing specs that apply to
them, `graph_neighbors` expands a symbol's neighbourhood (declared methods, super types, file
siblings, governing rules), and `context_bundle` assembles a focused slice — specs verbatim,
resolved contracts, and that neighbourhood — for a given task. It is the cheapest path to
"enough context to act" without reading whole files. Because structure comes from the IDE's own
index, it is always current and needs no rebuild step.

`spec_validate` detects drift: it re-resolves all Category-B anchors and reports OK / DRIFT /
BROKEN so regressions are caught mechanically.

## geai Hub (multi-IDE orchestration)

geai can register as a **spoke agent** on the geai Hub — a desktop orchestrator that routes
tasks across IDEs, runs cross-project questions in parallel and merges the answers. Run
**Tools → "Geai: Connect to Hub"** with the Hub running (default `ws://localhost:9876/ws`).
Hub tasks execute through the same agent loop as the chat window in a fresh session; progress,
logs, artifacts, token spend and system metrics stream back. The spoke also serves the hub's
loops: `plan` (objective → JSON task graph), `verify` (run tests → verdict), `map` (project
architecture fragment from the IDE index), and contract rejection. Hub URL and auto-connect
live under *Settings → Tools → Geai → Show advanced settings*.

## Tiered model routing

One provider, one API key, two model roles. Enable with *"GRACE tiered routing"* in settings.

The **navigator** (cheap model, e.g. `deepseek-chat` or `claude-haiku-4-5`) drives the agent
loop: planning, tool calls, structure navigation, context assembly. The **author** (strong
model, e.g. `claude-sonnet-4-6`) is invoked only via `escalate_author` when code must be written,
and receives a pre-assembled context bundle so it spends tokens on authoring, not orientation.

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

**Skills** — four quick-start cards in the welcome screen: *Debug*, *Explain*, *Implement*,
*Review*. Each pre-fills the composer with a task-specific prompt.

**Context bar** — a visual progress indicator below the topbar shows how much of the model's
context window is used. The bar changes colour as usage grows (green → yellow → red) and
displays the estimated token cost.

**Tool approval** — mutating tools (write, edit, run, self-patch) are auto-approved by default.
Disable in settings to get a per-call dialog with three options: Allow once / Allow for session
/ Deny.

**Settings** — the settings page (*Settings → Tools → Geai*) has a single "Show advanced
settings" toggle at the bottom. GRACE tiered routing, navigator model, and auto-approve options
are hidden by default to keep the page simple.

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
  fs/           read / list / search (index-backed) / find / write / edit
  debug/        breakpoints, debug-session control, variable inspection, expression eval
  system/       run_command
  selfmod/      self_info / self_patch
  grace/        resolve_ref, spec_*, graph_query / graph_neighbors, context_bundle, escalate_author
  knowledge/    kb_lookup / kb_record / kb_forget (persistent NAV/STYLE/TECH/LESSON index)
  interaction/  ask_user
agent/          AgentLoop (background, cancellable), system prompt, approval policy
context/        project snapshot + transcript compaction
session/        JSON persistence + resume
anchor/         AnchorResolver SPI + file: / psi: / openapi: resolvers
spec/           SpecStore — reads/writes spec/*.spec.xml (Category A)
graph/          PsiStructure — live PSI navigation (symbols, neighbours, bundle seeds) + GraphModel vocabulary
bundle/         ContextBundler + Ranker SPI (deterministic default, vector shim)
toolWindow/     JCEF chat UI + Swing fallback
```

## Limitations

- Concurrent sessions across multiple project windows are not supported.
- Structure navigation and search read IntelliJ's index, so results are most complete once the
  initial project indexing finishes — during "dumb mode" geai falls back to a direct scan.
- `psi:` anchors and class-structure navigation require the Java/JVM plugin; on a non-JVM IDE they
  degrade to a clean error and geai relies on file-level and spec context.

---

See [docs/GRACE_ARCHITECTURE.md](docs/GRACE_ARCHITECTURE.md) for the full GRACE design.

Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
