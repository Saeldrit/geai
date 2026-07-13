package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
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

internal object FrameInspection {

    private const val MAX_VARS = 40
    private const val PRESENT_TIMEOUT_MS = 2_500L
    private const val MAX_DUMP_DEPTH = 5

    private data class Presented(
        val text: String,
        val type: String?,
        val hasChildren: Boolean,
        val fullEvaluator: XFullValueEvaluator?,
    )

    /** Evaluate a single expression and return its string value (parsed from the ToolResult). */
    fun evaluateSingle(project: Project, expression: String, timeoutMs: Long): String {
        val result = evaluateAll(project, listOf(expression), timeoutMs)
        if (result.isError) return "<eval error: ${result.content}>"
        return result.content.removePrefix("$expression = ").trim()
    }

    /** Evaluate several expressions in the paused frame in one shot — frame/evaluator resolved once. */
    fun evaluateAll(project: Project, expressions: List<String>, timeoutMs: Long): ToolResult {
        if (expressions.isEmpty()) return ToolResult.error("Provide at least one expression in 'expressions'.")
        val frame = pausedFrame(project)
            ?: return ToolResult.error("Debugger is not paused at a frame. Set a breakpoint, start_debug, then await_pause.")
        val evaluator = frame.evaluator
            ?: return ToolResult.error("This debugger does not support expression evaluation.")
        val position = frame.sourcePosition

        val builder = StringBuilder()
        expressions.forEach { expression ->
            builder.append(expression).append(" = ")
                .append(evaluateOne(evaluator, position, expression, timeoutMs))
                .append('\n')
        }
        return ToolResult.ok(builder.toString().trimEnd())
    }

    fun locals(project: Project, timeoutMs: Long): ToolResult {
        val frame = pausedFrame(project)
            ?: return ToolResult.error("Debugger is not paused at a frame. Set a breakpoint, start_debug, then await_pause.")
        val variables = readChildren(frame::computeChildren, timeoutMs)
        if (variables.isEmpty()) return ToolResult.ok("No local variables in the current frame.")

        val builder = StringBuilder()
        variables.take(MAX_VARS).forEach { (name, value) ->
            builder.append(name).append(" = ").append(presentValue(value, PRESENT_TIMEOUT_MS).text.ifBlank { "<...>" }).append('\n')
        }
        if (variables.size > MAX_VARS) builder.append("… (${variables.size - MAX_VARS} more)")
        return ToolResult.ok(builder.toString().trimEnd())
    }

    /**
     * Dump an object and its fields up to [depth] levels deep in ONE call, so the model gets the whole
     * tree at once instead of probing field-by-field over many round-trips. Children per level are
     * capped at [MAX_VARS] (overflow marked) to keep the output bounded on large collections.
     */
    fun dumpObject(project: Project, expression: String, depth: Int, timeoutMs: Long): ToolResult {
        val frame = pausedFrame(project)
            ?: return ToolResult.error("Debugger is not paused at a frame. Set a breakpoint, start_debug, then await_pause.")
        val evaluator = frame.evaluator
            ?: return ToolResult.error("This debugger does not support expression evaluation.")

        val (root, error) = evaluateXValue(evaluator, frame.sourcePosition, expression, timeoutMs)
        if (error != null) return ToolResult.error("Evaluation error: $error")
        if (root == null) return ToolResult.ok("$expression = <no value>")

        val maxDepth = depth.coerceIn(1, MAX_DUMP_DEPTH)
        val builder = StringBuilder()
        builder.append(expression).append(" = ").append(presentValue(root, timeoutMs).text.ifBlank { "<...>" }).append('\n')
        dumpChildren(root, maxDepth, 1, timeoutMs, builder)
        return ToolResult.ok(builder.toString().trimEnd())
    }

