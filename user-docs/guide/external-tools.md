---
title: External tools
description: Define your own CLI commands and run them on the current file or buffer, with macros, stdin piping, and flexible output.
category: Customization
order: 6
---

Define your own command-line tools in **Settings → External Tools** and run them
on the current file or buffer, IntelliJ-style. It's available by default (the
list starts empty until you add one) and off in Simple UI mode.

The page lists your tools on the left and the selected tool's fields on the
right: **Name**, **Command** (with **Browse…**), **Arguments**, **Working
directory** (with **Browse…**; blank means the file's directory), **Standard
input**, **Output**, and an **Enabled** switch. Changes apply as you make them:
a field when you press Enter, leave it, or close Settings, and the lists and
the switch at once. There is no Save button. **Remove** asks "Delete “name”?"
first, with Cancel as the default, and drops the tool's key binding. Renaming a
tool keeps its key binding. A name that would give the same command as another
tool's is refused.

![Settings on the External Tools page with one tool, Prettier: Command npx, Arguments prettier --write $FilePath$, a blank working directory, Standard input None, Output Show in console and Enabled on, with the list of macros below](/screenshots/docs/settings-external-tools.png "A tool that runs Prettier on the current file.")

## Macros

A tool's command and arguments expand `$Name$` macros from the active file:

| Macro | Value |
| --- | --- |
| `$FilePath$` | Full path of the file |
| `$FileDir$` | The file's directory |
| `$FileName$` | The file name |
| `$FileNameWithoutExtension$` | The file name without its extension |
| `$SelectedText$` | The current selection |
| `$LineNumber$` / `$ColumnNumber$` | Caret position |
| `$ProjectFileDir$` | The project root |

## Input and output

A tool can pipe the **selection** or the **whole buffer** to the command's
stdin. It then chooses what to do with the output:

- show it in a read-only **console** tool window,
- **replace the selection**,
- **replace the whole buffer** (undoable), or
- **insert at the caret**.

That covers both "run a command and read the output" and text transforms with
filters like `jq`, `sort`, or `sed`.

**Replace the selection** replaces the text that was selected when the tool
started, wherever the caret or selection has moved to by the time it finishes.
In a [large file that has no undo](/docs/undo-history#files-without-undo), the
file's text is saved to Local History before a tool's output replaces any of
it.

## Running

Every tool you define becomes its own palette command (bindable to a key in
[Settings → Keymaps](/docs/keymaps)). There's also **External Tools: Run…**
(`externalTool.run`, a picker) and **Rerun Last** (`externalTool.rerunLast`).
Tools run off the UI thread, a tool still running after 2 minutes is stopped,
and console output appears in the
External Tools tool window (`tool.externalTools`). A tool that writes to the
console opens it at launch, with a spinner that turns until the tool exits; a
tool that writes into the editor spins the External Tools header instead.
External tools run locally only: on a file in a remote (SFTP) folder they
refuse and say so in the status line.
