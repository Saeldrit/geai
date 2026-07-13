#!/usr/bin/env python3
"""
Remove comments from Kotlin/Java source files.
- Remove ALL single-line comments (// ...) that are standalone or trailing
- Remove non-essential javadoc blocks (/** */)
- Keep essential javadocs (complex public API docs, non-obvious logic)
"""

import re
import os
from pathlib import Path

# Essential javadoc patterns to KEEP (these explain non-obvious things)
# We keep javadocs that contain keywords indicating complex/non-obvious documentation
KEEP_PATTERNS = [
    'non-obvious', 'contract', 'algorithm', 'cancellation', 'thread',
    'concurrent', 'race', 'cache', 'overflow', 'budget', 'backstop',
    'runaway', 'fingerprint', 'stuck-loop', 'streaming', 'SSE',
    'architecture', 'migration', 'breaking change', 'MUST', 'SHOULD',
    'security', 'persistence', 'compaction', 'summariz',
]

# Files/packages where ALL javadocs on the object/interface/class are essential
# (public API surface that agents/users need to understand)
ESSENTIAL_API_PATTERNS = [
    # Tool interfaces
    'tools/AgentTool.kt',
    'tools/ToolArgs.kt',
    'tools/ToolContext.kt',
    'tools/ToolResult.kt',
    'tools/ToolRegistry.kt',
    # Core interfaces
    'llm/LlmClient.kt',
    'llm/ToolSpec.kt',
    'llm/Chat.kt',
    'llm/Messages.kt',
    # Public services
    'context/ProjectContextGatherer.kt',
    'hub/HubProtocol.kt',
    'hub/HubService.kt',
    'hub/HubClient.kt',
    # GRACE tools
    'tools/grace/ContextBundleTool.kt',
    'tools/grace/EscalateAuthorTool.kt',
    'tools/grace/GraphNeighborsTool.kt',
    'tools/grace/GraphQueryTool.kt',
    'tools/grace/ResolveRefTool.kt',
    'tools/grace/SpecListTool.kt',
    'tools/grace/SpecLookupTool.kt',
    'tools/grace/SpecRecordTool.kt',
    'tools/grace/SpecValidateTool.kt',
    'tools/interaction/AskUserTool.kt',
    'tools/knowledge/SkillTool.kt',
    'tools/psi/DiagnosticsTool.kt',
    'tools/psi/FindImplementationsTool.kt',
    'tools/psi/FindSymbolTool.kt',
    'tools/psi/FindUsagesTool.kt',
    'tools/psi/JvmSymbols.kt',
    'tools/system/RunOutputService.kt',
    'tools/selfmod/SelfInfoTool.kt',
    'tools/selfmod/SelfPatchTool.kt',
    'tools/debug/DebuggerSupport.kt',
    'tools/debug/FrameInspection.kt',
    'tools/debug/TracepointRecorder.kt',
    # Bundle/ranker
    'bundle/ContextBundler.kt',
    'bundle/DeterministicRanker.kt',
    'bundle/GraceTelemetry.kt',
    'bundle/Ranker.kt',
    'bundle/Rankers.kt',
    'bundle/Atom.kt',
    # Settings
    'settings/GeaiSettings.kt',
    'settings/GeaiSecrets.kt',
    'settings/LlmProvider.kt',
    # Session
    'session/GeaiSessionStore.kt',
    # Knowledge
    'knowledge/GeaiKnowledgeStore.kt',
    # Spec
    'spec/SpecModel.kt',
    'spec/SpecStore.kt',
    # Agent
    'agent/AgentEvent.kt',
    'agent/AgentLoop.kt',
    # LLM clients
    'llm/anthropic/AnthropicClient.kt',
    'llm/http/HttpTransport.kt',
    'llm/http/JsonSupport.kt',
    'llm/openai/OpenAiCompatibleClient.kt',
    # Cost
    'cost/Pricing.kt',
    # Graph
    'graph/GraphModel.kt',
    'graph/PsiStructure.kt',
    # ToolWindow
    'toolWindow/GeaiChatPanel.kt',
    'toolWindow/GeaiToolWindowFactory.kt',
    'toolWindow/GeaiWebPanel.kt',
    'toolWindow/SkillsDialog.kt',
    # GeaiToolset
    'tools/GeaiToolset.kt',
]


def is_in_string(line, pos):
    """Check if position pos in line is inside a string literal."""
    in_string = False
    escape = False
    quote_char = None
    for i, ch in enumerate(line):
        if escape:
            escape = False
            continue
        if ch == '\\':
            if in_string:
                escape = True
            continue
        if ch in ('"', "'") and not in_string:
            in_string = True
            quote_char = ch
        elif in_string and ch == quote_char:
            in_string = False
        if i == pos:
            return in_string
    return False


def remove_single_line_comment(line):
    """Remove // comment from a line, preserving strings."""
    # Find // not inside a string
    i = 0
    in_string = False
    escape_next = False
    quote_char = None
    triple_quote = False

    while i < len(line):
        ch = line[i]

        if escape_next:
            escape_next = False
            i += 1
            continue

        if in_string:
            if ch == '\\':
                escape_next = True
            elif triple_quote and ch == quote_char and i + 2 < len(line) and line[i+1] == quote_char and line[i+2] == quote_char:
                triple_quote = False
                in_string = False
                i += 3
                continue
            elif not triple_quote and ch == quote_char:
                in_string = False
            i += 1
            continue

        # Not in string
        if ch == '/' and i + 1 < len(line) and line[i+1] == '/':
            # Found // outside string - remove from here to end
            # But check it's not part of a URL like "https://"
            prefix = line[:i].rstrip()
            if prefix and not prefix.endswith(':'):
                # Trailing comment - remove it
                return prefix
            elif not prefix:
                # Standalone comment line
                return None  # Signal to remove entire line
            else:
                return line  # Keep as-is (URL pattern)
        elif ch == '"':
            in_string = True
            quote_char = '"'
            # Check for triple-quote
            if i + 2 < len(line) and line[i+1] == '"' and line[i+2] == '"':
                triple_quote = True
                i += 3
                continue
        elif ch == "'":
            in_string = True
            quote_char = "'"

        i += 1

    return line


