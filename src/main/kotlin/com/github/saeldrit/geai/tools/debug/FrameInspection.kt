package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Icon

/**
 * Reads runtime state from the currently paused stack frame: local variables and arbitrary
 * expression evaluation. The XDebugger value APIs are asynchronous (results arrive on debugger
 * threads), so each call bridges to a synchronous [ToolResult] via a [CountDownLatch] with a
 * timeout. Language-agnostic: works for any debugger that implements the X* value model.
 */
internal object FrameInspection {

    private const val MAX_VARS = 40
    private const val PRESENT_TIMEOUT_MS = 2_500L

    fun evaluate(project: com.intellij.openapi.project.Project, expression: String, timeoutMs: Long): ToolResult {
        val frame = pausedFrame(project)
            ?: return ToolResult.error("Debugger is not paused at a frame. Set a breakpoint, start_debug, then await_pause.")
        val evaluator: XDebuggerEvaluator = frame.evaluator
            ?: return ToolResult.error("This debugger does not support expression evaluation.")

        val latch = CountDownLatch(1)
        val value = AtomicReference<String>()
        val error = AtomicReference<String>()
        evaluator.evaluate(
            expression,
            object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) {
                    result.computePresentation(captureNode { value.set(it); latch.countDown() }, XValuePlace.TREE)
                }

                override fun errorOccurred(errorMessage: String) {
                    error.set(errorMessage)
                    latch.countDown()
                }
            },
            frame.sourcePosition,
        )

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return ToolResult.error("Evaluation timed out after ${timeoutMs}ms.")
        error.get()?.let { return ToolResult.error("Evaluation error: $it") }
        return ToolResult.ok("$expression = ${value.get().orEmpty().ifBlank { "<no value>" }}")
    }

    fun locals(project: com.intellij.openapi.project.Project, timeoutMs: Long): ToolResult {
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
            builder.append(name).append(" = ").append(presentSync(value)).append('\n')
        }
        if (variables.size > MAX_VARS) builder.append("… (${variables.size - MAX_VARS} more)")
        return ToolResult.ok(builder.toString().trimEnd())
    }

    private fun pausedFrame(project: com.intellij.openapi.project.Project): XStackFrame? {
        val ref = AtomicReference<XStackFrame?>()
        ApplicationManager.getApplication().invokeAndWait {
            val session = XDebuggerManager.getInstance(project).currentSession
            ref.set(if (session != null && session.isPaused) session.currentStackFrame else null)
        }
        return ref.get()
    }

    private fun presentSync(value: XValue): String {
        val latch = CountDownLatch(1)
        val text = AtomicReference("")
        value.computePresentation(captureNode { text.set(it); latch.countDown() }, XValuePlace.TREE)
        latch.await(PRESENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return text.get().orEmpty().ifBlank { "<...>" }
    }

    private fun captureNode(onText: (String) -> Unit): XValueNode = object : XValueNode {
        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
            onText(if (!type.isNullOrBlank()) "$value ($type)" else value)
        }

        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
            val builder = StringBuilder()
            presentation.renderValue(textRenderer(builder))
            val rendered = builder.toString().trim()
            onText(presentation.type?.takeIf { it.isNotBlank() }?.let { "$rendered ($it)" } ?: rendered)
        }

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) = Unit
        override fun isObsolete(): Boolean = false
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