    private fun dumpChildren(value: XValue, maxDepth: Int, depth: Int, timeoutMs: Long, builder: StringBuilder) {
        val children = readChildren(value::computeChildren, timeoutMs)
        val indent = "  ".repeat(depth)
        children.take(MAX_VARS).forEach { (name, child) ->
            val presented = presentValue(child, timeoutMs)
            builder.append(indent).append(name).append(" = ").append(presented.text.ifBlank { "<...>" }).append('\n')
            if (presented.hasChildren && depth < maxDepth) {
                dumpChildren(child, maxDepth, depth + 1, timeoutMs, builder)
            }
        }
        if (children.size > MAX_VARS) builder.append(indent).append("… (${children.size - MAX_VARS} more)\n")
    }

    private fun evaluateOne(evaluator: XDebuggerEvaluator, position: XSourcePosition?, expression: String, timeoutMs: Long): String {
        val (value, error) = evaluateXValue(evaluator, position, expression, timeoutMs)
        if (error != null) return "ERROR: $error"
        if (value == null) return "<no value>"
        val presented = presentValue(value, timeoutMs)
        if (presented.text.isNotBlank() && !isPlaceholder(presented.text)) return presented.text
        val (forcedValue, forcedError) = evaluateXValue(evaluator, position, "java.lang.String.valueOf($expression)", timeoutMs)
        if (forcedError == null && forcedValue != null) {
            val forced = presentValue(forcedValue, timeoutMs).text
            if (forced.isNotBlank() && !isPlaceholder(forced)) return forced
        }
        return presented.text.ifBlank { "<no value>" }
    }

