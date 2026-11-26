---
title: Diff & merge
description: File, folder, patch, and Git reviews; editable results; hunk actions; and three-way merge resolution.
category: Version control
order: 2
---

Editora has a built-in diff viewer and a merge-conflict resolver. The Git-backed
comparisons need [Git](/docs/git) enabled; comparing two arbitrary files does
not.

## Diff viewer

Compare files in a dedicated tab, **side by side** or **unified**, with per-line
backgrounds, intra-line word emphasis, change ribbons between related hunks,
and a right-edge overview of the whole document.

![A side-by-side diff of Themes.java, HEAD on the left and the working tree on the right, with one changed line highlighted, word-level emphasis inside it, and the unchanged lines folded](/screenshots/features/diff-merge.png "The diff viewer, side by side, with unchanged lines folded away.")

| Compare | Command | Emacs key |
| --- | --- | --- |
| Against HEAD | `diff.vsHead` | `C-x v =` |
| Against a commit | `diff.vsCommit` | (palette) |
| Against a branch | `diff.vsBranch` | (palette) |
| Against a tag | `diff.vsTag` | (palette) |
| With another file | `diff.compareWith` | (palette) |
| Clipboard or empty text with the active file | `diff.compareClipboard` / `diff.compareBlank` | (palette) |
| Two folders recursively | `diff.compareDirectories` | (palette) |
| All staged changes | `diff.reviewStaged` | (palette / Commit window) |
| All unstaged and untracked changes | `diff.reviewUnstaged` | (palette / Commit window) |
| Open a `.patch`/`.diff` in the viewer | `diff.openPatchFile` | (palette) |

The other keymaps bind none of these by default; the
[keybindings reference](/keybindings) lists every keymap.

When a file is open, its live (possibly unsaved) text is used as the working
side. A unified `.patch`/`.diff` file opens as one navigable multi-file review
with per-file status and statistics, and each hunk carries the line numbers its
header states, so a hunk at line 500 is numbered from 500 and the gap between
two hunks is visible. Git reviews provide the same navigation across every
staged or working-tree file. Open diffs **refresh live** when the
underlying files change on disk or after a Git mutation such as a commit, stage,
or checkout, while preserving the selected change, scroll positions, focused
side, and file-list divider.

Comparison controls can ignore whitespace or case, use smart line alignment,
collapse unchanged context, wrap long lines, switch layout, swap sides, and
export a patch. Binary and very large inputs degrade to safe metadata or bounded
line comparisons instead of being decoded or rendered as ordinary text, and
those degraded comparisons stay read-only. In a block of rewritten lines the
removed lines are listed first and their replacements after them, with or
without smart alignment.

The controls are commands too, acting on the diff the keyboard is in:

| Action | Command |
| --- | --- |
| Compare whitespace exactly, trimmed, or not at all | `diff.cycleWhitespace` |
| Fold or show the unchanged lines | `diff.toggleCollapseContext` |
| Wrap long lines | `diff.toggleWrapLines` |
| Mark the changed words inside changed lines | `diff.toggleWordHighlight` |
| Next / previous file of a patch or folder review | `diff.nextFile` / `diff.previousFile` |
| Open the file at the changed line at the caret | `diff.openLineInEditor` |
| Export the comparison as a patch | `diff.exportPatch` |

**Export patch…** saves the current comparison as a unified `.patch` file. The
patch keeps each side's own line endings, so one made from a file with Windows
(CRLF) endings applies to that file, and it preserves a missing final newline on
either changed side. A file in a single-byte encoding such as Latin-1 or
Windows-1252 gets its patch in that encoding; otherwise the patch is UTF-8. The
patch is computed in the background, and two texts that have almost nothing in
common are written as one hunk. Export is unavailable when a side is binary or
too large to load.

A `.patch`/`.diff` open for review can be applied from the viewer:
**Diff: Apply Reviewed Patch to Working Tree** (`diff.applyPatchToWorkingTree`)
or **Diff: Apply Reviewed Patch to Index** (`diff.applyPatchToIndex`).

