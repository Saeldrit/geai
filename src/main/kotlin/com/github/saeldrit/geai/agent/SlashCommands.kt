package com.github.saeldrit.geai.agent

object SlashCommands {

    data class Parsed(val preloadGroups: Set<String>, val directive: String?)

    private data class Mode(val groups: Set<String>, val directive: String)

    private val MODES: Map<String, Mode> = buildMap {
        val debug = Mode(
            setOf("debug"),
            "MODE: DEBUGGING. Go straight to it — do NOT over-orient. Locate the failing path quickly " +
                "(find_symbol / find_usages / one narrow search). Pick the right technique: tracing a " +
                "VALUE through a flow → set_tracepoint along the path (with the value expression), run " +
                "the scenario, read trace_log — no stepping. A crash/exception → break_on_exception, " +
                "trigger it, await_pause at the throw. A specific state → set_breakpoint with a " +
                "'condition'. Otherwise classic: breakpoints in ONE step, start_debug, await_pause " +
                "(returns locals), debug_step/debug_evaluate until you SEE the divergence. Remove the " +
                "breakpoints you set when done. Be fast: minimal reads, no ceremony.",
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

    fun names(): Set<String> = MODES.keys

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

    fun parse(text: String): Parsed {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return Parsed(emptySet(), null)
        val token = trimmed.drop(1).takeWhile { !it.isWhitespace() }.lowercase()
        val mode = MODES[token] ?: return Parsed(emptySet(), null)
        return Parsed(mode.groups, mode.directive)
    }
}