    private fun evaluateXValue(
        evaluator: XDebuggerEvaluator,
        position: XSourcePosition?,
        expression: String,
        timeoutMs: Long,
    ): Pair<XValue?, String?> {
        val latch = CountDownLatch(1)
        val value = AtomicReference<XValue?>(null)
        val error = AtomicReference<String?>(null)
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
            position,
        )
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null to "timed out after ${timeoutMs}ms"
        error.get()?.let { return null to it }
        return value.get() to null
    }

    private fun readChildren(compute: (XCompositeNode) -> Unit, timeoutMs: Long): List<Pair<String, XValue>> {
        val latch = CountDownLatch(1)
        val out = mutableListOf<Pair<String, XValue>>()
        compute(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) out.add(children.getName(i) to children.getValue(i))
                if (last) latch.countDown()
            }

            override fun tooManyChildren(remaining: Int) = latch.countDown()
            override fun setAlreadySorted(alreadySorted: Boolean) = Unit
            override fun setErrorMessage(errorMessage: String) = latch.countDown()
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) = latch.countDown()
            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) = Unit
            override fun isObsolete(): Boolean = false
        })
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    private fun presentValue(value: XValue, timeoutMs: Long): Presented {
        val done = CountDownLatch(1)
        val last = AtomicReference(Presented("", null, false, null))
        val gotFull = AtomicReference(false)

        val node = object : XValueNode {
            override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                last.set(Presented(value, type, hasChildren, last.get().fullEvaluator))
                if (!isPlaceholder(value)) {
                    gotFull.set(true)
                    done.countDown()
                }
            }

            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                val sb = StringBuilder()
                presentation.renderValue(textRenderer(sb))
                val rendered = sb.toString().trim()
                last.set(Presented(rendered, presentation.type, hasChildren, last.get().fullEvaluator))
                if (!isPlaceholder(rendered)) {
                    gotFull.set(true)
                    done.countDown()
                }
            }

            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
                last.set(last.get().copy(fullEvaluator = fullValueEvaluator))
                done.countDown()
            }

            override fun isObsolete(): Boolean = false
        }

        value.computePresentation(node, XValuePlace.TREE)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)

        var result = last.get()
        val fullEvaluator = result.fullEvaluator
        if (!gotFull.get() && fullEvaluator != null) {
            forceFullValue(fullEvaluator, timeoutMs)?.let { result = result.copy(text = it) }
        }
        return result
    }

    private fun forceFullValue(evaluator: XFullValueEvaluator, timeoutMs: Long): String? {
        val done = CountDownLatch(1)
        val full = AtomicReference<String?>(null)
        evaluator.startEvaluation(object : XFullValueEvaluator.XFullValueEvaluationCallback {
            override fun evaluated(fullValue: String) {
                full.set(fullValue)
                done.countDown()
            }

            override fun evaluated(fullValue: String, font: Font?) {
                full.set(fullValue)
                done.countDown()
            }

            override fun errorOccurred(errorMessage: String) = done.countDown()
            override fun isObsolete(): Boolean = false
        })
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return full.get()
    }

    private fun isPlaceholder(s: String): Boolean {
        val t = s.trim()
        return t.isEmpty() || t == "…" || t == "..." || t.startsWith("Collecting")
    }

    private fun pausedFrame(project: Project): XStackFrame? {
        val ref = AtomicReference<XStackFrame?>()
        ApplicationManager.getApplication().invokeAndWait {
            val session = XDebuggerManager.getInstance(project).currentSession
            ref.set(if (session != null && session.isPaused) session.currentStackFrame else null)
        }
        return ref.get()
    }

    /** Top [maxFrames] frames of the paused session's active execution stack (async → sync). */
    fun topFrames(project: Project, maxFrames: Int, timeoutMs: Long): List<XStackFrame> {
        val stackRef = AtomicReference<com.intellij.xdebugger.frame.XExecutionStack?>(null)
        ApplicationManager.getApplication().invokeAndWait {
            val session = XDebuggerManager.getInstance(project).currentSession
            stackRef.set(if (session != null && session.isPaused) session.suspendContext?.activeExecutionStack else null)
        }
        val stack = stackRef.get() ?: return emptyList()
        val latch = CountDownLatch(1)
        val out = ArrayList<XStackFrame>()
        stack.computeStackFrames(
            0,
            object : com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer {
                override fun addStackFrames(frames: MutableList<out XStackFrame>, last: Boolean) {
                    synchronized(out) { out.addAll(frames) }
                    if (last) latch.countDown()
                }

                override fun errorOccurred(errorMessage: String) {
                    latch.countDown()
                }
            },
        )
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return synchronized(out) { out.take(maxFrames) }
    }

    /** Best-effort "file:line" for a specific stack frame; blank source position falls back to a marker. */
    fun framePosition(project: Project, frame: XStackFrame): String {
        val position = frame.sourcePosition
        return if (position != null) "${position.file.name}:${position.line + 1}" else "<no source>"
    }

    /** Local variables of a SPECIFIC frame (not only the current one), formatted with a two-space indent. */
    fun localsOf(frame: XStackFrame, timeoutMs: Long, maxVars: Int): String {
        val variables = readChildren(frame::computeChildren, timeoutMs)
        if (variables.isEmpty()) return "    <no locals>"
        val builder = StringBuilder()
        variables.take(maxVars).forEach { (name, value) ->
            builder.append("    ").append(name).append(" = ")
                .append(presentValue(value, timeoutMs).text.ifBlank { "<...>" }).append('\n')
        }
        if (variables.size > maxVars) builder.append("    … (${variables.size - maxVars} more)\n")
        return builder.toString().trimEnd('\n')
    }

    private fun textRenderer(sb: StringBuilder): XValuePresentation.XValueTextRenderer =
        object : XValuePresentation.XValueTextRenderer {
            override fun renderValue(value: String) { sb.append(value) }
            override fun renderValue(value: String, key: TextAttributesKey) { sb.append(value) }
            override fun renderStringValue(value: String) { sb.append(value) }
            override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) { sb.append(value) }
            override fun renderNumericValue(value: String) { sb.append(value) }
            override fun renderKeywordValue(value: String) { sb.append(value) }
            override fun renderComment(comment: String) { sb.append(comment) }
            override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
            override fun renderError(error: String) { sb.append(error) }
        }
}