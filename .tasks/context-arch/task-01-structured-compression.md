# Task 01: Structured Compression Summary

**Type:** Code Modification

## Goal

Replace the flat-text LLM summarization in ContextCompressor with a structured JSON-based compression that produces a `SemanticSummary` data class, preserving findings with file:line references, decisions, code changes, and next steps as discrete fields — not a prose blob.

## What to Do

- Create `SemanticSummary` data class in `com.github.saeldrit.geai.context`:
  ```kotlin
  data class SemanticSummary(
      val taskDescription: String,
      val findings: List<Finding>,     // file:line + what was found
      val decisions: List<String>,      // architectural/impl decisions made
      val codeChanges: List<String>,    // files modified + what changed
      val openQuestions: List<String>,  // unresolved items
      val nextSteps: List<String>,      // planned actions
  )
  data class Finding(val location: String, val summary: String)
  ```
- Create a new `SemanticCompressor` object in `com.github.saeldrit.geai.context` with:
  - A dedicated compression prompt that requests JSON output matching `SemanticSummary`
  - JSON parsing with fallback to raw text if parsing fails
  - A `renderForInjection(summary: SemanticSummary): String` method that formats the summary compactly for transcript injection
- Update `ContextCompressor.summarizeOldContext()` to use `SemanticCompressor` when available:
  - The structured summary replaces the plain-text recap message
  - The recap message format becomes: `[Structured summary of earlier steps]\n<findings>...</findings>\n<decisions>...</decisions>` etc.
- Update `SUMMARY_DOCTRINE` in `AgentLoop.kt` (line 866) and `TranscriptSummary.DOCTRINE` (line 19) to request structured JSON output
- Keep the existing text summarizer as a fallback when JSON parsing fails

## Files/Areas

- `src/main/kotlin/com/github/saeldrit/geai/context/SemanticSummary.kt` — NEW: data classes
- `src/main/kotlin/com/github/saeldrit/geai/context/SemanticCompressor.kt` — NEW: structured compression logic
- `src/main/kotlin/com/github/saeldrit/geai/context/ContextCompressor.kt` — Update `summarizeOldContext` to use structured output
- `src/main/kotlin/com/github/saeldrit/geai/context/TranscriptSummary.kt` — Update DOCTRINE
- `src/main/kotlin/com/github/saeldrit/geai/agent/AgentLoop.kt` — Update `SUMMARY_DOCTRINE` (line 866), `summarizerFor` (line 443)

## Key Points

- The compression prompt must explicitly request JSON and provide the schema
- JSON parsing must be lenient — LLMs sometimes emit markdown-wrapped JSON (`\`\`\`json ... \`\`\``)
- Fallback to plain text summary on any parse error — never lose context because of formatting
- `SemanticSummary.renderForInjection()` must produce output shorter than the raw transcript it replaced
- The `ContextCompressor.Summarizer` interface signature stays the same (returns String) — the structured parsing happens inside
- Existing `EAGER_TOOL_HEAD=2000` is already reasonable; this task only changes the summarization path

## Done When

- [ ] `SemanticSummary` and `Finding` data classes exist and compile
- [ ] `SemanticCompressor` object exists with structured compression prompt and JSON parsing
- [ ] `ContextCompressor.summarizeOldContext` produces structured summaries when the LLM returns valid JSON
- [ ] Plain text fallback works when JSON parsing fails
- [ ] Project compiles without errors