To make a patch from staged changes, unstaged changes, or a commit, or to apply
one to the working tree or the index, see [Git patches](/docs/git#patches).

## Folder comparisons

Right-click a folder in the Project tree to compare it recursively with HEAD, a
branch, tag, or chosen revision. `git.compareBranch` reviews every file that
differs between a branch and the current one, and the Git Log reviews a commit
or the difference between two selected commits (see
[Git Log](/docs/git#git-log)). You can also compare any two directories with
`editora --diff-ui DIR DIR`. The scan runs in the background, respects Git
ignore rules on both sides, excludes `.git`, and lists only changed, left-only,
or right-only files in a lazy review.

`editora --diff-ui LEFT RIGHT` starts an isolated comparison workspace without
restoring a normal Editora session. Use the Editora-mark button in its toolbar
(or **Diff: Leave Diff-Only Window**, `diff.exitDiffMode`) to restore the full
interface without closing or reloading the comparison. When Editora is already
running, the comparison opens in its own window of that editor
(see [One editor, not two](/docs/cli#one-editor-not-two)).

## Applying changes

On the editable side, gutter arrows let you **apply changes hunk by hunk**: a
single chevron on each changed row, and a double chevron at the start of a whole
hunk. The toolbar's **Apply all** replaces the editable file with the other side
wholesale (after a confirmation). Git comparisons also expose Stage, Unstage,
and Revert actions for the selected hunk or line, plus Copy Hunk and Open Changed
Line.

Applies route through an undoable buffer, so the toolbar's **Undo** and **Save**
act on them and nothing is committed to disk until you save. Which side is
editable depends on the comparison (the working file for vs-HEAD and vs-commit,
your local file for compare-with). Line endings and final-newline state are
preserved, and stale local content is rejected rather than overwritten.

For a local comparison, **Edit Local Result** opens a syntax-highlighted Result
draft below the diff. Editing the draft re-diffs after a short pause; applying
it changes the real editor as one guarded, undoable operation. It is never saved
implicitly. Result drafts take part in tab and window close protection, so an
edited result cannot disappear without the same Save / Discard / Cancel choice
as an ordinary buffer. Stale Git/index hunks are rejected rather than applied
to a newer working tree.

The same actions work from the keyboard:

| Action | Command |
| --- | --- |
| Apply the change at the caret, or just its line, to the local file | `diff.applyChange` / `diff.applyLine` |
| Copy the other side's final newline, or its absence | `diff.applyFinalNewline` |
| Take back the last applied change | `diff.undoApplied` |
| Save the file the changes were applied to | `diff.saveResult` |
| Stage, unstage or revert only the line at the caret | `diff.stageLine` / `diff.unstageLine` / `diff.revertLine` |
| Apply or reset the edited Result | `diff.applyResult` / `diff.resetResult` |

## Merge conflicts

When Git has unmerged index stages, `merge.resolve` opens a three-way
resolver using the common ancestor, ours, and theirs. Independent and identical
edits combine automatically; divergent regions offer Base, Ours, and Theirs
choices above an editable Result. Applying is undoable and protected against a
buffer that changed while the resolver was open. Once you edit the Result by
hand, choosing another resolution cannot silently erase those edits.

The resolver groups changes the way Git does: edits on neighbouring lines are
one conflict, so every region Git reported as a conflict is shown and none is
combined unseen. Each conflict can take **Accept Ours**, **Accept Theirs**,
**Accept Both**, or **Accept Base**, and the header counts how many are
resolved. A conflict you leave unresolved is written back exactly as it was.

If the file differs from Git's merge of the three versions, for example
because you already resolved some conflicts by hand, the resolver works from
the conflict markers the file still has, and what you resolved stays resolved.
When it cannot tell which you want, most often because the file has no markers
left and looks resolved already, it asks: **Start from Git's Versions** shows
every conflict again, and applying that result replaces what the file holds;
**Use the File's Markers** is offered when the file still has markers; Cancel
leaves the file alone.

When the file is one of the repository's conflicted files and the result has no
conflict left, applying it also saves the file and stages it, which removes it
from the Commit window's Conflicts group. A partial resolution is only written
into the buffer. The Commit window is where conflicted files are listed, with
keep-one-side and Mark Resolved actions and the Continue / Skip / Abort
controls for the operation; see
[Operations in progress and conflicts](/docs/git#operations-in-progress-and-conflicts).
