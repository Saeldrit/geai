package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.awt.Font
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon

/**
 * Reads runtime state from the currently paused stack frame: local variables and arbitrary expression
 * evaluation. The XDebugger value APIs are asynchronous AND multi-stage — `evaluate` hands back an
 * [XValue] REFERENCE, and the concrete data arrives later through [XValue.computePresentation] (often a
 * "Collecting data…" placeholder first, then the real value) or, for large/lazy values, an
 * [XFullValueEvaluator]. We bridge all of that to a synchronous [ToolResult] and, when a value still
 * won't materialise (e.g. a jOOQ/Hibernate lazy proxy), force it to a concrete string. Language-agnostic.
 */
internal object FrameInspection {

    private const val MAX_VARS = 40
    private const val PRESENT_TIMEOUT_MS = 2_500L

    private sealed interface Eval {
        data class Value(val text: String) : Eval
        data class Error(val message: String) : Eval
        data class Failure(val message: String) : Eval
    }

    fun evaluate(project: Project, expression: String, timeoutMs: Long): ToolResult {
        val first = evaluateExpr(project, expression, timeoutMs)
        // A lazy/proxy value (jOOQ, Hibernate, deferred fields) resolves to a reference whose
        // presentation stays "Collecting data…" / blank. Force materialisation by stringifying:
        // java.lang.String.valueOf works in Java AND Kotlin frames and is null-safe, so the proxy
        // collapses into the concrete string we actually want to see.
        if (first is Eval.Value && isUnresolved(first.text)) {
            val forced = evaluateExpr(project, "java.lang.String.valueOf($expression)", timeoutMs)
            if (forced is Eval.Value && !isUnresolved(forced.text)) {
                return ToolResult.ok("$expression = ${forced.text}")
            }
        }
        return when (first) {
            is Eval.Value -> ToolResult.ok("$expression = ${first.text.ifBlank { "<no value>" }}")
            is Eval.Error -> ToolResult.error("Evaluation error: ${first.message}")
            is Eval.Failure -> ToolResult.error(first.message)
        }
    }