def find_javadoc_blocks(lines):
    """Find all javadoc block ranges (start_line, end_line) in the file."""
    blocks = []
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if line.startswith('/**'):
            start = i
            # Single-line javadoc
            if '*/' in line and line.index('/**') < line.index('*/'):
                blocks.append((start, i))
                i += 1
                continue
            # Multi-line javadoc
            j = i + 1
            while j < len(lines):
                if '*/' in lines[j]:
                    blocks.append((start, j))
                    i = j + 1
                    break
                j += 1
            else:
                # Unclosed javadoc
                blocks.append((start, len(lines) - 1))
                i = len(lines)
            continue
        i += 1
    return blocks


def is_essential_javadoc(lines, start, end, filepath):
    """Determine if a javadoc block should be kept."""
    block_text = '\n'.join(lines[start:end+1])

    # Check if this file is in the essential API list
    is_essential_file = any(filepath.replace('\\', '/').endswith(p) for p in ESSENTIAL_API_PATTERNS)

    # For essential API files, keep class/interface/object-level javadocs
    if is_essential_file:
        # Look at what comes AFTER the javadoc
        next_line_idx = end + 1
        if next_line_idx < len(lines):
            next_line = lines[next_line_idx].strip()
            # Keep if it's on a class, interface, object, sealed class, enum, or public API method
            # But only if the javadoc is substantial (more than just repeating the name)
            doc_content = block_text.replace('/**', '').replace('*/', '').replace('*', '').strip()

            # Keep if doc has substantial content AND is on a public API element
            is_public_element = any(kw in next_line for kw in [
                'class ', 'interface ', 'object ', 'sealed ', 'enum ',
                'data class ', 'data object ', 'fun ', 'val ', 'var ',
            ])
            is_public = not next_line.startswith('private ') and not next_line.startswith('internal ')

            if is_public_element and is_public:
                # Keep if doc is non-trivial (more than 1 short line or contains keep patterns)
                doc_lower = doc_content.lower()
                has_keep_pattern = any(p.lower() in doc_lower for p in KEEP_PATTERNS)
                is_long_doc = len(doc_content) > 80 or '\n' in doc_content.strip()

                if has_keep_pattern or is_long_doc:
                    return True

    return False


def process_file(filepath):
    """Process a single file, removing comments."""
    with open(filepath, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    original_lines = list(lines)

    # Phase 1: Find javadoc blocks and mark which to remove
    javadoc_blocks = find_javadoc_blocks(lines)
    javadocs_to_remove = set()
    for start, end in javadoc_blocks:
        if not is_essential_javadoc(lines, start, end, filepath):
            for i in range(start, end + 1):
                javadocs_to_remove.add(i)

    # Phase 2: Process each line
    new_lines = []
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        # Skip lines that are part of javadoc to remove
        if i in javadocs_to_remove:
            i += 1
            continue

        # Handle single-line comments
        if '//' in stripped and not stripped.startswith('/**') and not stripped.startswith('*') and not stripped.startswith('/*'):
            result = remove_single_line_comment(line.rstrip())
            if result is None:
                # Entire line is a comment - skip it
                i += 1
                continue
            else:
                new_lines.append(result + '\n')
                i += 1
                continue

        new_lines.append(line)
        i += 1

    # Phase 3: Clean up excessive blank lines (more than 2 consecutive)
    final_lines = []
    blank_count = 0
    for line in new_lines:
        if line.strip() == '':
            blank_count += 1
            if blank_count <= 2:
                final_lines.append(line)
        else:
            blank_count = 0
            final_lines.append(line)

    # Write back
    with open(filepath, 'w', encoding='utf-8') as f:
        f.writelines(final_lines)

    removed = len(original_lines) - len(final_lines)
    return removed


def find_source_files(root):
    """Find all Kotlin and Java source files."""
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        # Skip build, .gradle, .idea directories
        dirnames[:] = [d for d in dirnames if d not in ('build', '.gradle', '.idea', '.git', 'node_modules')]
        for f in filenames:
            if f.endswith('.kt') or f.endswith('.java'):
                files.append(os.path.join(dirpath, f))
    return files


def main():
    root = os.path.dirname(os.path.abspath(__file__))
    src_dir = os.path.join(root, 'src')
    benchmark_dir = os.path.join(root, 'benchmark')

    files = []
    if os.path.exists(src_dir):
        files.extend(find_source_files(src_dir))
    if os.path.exists(benchmark_dir):
        files.extend(find_source_files(benchmark_dir))

    total_removed = 0
    files_changed = 0
    for filepath in sorted(files):
        removed = process_file(filepath)
        if removed > 0:
            rel = os.path.relpath(filepath, root)
            print(f"  {rel}: -{removed} lines")
            total_removed += removed
            files_changed += 1

    print(f"\nTotal: {files_changed} files changed, {total_removed} lines removed")


if __name__ == '__main__':
    main()
