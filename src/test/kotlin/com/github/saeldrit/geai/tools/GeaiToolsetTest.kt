package com.github.saeldrit.geai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progressive tool disclosure: the agent loop must advertise only CORE (+GRACE) until the model
 * loads an on-demand group. These are pure functions, so no IDE fixture is needed.
 */
class GeaiToolsetTest {

    private fun names(tools: List<AgentTool>) = tools.map { it.name }.toSet()

    @Test
    fun `core surface excludes on-demand groups`() {
        val core = names(GeaiToolset.advertisedTools(graceEnabled = false, activeGroups = emptySet()))
        assertTrue("core keeps navigation", core.containsAll(setOf("project_overview", "read_file", "edit_file")))
        assertFalse("debug tools are not advertised upfront", core.contains("run_command"))
        GeaiToolset.groupTools("debug").forEach {
            assertFalse("debug tool '${it.name}' must stay hidden until loaded", core.contains(it.name))
        }
        assertFalse("load_tools is advertised separately via loaderSpec, not as a regular tool", core.contains(GeaiToolset.LOAD_TOOLS))
    }

    @Test
    fun `grace adds its lean surface to core`() {
        val withGrace = names(GeaiToolset.advertisedTools(graceEnabled = true, activeGroups = emptySet()))
        val withoutGrace = names(GeaiToolset.advertisedTools(graceEnabled = false, activeGroups = emptySet()))
        assertTrue("grace adds resolve_ref/graph_query", withGrace.containsAll(setOf("resolve_ref", "graph_query")))
        assertFalse("baseline excludes grace tools", withoutGrace.contains("resolve_ref"))
    }

    @Test
    fun `psi semantic tools are advertised with grace and available to sub-agents`() {
        val grace = names(GeaiToolset.advertisedTools(graceEnabled = true, activeGroups = emptySet()))
        val sub = names(GeaiToolset.delegateTools())
        listOf("find_symbol", "find_usages", "find_implementations", "diagnostics").forEach { tool ->
            assertTrue("$tool advertised with GRACE", grace.contains(tool))
            assertTrue("sub-agent gets $tool", sub.contains(tool))
        }
        assertFalse(
            "lean baseline (grace off) excludes them",
            names(GeaiToolset.advertisedTools(graceEnabled = false, activeGroups = emptySet())).contains("find_usages"),
        )
    }

    @Test
    fun `loading a group reveals exactly that group`() {
        val withDebug = names(GeaiToolset.advertisedTools(graceEnabled = false, activeGroups = setOf("debug")))
        assertTrue("debug group is now visible", withDebug.containsAll(names(GeaiToolset.groupTools("debug"))))
        assertFalse("unrelated groups stay hidden", withDebug.contains("run_command"))
        assertFalse("unrelated groups stay hidden", withDebug.contains("self_patch"))

        val withRun = names(GeaiToolset.advertisedTools(graceEnabled = false, activeGroups = setOf("run")))
        assertTrue("run group reveals run_command", withRun.contains("run_command"))
    }

    @Test
    fun `unknown groups resolve to nothing`() {
        assertFalse(GeaiToolset.isGroup("nonsense"))
        assertTrue(GeaiToolset.groupTools("nonsense").isEmpty())
        assertEquals(
            "advertising an unknown group adds no tools",
            names(GeaiToolset.advertisedTools(false, emptySet())),
            names(GeaiToolset.advertisedTools(false, setOf("nonsense"))),
        )
    }

    @Test
    fun `delegate sub-agent toolset is read-only and cannot recurse`() {
        val sub = names(GeaiToolset.delegateTools())
        assertTrue("sub-agent can read & navigate", sub.containsAll(setOf("read_file", "search_text", "find_files", "project_overview")))
        // No mutation, no recursion, no progressive-disclosure plumbing.
        listOf("write_file", "edit_file", "run_command", "self_patch", "self_info", GeaiToolset.DELEGATE, GeaiToolset.LOAD_TOOLS, "ask_user")
            .forEach { assertFalse("sub-agent must not expose '$it'", sub.contains(it)) }
        GeaiToolset.groupTools("debug").forEach {
            assertFalse("sub-agent must not expose debug tool '${it.name}'", sub.contains(it.name))
        }
    }

    @Test
    fun `delegate spec requires a task`() {
        val spec = GeaiToolset.delegateSpec()
        assertEquals(GeaiToolset.DELEGATE, spec.name)
        assertTrue("delegate takes a task argument", spec.parametersJsonSchema.contains("\"task\""))
        assertTrue("task is required", spec.parametersJsonSchema.contains("\"required\":[\"task\"]"))
    }

    @Test
    fun `note spec requires text and is a meta-tool, not in the regular registry`() {
        val spec = GeaiToolset.noteSpec()
        assertEquals(GeaiToolset.NOTE, spec.name)
        assertTrue("note takes a text argument", spec.parametersJsonSchema.contains("\"text\""))
        assertTrue("text is required", spec.parametersJsonSchema.contains("\"required\":[\"text\"]"))
        // It's loop-intercepted (like load_tools/delegate), never advertised as a normal tool.
        assertFalse(names(GeaiToolset.advertisedTools(graceEnabled = true, activeGroups = emptySet())).contains(GeaiToolset.NOTE))
        assertFalse(names(GeaiToolset.delegateTools()).contains(GeaiToolset.NOTE))
    }

    @Test
    fun `loader spec names every group so the model can request it`() {
        val spec = GeaiToolset.loaderSpec()
        assertEquals(GeaiToolset.LOAD_TOOLS, spec.name)
        GeaiToolset.groupNames().forEach { group ->
            assertTrue("loader schema enumerates '$group'", spec.parametersJsonSchema.contains("\"$group\""))
            assertTrue("loader description lists '$group'", spec.description.contains(group))
        }
    }

    @Test
    fun `specs group is loadable and reveals the governance tools only when loaded`() {
        assertTrue(GeaiToolset.isGroup("specs"))
        val loaded = names(GeaiToolset.advertisedTools(graceEnabled = true, activeGroups = setOf("specs")))
        listOf("spec_list", "spec_lookup", "spec_validate", "spec_record").forEach {
            assertTrue("specs group reveals '$it'", loaded.contains(it))
        }
        assertFalse(
            "governance tools stay hidden until loaded",
            names(GeaiToolset.advertisedTools(graceEnabled = true, activeGroups = emptySet())).contains("spec_record"),
        )
    }

    @Test
    fun `grace advertises the graph navigation tools the doctrine names`() {
        // Drift guard: the system prompt and tool error texts reference these, so they MUST be
        // advertised+executable when GRACE is on — or a literal model hits an "Unknown tool" dead-end.
        val grace = names(GeaiToolset.advertisedTools(graceEnabled = true, activeGroups = emptySet()))
        listOf("context_bundle", "graph_query", "graph_neighbors").forEach {
            assertTrue("GRACE must advertise '$it' (named by the doctrine)", grace.contains(it))
        }
        val sub = names(GeaiToolset.delegateTools())
        assertTrue("sub-agent can re-bundle context", sub.contains("context_bundle"))
        assertTrue("sub-agent can walk the graph", sub.contains("graph_neighbors"))
    }
}
