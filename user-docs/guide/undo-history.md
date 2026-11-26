---
title: Undo History
description: Jump back to any recent in-session editing checkpoint, and get unsaved edits back after a crash.
category: Editing
order: 6
---

Editora keeps an in-session **timeline of checkpoints** as you edit, one per
typing burst. It's finer-grained than save-based
[Local History](/docs/workspace#local-file-history), and session-only
(it's not persisted, and it's off in large-file mode and for documents over a
million characters).

![A Python file with the Undo History panel on the right, listing timestamped checkpoints, each with the line it touched](/screenshots/features/undo-history.png "The Undo History panel: one checkpoint per typing burst.")

Up to **50** checkpoints are kept, within a memory budget, whichever limit is
reached first: an ordinary file gets all 50, a big one keeps fewer. The budget
is shared by **all open files together** (64 million characters), and the oldest
checkpoints go first. Closing a tab releases its checkpoints straight away.

Ordinary undo (**Edit: Undo**, `C-/` in the Emacs keymap, `Ctrl+Z` in the
others, `Cmd+Z` on macOS) takes back a word or a line at a time, not a whole
burst of typing. An undo step ends after typing that ends in a space or a line
break, after deleting across a space, and after a pause of 400 milliseconds. A
paste, a snippet or a format is a step of its own.

Ordinary undo is bounded the same way as the checkpoints: a document keeps up
to 300 undo steps, and the undo steps of all open documents together hold no
more than 64 million characters of text. Over that, the document edited least recently gives up its
oldest steps first, and every document keeps at least its latest step. Closing
a tab releases its undo steps at once.

## The popup (recommended)

`undoHistory.jump` (`M-g v` in the Emacs keymap) opens the active buffer's checkpoints as a
filterable popup, each with a caret-line preview and capture time. Type to
filter, then pick one to jump back to that state. The jump is a single undoable
restore, so you can undo it (`edit.undo`).

## The tool window

The **Undo History** tool window (`M-g u`) shows the same timeline; double-click
or Enter on a checkpoint to jump back. A checkpoint's right-click menu (or the
Menu key, or Shift+F10) has **Restore This State**, which is also the command
**Undo History: Restore This State** (`undoHistory.restoreSelected`). Its side-stripe button is off by default
(the popup is the primary entry point); enable it in **Settings → Tool Windows**
or with `tool.undoHistory`.

## Files without undo

A file of 200,000 lines or more (or 16 MB or more), or one with a line of a million characters or more, opens in
[View Mode](/docs/editing#read-only-and-view-mode) and, once you enable editing, in
[large-file mode](/docs/troubleshooting#performance), which keeps no undo steps
at all. Typing there is not undoable. A **bulk edit** is treated differently,
because it changes text you are not looking at: Replace in Files, a line
transform, an external tool's output, an AI rewrite, an edit by the AI agent,
the MCP server or a plugin, a language-server edit, and applying a change from
a diff.

Before such an edit Editora saves the file's current text, unsaved changes
included, to [Local History](/docs/workspace#local-file-history) as a
revision labelled "Before ‹operation› (no undo)", and the status bar says where
the copy is. The edit waits a moment for the copy to reach the disk. If the
copy cannot be taken (Local History is off, the file is untitled, remote or
encrypted, or the write fails or does not finish in time), the edit is **not
applied** and the status bar says why.

## Crash recovery

Undo steps and checkpoints live in memory. To protect unsaved text against a
crash, Editora also keeps a **recovery copy** on disk of every tab that has
unsaved changes, untitled buffers included, in every window. The copies are in
the `recovery/` folder of the [config folder](/docs/configuration).

A copy is written within a second or two of a pause in typing, and at least
every ten seconds while you keep typing; very large documents are copied less
often. It is removed when you save the file, revert it, edit it back to its
saved text, or close the tab. A normal quit leaves none behind.
[Encrypted files](/docs/encrypted-files) get no recovery copy, so their text
never reaches the disk unencrypted.

### After an abnormal exit

If Editora did not close normally (a crash, a kill, a logout, a power cut), the
next launch opens **Recover Unsaved Edits**. It lists each kept buffer with its
name, location, and the time the copy was taken, and marks the ones that need
care:

- the file **changed on disk** since the edits were made,
- the file **no longer exists**, or
- the file is **remote**.

| Button | Effect |
| --- | --- |
| **Restore All** (Enter), **Restore Selected** | Open the kept text as unsaved tabs |
| **Discard Selected…**, **Discard All…** | Delete the copies, after a confirmation |
| **Decide Later** (Escape) | Change nothing; the list is offered again at the next launch |

**Restoring never writes to your files.** The text opens in a tab marked
unsaved, with the file's encoding and line endings, and nothing reaches the
disk until you save. A restored file that changed on disk in the meantime is
handled like any other file that changed under unsaved edits: Editora tells you
and asks before a save overwrites it. If the file is already open with unsaved
changes of its own, or is a remote file whose connection is not open, the
recovered text opens in a separate untitled buffer instead.

`file.recoverUnsavedEdits` (**File: Recover Unsaved Edits…**) reopens the list
at any time.

### Limits and the setting

- A buffer over **16 million characters** gets no recovery copy. The status bar
  says so once; save the file to keep your changes.
- Tabs that are not text editors (image, hex, PDF, diff, and merge views), a
  followed log, and a file that was only partly loaded are not covered.
- A recovery copy is plain text in your config folder, readable only by your
  user account where the platform supports that.

The setting is **Keep a recovery copy of unsaved edits** in **Settings →
Workspace**, on by default; `view.toggleCrashRecovery` (**View: Toggle Crash
Recovery**) switches it too. Turning it off removes the current copies. Copies
left by an earlier run are still offered.

Other things Editora offers to put back after an interruption (a save that did
not finish, a refactoring cut short) are listed in
[Troubleshooting](/docs/troubleshooting#after-a-crash-or-an-interrupted-save).

## Undo Last Removal

Editora asks the same way everywhere before something is lost. The question
names what goes, its confirming button carries the action's own name (Delete, Discard,
Replace, Overwrite, Reset) in red, and **Cancel** is the default button: Enter
keeps what you have, and only a click on the red button removes it. Where the
question offers a safe action beside the red one, such as Save beside Discard,
the safe action is the default instead. Every dialog draws Cancel to the left of
its actions, on Windows, macOS and Linux alike.

Some things are removed with one key and no question, because a question each
time would be tiresome: a watch; a breakpoint with a condition, a log message or
a hit count; a breakpoint group or filter; a method breakpoint or field
watchpoint with a condition; an exception breakpoint; a value pinned to a line;
a bookmark with a mnemonic; a key binding you removed or reset; a word of your
personal dictionary; a setting put back to its default; and a project's value
taken off a setting. The status bar says when one of these goes, and
**Edit: Undo Last Removal** (`edit.undoRemoval`, in the **Edit** menu) puts the
last one back as it was: in its place in its list, with its condition, its
paused state or its mnemonic. It has no key until you give it one.

- It keeps the twenty newest removals of a window, until the window closes.
- When something can no longer go back where it was (its file was closed, the
  line reads something else, the key runs another command now, a new breakpoint
  is on the line), it says so and changes nothing.
- A plain breakpoint or bookmark is not recorded: the key that removed it sets
  it again.
- A removal made in Settings is reported in the editor window's status bar and
  undone from the editor window.

## Related

This complements **word/line-level undo**, where one undo removes a word or line
rather than a whole contiguous burst. For longer-term recovery across sessions,
see [Local History](/docs/workspace#local-file-history), which records
saves; [crash recovery](#crash-recovery) is the only one of the three that
covers text you have not saved.
