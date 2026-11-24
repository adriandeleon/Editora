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
  - `LogTimes` — the time a line was written (epoch ms), typed times and ranges, and the line at a time.
- [`editor/LogView`](../../src/main/java/com/editora/editor/logviewer/LogView.java) — one buffer's filter and
  follow state, and the full text a filter hides.
- [`editor/LogHighlightOverlay`](../../src/main/java/com/editora/editor/logviewer/LogHighlightOverlay.java) —
  the gutter bar and row wash, painted on a canvas over the visible lines only.
- [`editor/LogStripe`](../../src/main/java/com/editora/editor/logviewer/LogStripe.java) and
  `logviewer/LogMarks` — the WARN/ERROR overview over the scrollbar, and the scan that finds its lines.
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

**Hiding is decided by a record's first line.** The hide pattern (`LogQuery.exclude`) hides a whole
record when the line that starts it matches, and any other matching line on its own. A record is
never hidden by a later line: that would mean holding every record until the next one starts, and a
followed log would not show its newest record. Hiding wins over showing, and a hidden line is never
held, so held lines carry their own indexes (they need not be contiguous).

**User patterns run through `LogMatcher`.** A pattern that can backtrack badly (a repeated group, a
back-reference, three or more quantifiers: `canBacktrack`) is matched through a `CharSequence` that
checks the clock and gives up on a line after 50 ms; three such lines and the matcher stops trying.
The filter runs on the FX thread for a small log and the overlay's match highlight always does, so
this is what keeps a bad pattern from freezing the window. Other patterns skip the check: it made a
filter about 10% slower. JDK 25 memoizes `(a+)+$`, so tests use `(.*a){20}$`.

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

**Remembered per file.** `WorkspaceState.logViews` (schema 37) keeps, per absolute path, a
`LogViewState`: View as Log (`true` on, `false` a sniffable file turned off, so `sniffLoaded` leaves
it alone; `null` the name and lines decide), the level floor and the `LogQuery`. Only what differs
from a fresh open is stored; an entry equal to a fresh open is removed. `LogViewerCoordinator`
writes it from `filtered()` and `viewAsLog()` and saves only when it changed; `restoreView` puts it
back once per buffer after the load (`FileWorkflowCoordinator.applyPreparedLoad`), so a reload keeps
what the user has now. Follow is not remembered: it starts a poll. `RenamedFileState` moves entries
with a rename and copies them on Save As.

**Memory.** A follow may add `LogView.FOLLOW_CAP` characters to what the buffer held when it
started; past that the oldest lines go and the buffer becomes unsaveable (`trimmed()`). A filter on
a log of `LogViewerCoordinator.ASYNC_FILTER_CHARS` or more is computed off the FX thread and
installed only if the text has done nothing but grow since (`LogView.epoch()`).

**The overview stripe scans the lines in view.** `LogStripe` runs `LogMarks.scan` over the area's text
250 ms after the last change (a follow appends in bursts), inline up to 256 KB and on one shared
daemon thread above that; a scan whose generation is stale is dropped, and one that finds the same
marks repaints nothing. It paints one mark per pixel row, the worst, so cost does not grow with the
number of marks. It shares the Markdown-lint stripe's slot (a log is never Markdown) and pushes the
TODO stripe left by its width. A split's copy takes the original's marks instead of scanning.

**Times are epoch milliseconds, completed by a `LogTimes.Context`.** A timestamp with no zone is in
the context's zone, a date with no year in its year; `LogFilter.compile` uses `Context.local()`. The
range lives in `LogQuery.from/to` (absolute, so a typed time of day is resolved against the caret
line's date when it is typed) and compiles to `LogFilter.TimeRange` in `Patterns`. `LogRecordFilter`
parses a line's time only while a range is set, carries it to the lines below like the level, and
does not keep a line with no time known. The control bar has no field for the range but keeps it in
`query()`, so typing a pattern does not drop it. `LogTimes.firstAtOrAfter` reads from the top: logs
are only nearly ordered.

**A stream is a tab without a file.** `LogViewerCoordinator.startStream` builds the command from a
`LogStreamSource` (the typed text, shell words except for SSH's `host:/path`, whose path is quoted for
the remote shell), opens an untitled read-only buffer forced into log mode and feeds it through
`appendLogText`, as a follow does. `LogStream` reads stdout and stderr line by line on two daemon
threads (a line is never split between them) and drains to the FX thread in coalesced batches.
Follow off pauses delivery, not the command; the held text is capped at `LogView.FOLLOW_CAP`. Closing
the tab (`onBufferClosed`) and `shutdown` stop the command. COMMAND and JOURNAL run on
`ExecTargets.forPath(projectRoot)`; CONTAINER, KUBERNETES and SSH_FILE are clients on this machine.

**A `.gz` file opens decompressed and read-only.** `FileWorkflowCoordinator.prepareGzip` reads it
through `io/GzipText` (at most `EditorBuffer.LARGE_FILE_BYTES`; a log keeps its end, from a line
start, anything else its start) and marks the load `truncated`, which makes the tab read-only and
keeps Save from writing text over the compressed bytes. `LogFileNames.isLog` looks through `.gz`
(not `.tar.gz`), so `app.log.2.gz` is a log by name; `startFollow` refuses a compressed file. A file
that is not gzip after all falls back to the plain read.

## Not done

- No time ruler beside the overview stripe, and no time slider: the range is typed or set from the caret.
- The time range is not remembered across sessions with the rest of the filter.
- A stream that ended cannot be restarted in place (it would repeat its last 1000 lines); open it again.
- Streams are not reopened with the session.
- Archives (`.zip`, `.tar`, `.tar.gz`) have no entry picker; only single-file gzip opens.
- The level filter, pattern filter, hide filter and clear filter commands have no default keys.
- The hide pattern's matches are not highlighted (only the show pattern's are).
