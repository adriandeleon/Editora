# Diff viewer guide

Editora compares text in a dedicated tab, preserving the source text's line endings and final-newline state.
The full implementation guide is [Diff and merge](subsystems/diff-and-merge.md); this page is a concise
developer and manual-test reference.

## Open a comparison

- **Compare with HEAD** (`diff.vsHead`) compares the active file with its repository version.
- **Compare With…**, **Compare Clipboard with Active File**, and **Compare Empty Text with Active File** work
  for any saved local file.
- Git changes, staged and unstaged review, file history, `.patch`/`.diff` files, and directory comparisons
  reuse the same viewer.
- `editora --diff-ui LEFT RIGHT` launches an isolated, focused viewer for two files or two directories. The
  Editora-mark button restores the normal application chrome without closing the comparison.

## Review controls

The default layout is side-by-side. The layout button switches to unified view; next/previous change controls
move through changed blocks. Long equal regions can collapse to context rows, wrapping can be toggled, and
the side-by-side layout draws change ribbons plus an overview track.

The comparison controls deliberately affect matching, not source content:

- Whitespace cycles through exact, trim, and ignore-all matching.
- **Words** enables intra-line changed-token emphasis.
- **Rules** contains **Ignore case** and **Smart line alignment**. Smart alignment uses a bounded token-
  similarity pass within replacement blocks so an inserted line does not force unrelated positional pairs.
  Turn it off to use positional pairing. Very large blocks automatically use positional pairing.

Even when a rule treats two lines as equal, the left and right panes continue to show their own original text.
Rules are also available through `diff.toggleIgnoreCase` and `diff.toggleSmartAlignment` in the command
palette.

## Editing and applying

For comparisons with a local editable side, apply controls reconstruct the actual source document rather than
the aligned display rows. Line, hunk, whole-file, and final-newline changes all use the normal undoable editor
path and refuse stale content. The optional Result editor is a separate editable draft: it re-diffs after a
short pause, applies once as an undoable edit, and prevents side swapping while dirty.

**Swap sides** flips labels, displayed content, connector geometry, and patch direction while retaining the
same local target for edits and refreshes. Export Patch always uses the original compared text.

Git-backed comparisons additionally offer Stage, Unstage, Revert, Copy Hunk, and Open Changed Line actions.

## Fidelity and limits

`DiffText` keeps the dominant line separator and final-newline bit, so an apply does not silently turn CRLF
into LF or append a newline. Binary inputs show safe metadata instead of decoded bytes. Full diffs are bounded
at 60,000 lines; larger content uses a line-only comparison, and exceptionally large content uses a metadata-
only comparison.

## Testing

Run the automated suite with:

```sh
mvn verify
```

For manual checks, use the curated fixtures in
[`../tests/editora/README.md`](../tests/editora/README.md). In particular,
`pairs/08-rules-alignment` demonstrates both smart alignment and case-insensitive matching.