    fun locals(project: Project, timeoutMs: Long): ToolResult {
        val frame = pausedFrame(project)
            ?: return ToolResult.error("Debugger is not paused at a frame. Set a breakpoint, start_debug, then await_pause.")

        val latch = CountDownLatch(1)
        val variables = mutableListOf<Pair<String, XValue>>()
        val error = AtomicReference<String>()
        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) variables.add(children.getName(i) to children.getValue(i))
                if (last) latch.countDown()
            }

            override fun tooManyChildren(remaining: Int) = latch.countDown()
            override fun setAlreadySorted(alreadySorted: Boolean) = Unit
            override fun setErrorMessage(errorMessage: String) {
                error.set(errorMessage)
                latch.countDown()
            }

            override fun setErrorMessage(errorMessage: String, link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?) =
                setErrorMessage(errorMessage)

            override fun setMessage(
                message: String,
                icon: Icon?,
                attributes: SimpleTextAttributes,
                link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?,
            ) = Unit

            override fun isObsolete(): Boolean = false
        })

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return ToolResult.error("Reading locals timed out after ${timeoutMs}ms.")
        error.get()?.let { return ToolResult.error("Could not read locals: $it") }
        if (variables.isEmpty()) return ToolResult.ok("No local variables in the current frame.")

        val builder = StringBuilder()
        variables.take(MAX_VARS).forEach { (name, value) ->
            builder.append(name).append(" = ").append(resolveValue(value, PRESENT_TIMEOUT_MS)).append('\n')
        }
        if (variables.size > MAX_VARS) builder.append("… (${variables.size - MAX_VARS} more)")
        return ToolResult.ok(builder.toString().trimEnd())
    }

    /** Evaluate one expression and resolve its value to concrete text (no forcing — see [evaluate]). */
    private fun evaluateExpr(project: Project, expression: String, timeoutMs: Long): Eval {
        val frame = pausedFrame(project)
            ?: return Eval.Failure("Debugger is not paused at a frame. Set a breakpoint, start_debug, then await_pause.")
        val evaluator: XDebuggerEvaluator = frame.evaluator
            ?: return Eval.Failure("This debugger does not support expression evaluation.")

        val latch = CountDownLatch(1)
        val value = AtomicReference<XValue?>()
        val error = AtomicReference<String?>()
        evaluator.evaluate(
            expression,
            object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) {
                    value.set(result)
                    latch.countDown()
                }

                override fun errorOccurred(errorMessage: String) {
                    error.set(errorMessage)
                    latch.countDown()
                }
            },
            frame.sourcePosition,
        )

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return Eval.Failure("Evaluation timed out after ${timeoutMs}ms.")
        error.get()?.let { return Eval.Error(it) }
        val xv = value.get() ?: return Eval.Value("")
        return Eval.Value(resolveValue(xv, timeoutMs))
    }

    /**
     * Resolve an [XValue] to concrete text. The presentation callback is async and multi-stage: the
     * first call can be a "Collecting data…" PLACEHOLDER, with the real value arriving later — via a
     * second [XValueNode.setPresentation] or, for large/lazy values, an [XFullValueEvaluator]. We keep
     * the latest text but only FINALISE on a real value (or the full-value result), so we stop returning
     * the reference-stage placeholder that produced empty/garbled evaluations.
     */
    private fun resolveValue(value: XValue, timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        val latest = AtomicReference("")
        value.computePresentation(
            object : XValueNode {
                private fun accept(text: String, forceFinal: Boolean = false) {
                    latest.set(text)
                    if (forceFinal || !isUnresolved(text)) latch.countDown()
                }

                override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                    accept(if (!type.isNullOrBlank()) "$value ($type)" else value)
                }

                override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                    val builder = StringBuilder()
                    presentation.renderValue(textRenderer(builder))
                    val rendered = builder.toString().trim()
                    accept(presentation.type?.takeIf { it.isNotBlank() }?.let { "$rendered ($it)" } ?: rendered)
                }

                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
                    // The shown value is truncated/lazy (the "Collecting data…" path). Pull the full text.
                    fullValueEvaluator.startEvaluation(object : XFullValueEvaluator.XFullValueEvaluationCallback {
                        override fun evaluated(fullValue: String) = accept(fullValue, forceFinal = true)
                        override fun evaluated(fullValue: String, font: Font?) = accept(fullValue, forceFinal = true)
                        // Full-value fetch failed: stop waiting and keep what we have (the forcing pass in
                        // evaluate() will then try java.lang.String.valueOf to materialise it).
                        override fun errorOccurred(errorMessage: String) = latch.countDown()
                        override fun isObsolete(): Boolean = false
                    })
                }

                override fun isObsolete(): Boolean = false
            },
            XValuePlace.TREE,
        )
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return latest.get().orEmpty().ifBlank { "<...>" }
    }

    private fun pausedFrame(project: Project): XStackFrame? {
        val ref = AtomicReference<XStackFrame?>()
        ApplicationManager.getApplication().invokeAndWait {
            val session = XDebuggerManager.getInstance(project).currentSession
            ref.set(if (session != null && session.isPaused) session.currentStackFrame else null)
        }
        return ref.get()
    }

    /** A still-loading / reference-stage presentation — not a real value worth returning. */
    private fun isUnresolved(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isEmpty() || trimmed == "<...>" || trimmed == "…" || trimmed == "..." ||
            trimmed == "<no value>" || trimmed.contains("Collecting data", ignoreCase = true)
    }

    private fun textRenderer(builder: StringBuilder): XValuePresentation.XValueTextRenderer =
        object : XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: String) { builder.append(value) }
            override fun renderStringValue(value: String) { builder.append(value) }
            override fun renderNumericValue(value: String) { builder.append(value) }
            override fun renderKeywordValue(value: String) { builder.append(value) }
            override fun renderValue(value: String, key: TextAttributesKey) { builder.append(value) }
            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { builder.append(value) }
            override fun renderComment(comment: String) { builder.append(comment) }
            override fun renderSpecialSymbol(symbol: String) { builder.append(symbol) }
            override fun renderError(error: String) { builder.append("ERROR: ").append(error) }
        }
}
