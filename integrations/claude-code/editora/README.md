# Editora for Claude Code

A Claude Code plugin, in its own marketplace: it connects Claude Code to the running
Editora through `editora mcp` (Editora's MCP tools, resources and prompts) and adds two
skills:

- **review-in-editora**: review a change and leave each finding as a review comment on
  its lines in Editora (severity, category, a suggestion to apply), and work through
  the review comments the user left, resolving each with a note.
- **work-in-editora**: work with the files open in Editora without losing unsaved
  changes: read the editor's live text, edit open buffers undoably, save only when asked.

Both pre-approve Editora's read-only tools only; Claude Code still asks before a tool
that changes a buffer, a file or a review thread.

## Install

Turn on **Settings > MCP Server** in Editora. Then, from a checkout of this folder:

```bash
claude plugin marketplace add ./integrations/claude-code   # from the root of an Editora checkout
claude plugin install editora@editora
```

If `editora` is not on your `PATH` (macOS, an RPM or AppImage install, Windows), give
the launcher Settings > MCP Server's **Copy Connect Command** shows:

```bash
claude plugin install editora@editora --config editora_bin=/path/to/editora
```

or run `/plugin configure editora@editora` in Claude Code. On Windows use `editora-cli`.

With the plugin installed, there is no need for a separate `claude mcp add editora`.
If you added that before, remove it (`claude mcp remove editora`) so the tools are not
offered twice.

## What is in it

| Path | What |
| --- | --- |
| `../.claude-plugin/marketplace.json` | The marketplace, holding the one plugin |
| `.claude-plugin/plugin.json` | The plugin, and its `editora_bin` setting |
| `.mcp.json` | `editora mcp` as a stdio MCP server |
| `skills/*/SKILL.md` | The two skills |

The folder above this one is laid out as a marketplace root, ready to become a repository of its own.
