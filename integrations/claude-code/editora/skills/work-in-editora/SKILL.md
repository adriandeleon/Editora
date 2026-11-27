---
name: work-in-editora
description: Work with the files the user has open in Editora without losing their unsaved changes - read the editor's live text, edit open buffers undoably, save when asked, and open files or run editor commands. Use when the user refers to "the open file", "what I have selected", unsaved changes, the editor, or asks you to change a file they are editing in Editora.
allowed-tools: mcp__plugin_editora_editora__list_open_files mcp__plugin_editora_editora__list_tabs mcp__plugin_editora_editora__read_buffer mcp__plugin_editora_editora__get_selection mcp__plugin_editora_editora__get_diagnostics mcp__plugin_editora_editora__document_symbols mcp__plugin_editora_editora__find_in_files mcp__plugin_editora_editora__git_status mcp__plugin_editora_editora__todo_scan mcp__plugin_editora_editora__list_commands
---

# Working in Editora

Editora is running with the user's files open. Some may have **unsaved changes**
that are not on disk yet. Writing such a file on disk would lose them, or be
overwritten when the user saves.

- To see what the user sees, use `read_buffer` (the live text, unsaved changes
  included) rather than reading the file from disk. `get_selection` gives the caret
  and the selected text; `list_open_files` says which files are open and which have
  unsaved changes (`dirty`).
- To change a file that is open in Editora, use `edit_buffer` with the exact
  `old_text` and its `new_text`, not a disk write. The edit goes through the
  editor's undo history, so the user can undo it, and nothing is written until
  `save_buffer` or the user saves. Save only when the user asks.
- Files that are not open can be edited on disk as usual.
- `get_diagnostics` gives the language server's problems for an open file, and
  `document_symbols` its outline: use them to check an edit.
- `open_file` shows a file of the project to the user at a line, for example to
  point at what you changed.
- `execute_command` runs any Editora command (`list_commands` lists them). Use it
  only for what the user asked for.
- If a tool says Editora is not running, ask the user to start Editora and turn
  on Settings > MCP Server.
