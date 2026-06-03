package com.github.saeldrit.geai.agent

/**
 * Lightweight slash-command layer. A leading `/<cmd>` in the user's message selects a working MODE:
 * it PRE-LOADS the tool group that mode needs (so the model does not spend a whole round-trip on
 * `load_tools`) and appends a focused directive to the volatile system suffix (placed AFTER the cache
 * breakpoint, so the stable doctrine keeps caching). Unknown or absent commands are a no-op — the
 * message is used verbatim. This is what makes e.g. `/debug` go STRAIGHT to debugging instead of
 * orienting first.
 */
object SlashCommands {

    data class Parsed(val preloadGroups: Set<String>, val directive: String?)

    private data class Mode(val groups: Set<String>, val directive: String)

    private val MODES: Map<String, Mode> = buildMap {
        val debug = Mode(
            setOf("debug"),
            "MODE: DEBUGGING. Go straight to it — do NOT over-orient. Locate the failing path quickly " +
                "(find_symbol / find_usages / one narrow search), set the breakpoints along it in ONE step, " +
                "start_debug, then await_pause with a long timeout and DRIVE the debugger yourself " +
                "(debug_step over/into/out/resume, debug_variables, debug_evaluate) until you see where the " +
                "data diverges. Remove the breakpoints you set when done. Be fast: minimal reads, no ceremony.",
        )
        put("debug", debug); put("debugger", debug); put("дебаг", debug); put("дебагер", debug)

        val run = Mode(
            setOf("run"),
            "MODE: RUN. Use run_command to build / run / test / git as needed to reproduce and verify; report concisely.",
        )
        put("run", run)

        val explain = Mode(
            emptySet(),
            "MODE: EXPLAIN. Explain how the target works and show where it is used (find_usages), citing file:line. Do not edit.",
        )
        put("explain", explain); put("объясни", explain)

        val implement = Mode(
            emptySet(),
            "MODE: IMPLEMENT. Study the affected code first, then make the SMALLEST style-matching change; verify after.",
        )
        put("implement", implement); put("feature", implement)

        val refactor = Mode(
            emptySet(),
            "MODE: REFACTOR. Preserve behavior. Show a short plan, then apply incremental, style-matching changes.",
        )
        put("refactor", refactor)

        val test = Mode(
            emptySet(),
            "MODE: TEST. Write tests in the project's existing style covering main and edge cases; run them if possible.",
        )
        put("test", test); put("tests", test)

        val review = Mode(
            emptySet(),
            "MODE: REVIEW. Review for correctness, bugs, security, and simplifications; return findings with file:line. Do not edit unless asked.",
        )
        put("review", review)

        val security = Mode(
            emptySet(),
            "MODE: SECURITY. Hunt for injection, hardcoded secrets, broken authz, unsafe deserialization; report concrete findings with file:line and fixes.",
        )
        put("security", security)
    }

    /** The command words known to this layer — for UI hints / discoverability. */
    fun names(): Set<String> = MODES.keys

    /** Primary commands with one-line descriptions for the UI "/" menu (aliases omitted). */
    fun catalog(): List<Pair<String, String>> = listOf(
        "debug" to "Set breakpoints and drive the debugger to the root cause",
        "run" to "Build, run, test, or git to reproduce and verify",
        "explain" to "Explain how code works and show where it is used",
        "implement" to "Make the smallest style-matching change to add a feature or fix",
        "refactor" to "Refactor while preserving behavior",
        "test" to "Write tests in the project's existing style",
        "review" to "Review for correctness, bugs, security, and simplifications",
        "security" to "Hunt for injection, secrets, broken authz, unsafe deserialization",
    )

    /** Parse a leading `/<cmd>` token. Returns empty groups + null directive when there is no command. */
    fun parse(text: String): Parsed {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return Parsed(emptySet(), null)
        val token = trimmed.drop(1).takeWhile { !it.isWhitespace() }.lowercase()
        val mode = MODES[token] ?: return Parsed(emptySet(), null)
        return Parsed(mode.groups, mode.directive)
    }
}
