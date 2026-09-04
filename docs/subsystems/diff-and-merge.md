# Diff and merge

Editora's diff pipeline is split into a toolkit-free model, background services, and JavaFX views. Keep
those boundaries intact: content acquisition and diff/highlight computation must not block the FX thread,
while all `CodeArea` and scene-graph mutations belong on it.

## Text fidelity

`DiffText` is the loss-aware boundary type. It separates a document into lines while retaining its dominant
line separator and final-newline state. Apply operations compose through the editable side's `DiffText`, so
an accepted hunk does not silently normalize CRLF or add/remove the final newline. `DiffModel` carries both
EOF states and the viewer presents an explicit final-newline action when they differ. `PatchWriter` and
`PatchParser` preserve standard `\ No newline at end of file` markers.

Git blobs and closed files are decoded through the editor's charset rules (BOM, then EditorConfig, then
UTF-8). `BinaryDiff` sniffs bytes before decoding and renders stable type/size/hash metadata rather than
mojibake. Equal binary hashes therefore compare as equal; different binaries remain inspectable without
pretending they are text.

## Computation and refresh

`DiffService` owns the serial daemon worker. Up to 60,000 lines it uses the full Myers line diff and optional
intra-line spans. Larger inputs use a linear common-prefix/suffix comparison without word spans, and inputs
beyond the rendered-row ceiling become a bounded metadata comparison. The viewer labels degraded results.

Comparison rules build one key per source line and never rewrite the rendered or applied text. Whitespace
normalization and case folding therefore affect matching only. Replacement blocks use a bounded dynamic-
programming alignment over token similarity so an inserted line does not shift every related line below it;
blocks above 200 lines or 10,000 candidate pairs fall back to positional alignment.

TextMate highlighting has its own serial daemon and paints plain diff rows immediately. A generation check
rejects styles for superseded content. `DiffCoordinator` independently generations side-fetch and diff
requests, preventing an older refresh from overwriting a newer one. Rebuilds retain the selected change,
viewport offsets, focused side, and divider position.

## Review views

`DiffViewerPane` supports side-by-side and unified layouts. Both carry intra-line emphasis. Long equal runs
collapse to three context lines around changes and can be expanded globally from the toolbar. The same bar
cycles exact/trim/all-whitespace matching, toggles word emphasis and wrapping, and remembers the session's
last choices. Navigation operates in source-row coordinates so collapsing does not change apply semantics.
The Rules menu adds case-insensitive matching and a switch between smart and positional changed-line
alignment. Both are also available as `diff.toggleIgnoreCase` and `diff.toggleSmartAlignment` commands.

The VCS menu and palette can compare the active local file against another file, clipboard text, or an empty
document. Full diff panes can swap sides after an off-thread recomputation. The pane swaps headers, patch
labels, view state, Git-action direction, and its editable-side marker as one operation; the coordinator keeps
the underlying local target fixed and applies the same orientation to later refreshes and option changes.
Swapping is disabled while recomputation is pending or an editable Result draft is dirty.

Side-by-side views reserve a narrow center track for curved change ribbons. Each ribbon joins the visible
bounds of one contiguous change block across the two editors, including unequal wrapped-line heights, and
is redrawn from visible paragraphs as either side scrolls. Added, removed, and modified blocks use the same
semantic colors as the line backgrounds. The active navigation block is emphasized, and a slim right-edge
overview track marks every change across the document. Both layers are decorative, mouse-transparent
complements to the existing signs, labels, and keyboard navigation.

`PatchReviewPane` is the shared multi-file review surface. It owns the file list, per-file status and stats,
file navigation, and one displayed-at-a-time `DiffViewerPane` per entry. It accepts parsed sections from
multi-file `.patch`/`.diff` buffers and repository snapshots from the Git coordinator. The Commit window and
the `diff.reviewStaged` / `diff.reviewUnstaged` commands open index-vs-HEAD or working-vs-index review sets;
untracked files compare against an empty index side, and rename/copy entries fetch their original path on the
left. Active-diff commands route through the currently selected file.

`DirectoryReviewPane` is the recursive folder-comparison surface. `DirectoryDiff` walks without following
symbolic links, bounds each scan to 20,000 files, compares candidates with `Files.mismatch`, counts identical
files, and returns only modified and one-sided paths. The scan runs on the file-read executor. Selecting an
entry loads its two sides and builds a normal `DiffViewerPane` on demand; an access-ordered cache retains at
most 32 visited panes. `diff.compareDirectories` opens the two-folder picker.

Comparisons backed by a local editable file expose an optional Result editor below the rendered diff. It is
a separate exact-text draft rather than one of the aligned display areas, whose filler and collapsed rows are
not valid source text. Edits are idle-debounced and recomputed through `DiffService`; generation checks reject
stale computations. Apply Result first validates the original local baseline, then replaces the editor buffer
once through the normal undoable path without saving. A dirty draft blocks competing hunk mutations and is
never discarded by closing its toolbar toggle.

## Standalone viewer

`editora --diff-ui LEFT RIGHT` starts one isolated, session-free window and opens either a normal
`DiffViewerPane` for two files or `DirectoryReviewPane` for two directories. The app toolbar, menu, status bar,
tab header, breadcrumb, and tool stripes are suppressed before the stage is shown; file reads, directory scans,
and diff computation stay on bounded daemon executors. A mixed file/directory pair is rejected. The full-UI
button in each loaded diff removes only this transient chrome override: the live comparison remains open while
the normal Editora UI returns. Standalone launches are not forwarded to an existing process and never restore
or overwrite the normal workspace session.

## Applying and Git mutations

Local apply actions reconstruct the full editable document, check that its live text still matches the
displayed baseline, and then use the normal undoable `EditorBuffer` replacement path. Apply-all confirms;
Undo and Save enable only after an accepted operation. Line apply is deliberately secondary to hunk apply.

Git-panel diffs add Stage/Unstage/Revert for the current hunk and line. The view derives the desired full
index or worktree text, `PatchWriter` produces a scoped patch, and `GitService.applyPatch` runs `git apply
--check` immediately before `git apply` on the service executor. A stale index fails without mutation. Copy
hunk and open-changed-line are available from the context menu and command palette.

## Three-way merge

`merge.resolve` first asks Git for the conflicted path's `:1`, `:2`, and `:3` index blobs: the common
ancestor, ours, and theirs. `GitService.BlobResult` distinguishes a valid empty blob from a missing stage.
Blob reads stay on the Git executor, charset decoding follows the editor's BOM/EditorConfig rules, and the
pure `ThreeWayMerge` computation runs away from the FX thread.

`ThreeWayMerge` diffs both sides against the ancestor. It automatically composes disjoint changes and
overlapping changes that produce identical text; only divergent overlapping regions become conflicts. Each
conflict retains an explicit base-presence bit, because two competing insertions have a real but empty
ancestor region. If all three Git stages are not available, `ConflictParser` remains the fallback for files
that already contain standard merge/diff3 markers.

`MergeViewerPane` shows Base/Ours/Theirs for each conflict and a lower editable Result. Acceptance actions
recompute the Result, while the user may edit it directly before applying. The apply path restores the
source document's line separator, preserves the edited final-newline state, uses the normal undoable
whole-document replacement, and refuses to overwrite a buffer that changed after the resolver opened.

## Accessibility

Add/remove signs accompany color backgrounds, diff areas and controls expose accessible text, and gutter
apply slots are keyboard-focusable buttons supporting Enter and Space. New controls must retain a textual or
shape cue; red/green color alone is not an acceptable state indicator.
