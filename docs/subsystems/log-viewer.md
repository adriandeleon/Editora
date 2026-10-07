# Log viewer

Level tints, tail-follow and filtering for log files. Back to [the docs index](../README.md).

## The pieces

- [`logviewer/`](../../src/main/java/com/editora/logviewer/) — pure model, no JavaFX except the
  follow service's `Platform.runLater`:
  - `LogLevel` — six levels and the one vocabulary of level words. `LogPatterns` builds its regexes
    from it, and a test keeps `log.tmLanguage.json` to the same words.
  - `LogPatterns` — the level of a line (`levelOf`), and whether a sample looks like a log.
  - `LogFileNames` — which names are logs (`app.log.1`, `access_log`, `syslog`), and which are worth
    sniffing (`server.out`, extensionless).
  - `LogRecordFilter` / `LogFilter` — the filter. Stateful, fed one complete line at a time.
  - `LogTail` / `LogTailService` — reading what a file gained, and the poll that does it.
  - `LogNavigation` — next/previous line at a level.
- [`editor/LogView`](../../src/main/java/com/editora/editor/LogView.java) — one buffer's filter and
  follow state, and the full text a filter hides.
- [`editor/LogHighlightOverlay`](../../src/main/java/com/editora/editor/LogHighlightOverlay.java) —
  the gutter bar and row wash, painted on a canvas over the visible lines only.
- [`ui/LogControlBar`](../../src/main/java/com/editora/ui/LogControlBar.java) and
  [`ui/LogViewerCoordinator`](../../src/main/java/com/editora/ui/LogViewerCoordinator.java) — the bar
  docked above a log's text, and the feature's commands and wiring.

## Rules that are easy to break

**A filter replaces the editor text.** While one is on, the area holds a subset and is not the
document; `EditorBuffer.getContent()` returns `LogView.fullText()`. Everything that means "the file"
must go through `getContent()`. Because the visible text is a subset, nothing about a line may be
derived from its neighbours there: `LogView` records each visible line's number and level in the
full text, and the gutter (`FoldManager.setLineNumbers`) and the overlay (`LevelSource`) read those.

**A record is the unit, not a line.** A record is a line with a level plus the level-less lines
after it. The level floor and the pattern both keep or drop whole records, so a stack trace stays
with the line that explains it. `LogRecordFilter` holds a record's unmatched lines until a later
line matches or the next record starts.

**Only complete lines are judged.** A followed log arrives in chunks that can end mid-line. The
visible text is always whole lines, each ending in `\n`; the unfinished last line is shown
provisionally and replaced when its newline arrives. Appending kept lines without a terminator is
what once glued `ERROR b` and `ERROR c` into `ERROR bERROR c`.

**Appends are not edits.** `LogView` rewrites the area with `adjusting()` set. Dirty tracking skips
those changes, and the undo history does not record them (`CompletionUndoFactory.forDocument`'s
`untracked` argument). Appends go through the document (`area.getContent().replace`), not
`appendText`, which moves the caret and drops the selection. A change that moves earlier text (a
trim, a filter swap) forgets the undo history, since its positions no longer hold.

**The follow offset is "what reached the buffer".** `LogTailService.Handle.offset()` advances when
a chunk is delivered on the FX thread, not when it is read. `LogViewerCoordinator` resumes from it,
or from the load offset, as long as the buffer's disk snapshot is still the size it was when the
offset was taken; a save or reload moves the snapshot and the follow restarts from the new one. The
follow also advances the disk snapshot as it reads, and `checkExternalChanges` skips a buffer that
is following, so a log that is being followed never raises "File Changed on Disk".

**Rotation.** A file is rotated when it shrinks or when its file key changes
(`BasicFileAttributes.fileKey`, null on Windows). A missing file is not an error: the poll waits
and reads the new file from its first byte. A file truncated and rewritten in place to beyond the
old offset between two polls cannot be told from growth; `tail -f` has the same limit. A rotation
replaces the whole buffer, so it is not applied to a buffer with unsaved edits: the follow stops
with a status message and the disk snapshot stays that of the old file, so saving asks first.

**Memory.** A follow may add `LogView.FOLLOW_CAP` characters to what the buffer held when it
started; past that the oldest lines go and the buffer becomes unsaveable (`trimmed()`). A filter on
a log of `LogViewerCoordinator.ASYNC_FILTER_CHARS` or more is computed off the FX thread and
installed only if the text has done nothing but grow since (`LogView.epoch()`).

## Not done

- No default key bindings for the `log.*` commands; `log.focusFilter` exists to bind.
- No match highlight inside filtered lines, no exclude filter, no case toggle.
- "View as Log" and the active filter are not remembered across sessions.
- A pattern that backtracks catastrophically still runs unbounded (on the worker for a large log,
  on the FX thread for a small one).
