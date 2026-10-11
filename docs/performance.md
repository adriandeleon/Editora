# Performance

Performance is a first-class constraint in Editora, not an afterthought. The editor must stay
responsive on large files, and the UI thread is sacred. This page is the contract every change
on a hot path must honor.

**Assess and report the cost of every change.** For any implementation or fix, evaluate its
effect on the hot paths (allocation per keystroke/scroll, added FX-thread work, extra
layout/CSS passes, memory) and say so in the PR — even if it's "negligible". If a change risks
a regression, measure it (e.g. temporary `System.nanoTime` instrumentation) rather than guess.

## The hot paths

Treat these as sacred — they run on every keystroke or scroll pulse:

- typing / editing
- scrolling
- syntax highlighting
- the document overlays (whitespace, minimap, the 80-column ruler, spell-check, search,
  TODO, lint, diagnostics)
- the line-number gutter

## The rules

### 1. Never block the JavaFX Application Thread

Tokenize/parse/search **off-thread**, then apply results back on the FX thread under a
**generation guard** so a stale result is dropped. The canonical shape — used by
`GitService`, `SearchService`, `MarkdownLintService`, the `highlightExecutor` in
`EditorBuffer`, and every other service:

```java
private final ExecutorService exec = Executors.newSingleThreadExecutor(daemon("my-feature"));
private final AtomicLong gen = new AtomicLong();

void request(String input, Consumer<Result> onResult) {
    long mine = gen.incrementAndGet();
    exec.submit(() -> {
        Result r = computeOffThread(input);          // heavy work, off the FX thread
        if (mine == gen.get()) {                       // superseded? drop it
            Platform.runLater(() -> {
                if (mine == gen.get()) onResult.accept(r);
            });
        }
    });
}
```

Later additions with the same shape: the find bar's large-document search (`LatestOnly`), the status
bar's byte count, the Run-glyph scan (`RunScan`, at a load as well as at a settled edit), external-change and
recent-file checks, directory listings for Find File and the breadcrumb (`DirectoryListing`), the File
Information panel's word count and disk facts, and the spell checker's code-line scan of a prose buffer.

Console output is a special case of "don't flood FX": `process/OutputPump` delivers each drain inside an
`OutputBatch`, `ui/ConsoleAppender` applies a drain as one append, one style application and one trim, and
drains start at least 16 ms apart. Text that is kept rather than shown is bounded the same way: a test's
captured output, failure message and stack trace keep their head and tail around a note of the cut
(`test/OutputCap`, 200,000 chars), and the agent transcript holds at most 200,000 chars per entry and
1,000,000 in all, oldest entries first (`AgentPanel`). Pipe and socket writes to language servers, debug adapters and the ACP
agent all go through `AsyncPipeWriter`, never the FX thread.

### 2. Debounce and coalesce

Re-highlighting is debounced; overlay/ruler/minimap redraws coalesce to **one per pulse** with
a `pending` flag + `Platform.runLater`. Don't add per-keystroke or per-scroll-pulse work that
isn't coalesced. The coalescing shape:

```java
private boolean redrawPending;
private void scheduleRedraw() {
    if (!active || redrawPending) return;
    redrawPending = true;
    Platform.runLater(() -> { redrawPending = false; redraw(); });
}
```

"One per pulse" here means one per event-loop turn: the `runLater` runs before the next input event, which
is normally but not always the next pulse. The toolbar's edit state (Save/Undo/Redo/Cut/Copy) follows the same
shape: a typed character fires an edit and a selection change, and `MainController.scheduleEditStateRefresh`
turns both into one refresh per turn. That refresh reads the buffer's dirty *property*, not `isDirty()`: the
exact answer can cost an O(document) compare, and the property's own listener re-runs the refresh when it
changes. `EditorBuffer.dirtyAfterEdit` decides most edits by length alone; at the saved length it compares the
text at once for a clean buffer and for a *length-changing* edit that lands there (a deletion or an undo of an
insert — the way a user gets back to the saved text, and the dip to clean is what the tab marker and the Git
gutter's reset listen for), while a *same-length* edit of a dirty buffer (move line, overwrite, transpose)
carries the flag and is compared at the 150 ms settle or when `isDirty()` is asked, so a run of them copies
nothing. Nobody on the per-key path may ask `isDirty()`. A dirty transition (clean → dirty on the first edit)
still refreshes at once through the listener; it is the per-key path that is coalesced. A true once-per-frame scheduler was prototyped and not adopted
(it raised render wait in headless scroll runs); measure on a real GPU pipeline before trying again. For a
leading-edge-plus-max-wait throttle (rather than a trailing debounce) use `ThrottlePolicy` / `FxThrottle`.

For text-driven work inside `EditorBuffer`, register a milestone with its shared
`SettledEditDispatcher` rather than adding another RichTextFX `successionEnds` subscription. One document
subscription resets one JavaFX timer sequence, which preserves each feature's delay while inactive feature
milestones are never armed. Standalone controls without that dispatcher should still debounce on their
RichTextFX stream rather than doing expensive work per change.

A stripe on the minimap follows the same rule one step further: its *data* is not recomputed per repaint
either. The Git change stripe holds an `int[]` of `{line, count, kind}` triples and asks `GitGutterLines`
for a new one only after `gitMarksChanged()` (new bars, or an edit that moved lines); a scroll re-blits the
cached content image and draws from the array it has, allocating nothing.

The minimap's *content* render (the one that ends in `canvas.snapshot()`, a forced full-scene layout) is
paced on top of its 200 ms typing-pause debounce: above 2,000 lines two edit-driven renders are at least
`lines / 10` ms apart, capped at 2 s (`MinimapPacing`), and the render after the last pause always runs.
Before it clears anything, a render plans the runs it would draw (`MinimapRows.RunList`) and compares them
with the plan behind the cached image; a pause that moved no run — a letter typed inside a word, a
style-only change that still ended a succession — skips the draw and the snapshot.

### 3. Work incrementally, and only on what's visible

- Highlighting re-tokenizes only the **edited range**: from the first edited line, on the stored grammar
  state, to the first line past the edit whose end state (and bracket depth, when bracket colours are on)
  equals what was stored for it (`HighlightPass`). A pass that is dispatched but never applied (superseded,
  or the text changed under it) still owes its range: `HighlightDirty` keeps the range, mapped through later
  edits, until a pass lands. Tokenizing, the bracket and semantic overlays and the per-line splices all run
  on the pool thread; the FX apply is one `setStyleSpans` over the restyled range.
- Lines over 20,000 characters are not tokenized and each line has a one-second budget
  (`TextMateHighlighter.MAX_TOKENIZED_LINE` / `LINE_TIMEOUT`). Passes are queued per grammar
  (`HighlightPass.submit`) so a waiting pass holds no pool thread, and `GrammarRegistry` lookups are
  lock-free; only loads are serialized.
- A buffer with no grammar is not restyled at a typing pause; styles are cleared once, on the transition
  from "styled" to "no grammar".
- A semantic-tokens response is a style-only update: identical tokens restyle nothing, and a different
  list re-tokenizes and restyles only the lines whose tokens differ (`SemanticToken.changedLines`), with the
  overlay built off the FX thread.
- Overlays iterate just the **visible paragraphs**
  (`firstVisibleParToAllParIndex … lastVisibleParToAllParIndex`) and skip folded lines.
- The minimap draws one row per **pixel row**, not per line, once lines are less than a pixel tall
  (`MinimapRows`): the longest of the lines sharing a row stands for it, so a render reads one length per
  line and fetches, scans and draws text only per row of the column. Rows a whole pixel or taller are still
  drawn per line, which is at most a column's height of them.
- Avoid O(document) work on an edit or a scroll.
- **Never** call `getCharacterBoundsOnScreen` synchronously inside a layout/viewport event.
- **Never** ask `getCharacterBoundsOnScreen` for an **empty** range — it allocates a blinking
  `CaretNode` whose timer is never stopped, permanently leaking a pulse receiver. Measure a
  **one-character** range instead (`getCaretBounds()` is focus-dependent and not a substitute). See
  [gotchas.md](gotchas.md#never-ask-getcharacterboundsonscreen-for-an-empty-range).
- The box returned for a one-character range is wider than the glyph advance, so take a character advance
  from the difference of two `minX` values, never from a box width (`WhitespaceOverlay`).
- The line-number gutter is rebuilt per cell by Flowless (every edited paragraph, every row scrolled into
  view), so a row allocates as little as the look allows: its padded number comes from a per-gutter
  `LineNumberText` cache (no `String.format`), its clip is sized in the row's own `layoutChildren` rather
  than through two property bindings, and the empty bookmark and chevron slots are bare `Region`s. The
  per-view scroll features (format bar, AI actions bar, sticky scroll, semantic refresh) share one
  `estimatedScrollY` listener (`installFormatBarListeners`; `ScrollListenerPolicyTest` pins it).
- Per-keystroke helpers read a bounded window, not the document: Enter/Tab/closer handling goes through
  `IndentWindow` slices, brace matching checks the two adjacent characters before building its window, and
  the find bar holds a `SearchMatches` page (at most 100,000 matches) that overlays binary-search. None of
  them may call `area.getText()`.
- The project watcher re-lists only directories whose entries were created or deleted and reconciles the
  rows (`ChildReconciler`); Editora's own saves are registered through `ProjectPanel.noteLocalWrite` so
  they are not treated as external changes.

### 4. Don't defeat the per-node CSS style cache

- Keep token rules as the compound `.text.<class>` selector (see
  [`styles/syntax.css`](../src/main/resources/com/editora/styles/syntax.css)).
- Coalesce adjacent same-style spans (`SpanMerger`) before `setStyleSpans`.

### 5. Preserve the large/huge-file guards

Highlighting + minimap are disabled at **≥ 5 MB**; the file goes read-only with a capped load
at **≥ 50 MB**. Many overlays check `largeFile`/`hugeFile` and no-op. Keep these guards when
touching that code, and bound memory (undo history is capped; loads are capped).

Every text-file candidate uses a tab shell while `MainController` stats, reads, binary-sniffs, resolves
EditorConfig/the charset, and decodes on a virtual thread. File size is not a safe proxy for latency: a
tiny file can live on a cold network mount or FUSE provider. The only required FX-thread step is the final
RichTextFX insertion. The shell applies no path-dependent settings; the prepared EditorConfig result is
applied once, immediately before insertion, rather than walking parent directories on FX and then doing it
again in the loader. The paragraph split and the file hash are also done by the read worker
(`InitialDocument`, `PreparedLoad.fingerprint`), and the loaded String is shared as the saved baseline and
the first text snapshot (`DocumentSnapshots.seed`), so a freshly loaded tab holds one full-text String. A
load of 5 MB or more that will not fit the free heap takes the capped read-only path instead
(`LoadHeapGuard`: about 15× the file size plus a 128 MB margin). Every load takes this path: a session
restore's fill, and a background open on behalf of a diff apply, a workspace edit or an agent write
(`openBackgroundBufferAsync`), prepare the paragraphs on the worker too and hand the prepared EditorConfig
result to `addBuffer`, so attaching the tab never walks the ancestor directories again. Only a merge pane's
save-before-close and the workspace-edit transaction's fallback, both of which must answer synchronously,
still read on the calling thread.

A reload from disk does not swap the document: `EditorBuffer.reloadContent` rewrites only the span that
differs (as `replaceWholeDocument` does), so the paragraphs, styles and folds outside it survive and nothing
is split again on the FX thread; the worker resolves the line endings (`InitialDocument.ofText`). Like a
load it is not an undo step: the history is forgotten, so undo cannot bring back text the disk no longer
has. A reload that needs the segmented or capped profile still installs whole.

The saved baseline — what the modified flag compares the document with — is a `SavedBaseline`: the saved
text's length and `TextFingerprint` (SHA-256 of its chars, computed by the read worker at load and by the
save worker at save), plus the text itself only while it is still the snapshot. Once a dirty buffer's edits
settle the baseline is sealed and the text let go, so a modified tab holds no second copy of its document;
a later edit back to the saved length is compared by hashing the snapshot. The per-keystroke gate is
unchanged: a different length (or line ending) decides without reading the text, and a dirty buffer at the
saved length is compared at the settle, not per edit. A save acknowledges what the worker wrote by document
version, length and fingerprint (`FileWorkflowCoordinator.CommittedSave` keeps neither the bytes nor the
text); on an unchanged document the current snapshot is shared as the new baseline.

Apply the large/heavy/read-only profile before that insertion so disabled features and undo history never
observe the initial document change. Shape matters as well as byte and line counts: a paragraph at **64 KiB
or wider** enters the long-line safety profile, which forces wrapping off, uses the large-file feature
guards, and installs giant paragraphs as bounded, visually identical style segments. The segmentation keeps
the text and paragraph model exact while preventing JavaFX Text from laying out hundreds of thousands of
characters as one glyph node. This protects minified/generated files that evade a line-count threshold but
are pathological to lay out as one paragraph. Navigation requested against the shell must remain queued
until loading completes.

Folding's debounced document detection is also generation-guarded background work. Explicit fold
commands remain synchronous, while large-file mode skips heuristic detection entirely; server and
manual regions can still be applied without scanning the document. A consumer that wants the regions
current without a stall asks `FoldManager.recomputeAsync()` (synchronous only when the heuristic is off,
which reads no text): the Structure outline does so when it attaches to the selected tab, after building its
tree from the regions the manager already holds, and skips even that while its tool window is closed —
a tab switch scans nothing; opening the window rebuilds and asks once. A fold *restore* is always deferred to
that background detection: the saved collapsed lines are applied when the regions arrive and are computed
on demand by `collapsedStartLines()`, `unfoldContaining()` and the fold commands. The restore's scan also
stands in for the debounced pass the load's own insertion would otherwise trigger: that pass runs only when
the document's generation has moved past the one last scanned.

Whole-document consumers must use `EditorBuffer`'s versioned text snapshot rather than independently
calling RichTextFX `getText()`. The first consumer of a document version materializes one immutable String
on the FX thread; highlighting, folding, TODO scans, lint, LSP sync, previews, run-target detection, saves,
and Undo History reuse it. Every plain-text edit invalidates the buffer's cached reference synchronously,
while background work keeps its local snapshot and retains the existing generation/version guard. This
bounds the cache to one current String per buffer and avoids repeating the same O(document) copy across
independently debounced consumers after an edit settles.

Undo is bounded by text as well as by count: the queue holds 300 entries and 64 M characters
(`BudgetedChangeQueue`), the queues of all documents together share the same 64 M-character budget
(`UndoQueueBudget`: the least recently edited document gives up its oldest steps first, and no document is
taken below its newest one), and Undo History checkpoints share an app-wide 64 M-character budget
(`UndoHistoryBudget`). A closed tab forgets its queue at once. An undoable whole-document rewrite must go through
`EditorBuffer.replaceWholeDocument` / `replaceVisibleText`, which record only the differing span — never
`area.replaceText(wholeText)`. Every RichTextFX area outside `EditorBuffer` must be built through
`ui/AreaUndo.none(...)` (read-only: no history) or `AreaUndo.bounded(...)`; the default undo manager is
unlimited and records programmatic edits. `RichTextAreaUndoPolicyTest` enforces this.

Large-file mode has **no** undo, so "it is one undo step" is not a safety net there. A programmatic bulk or
whole-document edit (Replace in Files, a line transform, tool/AI/agent/plugin/LSP output) must ask
`ui/NoUndoGuard.allow(buffer, operationName)` immediately before it edits: on a no-undo buffer the guard
first stores the buffer text in Local History as a labelled revision (blocking until it is on disk) and
tells the user, or returns false — and then the edit must not happen.

Build `HttpClient`s through `io/LazyHttpClient`, never in a field initializer: each one starts a selector
thread when built.

### 6. Bound retained GPU textures

JavaFX's Prism texture pool has a fixed ceiling (default 512 MB); exhausting it makes the
render thread NPE on a null texture — a black window, seen only in the packaged build. So don't
let GPU-backed resources grow with the number of open files:

- A background (non-selected) tab drops its minimap snapshot and shrinks the minimap canvas to 1×1 via
  `EditorBuffer.setRenderingActive(false)`. The snapshot's *pixels* are copied to the CPU first
  (`MinimapRetention`: at most 16 tabs and `ImageCacheBudget.MINIMAP_BUDGET_BYTES`, least recently
  shown out first), so switching back writes them into a fresh image and re-blits — a texture upload, not
  a render — unless the document, colours, tab size or column size changed while the tab was hidden.
  Nothing GPU-backed is retained for a hidden tab.
- Image caches are count- and byte-bounded through `ImageCacheBudget` (Mermaid and Typst included), not
  unbounded maps — each entry pins an `Image` (a texture). The parsed-SVG cache (`SvgImages`) is heap, not
  texture, and is weighed the same way, by an estimate from its source size. Live-preview renders are kept one per surface
  and released when the buffer is disposed (`PreviewSurfaces`).
- Every per-buffer Canvas overlay implements `TabSurface` — the search-highlight, occurrence-highlight,
  inline-values and AceJump overlays included, so a find, a word highlight or a suspended debug session left
  on a hidden tab holds no viewport texture; `EditorBuffer.setRenderingActive` releases them all for a
  background tab, and an overlay with nothing to draw holds a 1×1 canvas (`CanvasGuards.fit` / `release`).
  A jump in progress ends when its tab is hidden (its labels are drawn for one viewport and its keys must
  not stay owned on a tab the user cannot see).
- A plain buffer builds only what every buffer uses: the note and spell overlays (both on by default), the
  minimap and the column ruler (one `Line`). Every other overlay and stripe — whitespace, TODO (overlay and
  stripe, attached with the first non-empty scan result), Markdown lint (overlay and stripe), inline values,
  the LSP diagnostic stripe, search, occurrences, log, Mermaid lint, LSP squiggles, snippets, AceJump — is
  attached on first activation through `attachLazyOverlay` / `attachLazyStripe` at its `EditorBuffer.Layer`,
  which keeps one stacking order whatever a buffer turned on and in whatever sequence. The right-click menu,
  the note hover tooltip and the fold preview tooltip (each a popup window with a scene of its own) are
  built on first use, and the log viewer subscribes to edits only once something asks for its epoch.
  Measured on 30 open 31 KB Java files this takes 14 scene-graph nodes, their subscriptions and three
  popup scenes off every tab; the live heap per tab (4.3–5.7 MB either way) is the document, its style
  spans and the highlighting state, not the overlays.
- A tab that has stayed hidden for `EditorBuffer.HIDDEN_RELEASE_DELAY` (3 minutes) gives back what it keeps
  only for drawing and can rebuild on demand: the cached whole-document String when it is a second copy (a
  dirty buffer's — a clean buffer's snapshot *is* its saved baseline, so dropping it would free nothing and
  cost a copy on the next read) and the spell overlay's per-line spans and squiggle geometry
  (`EditorBuffer.releaseHiddenCaches`, timed by `HiddenTabTimer`: a `CompletableFuture` delay, no thread and
  no pulse receiver; showing the tab cancels it). The delay exists because the snapshot is rebuilt on the FX
  thread in O(document) when next asked for, and a tab switched away from is often switched back to within
  seconds. Undo entries and Undo History checkpoints are user data and are never released this way.
- A restored background tab's editor is kept out of the scene until the tab is first selected
  (`DeferredTabContent`). A hidden but sized RichTextFX area lays out a screenful of cells on every
  document update, which was most of the cost of restoring a session. The editor itself is not even built
  before the first frame: a session restore creates only the selected tab's `EditorBuffer` (and, in a
  split, each group's selected one); every other saved file is a `RestorePlaceholder` tab — title, icon,
  pin, order and group in place, an empty node as content — whose editor is built and filled after the
  selected file has painted, on the `RestoreSchedule` cadence (a time-boxed batch per pulse, a painted
  frame between batches). A tab built in the background also holds its first highlight pass back until it
  is first shown (`EditorBuffer.setHighlightDeferred`): the fill marks the whole document dirty, and
  tokenising thirty files nobody is looking at is pure cost. Measured on a 30-file session (headless, 20–60
  KB Java files): the window build no longer carries ~18 ms of buffer wiring per saved file before `show()`
  returns. Anything that needs a placeholder's editor before its turn — selecting the tab, a workspace edit,
  a recovery record, an MCP buffer call — gets it built on the spot (`MainController.ensureBuffer`,
  `WindowSessionCoordinator.materialize`); everything else that iterates buffers sees a placeholder as "no
  buffer", like an image tab, and treats the file as on disk, which is what its content is.
- A Canvas overlay releases its backing canvas to **1×1** when it has nothing to draw.

When you add any per-buffer `Canvas`/`Image`, make sure it is released or bounded. The dist
build (and `mvn javafx:run`) also raise the caps as a safety net
(`-Dprism.maxvram=2G -Dprism.maxTextureSize=16384`).

## Canvas overlays

Every document overlay (whitespace, spell-check, search-highlight, TODO, Markdown-lint, LSP
diagnostics, …) follows one discipline:

- a **mouse-transparent** `Canvas` sized to the viewport
- coalesced redraw (one per pulse) on scroll / edit / resize
- draws **only the visible paragraphs**
- `CanvasGuards` for dimension clamping + paintability checks
- released to a 1×1 backing texture while inactive (the common case is a buffer that doesn't
  use the feature)
- **lazily attached** on first activation so an off-feature buffer never builds the
  `Canvas`/subscriptions at all — the rule for everything but the two overlays that are on by default
  (notes, spelling); see rule 6 above
- **inert while it has nothing to paint**: every `scheduleRedraw` returns on its `active` flag before any
  viewport call, and an overlay that is on for every buffer (the Personal-Notes highlight) also asks a cheap
  gate (`NoteManager.hasActiveSpans`) before queueing its `runLater`, so a buffer without a note never does
  the visible-paragraph lookups — the same shape as the TODO overlay being off until it has marks

See the recipe in [extending.md](extending.md#add-a-canvas-overlay), and `SpellCheckOverlay` /
`MarkdownLintOverlay` as references.

An overlay that marks many spans per line should not ask RichTextFX where each one is on every frame:
`getCharacterBoundsOnScreen` is the expensive call, and scrolling moves a paragraph without changing its
layout. `SpellCheckOverlay` keeps each paragraph's squiggle positions relative to the paragraph's own box
(`getParagraphBoundsOnScreen`, one lookup per paragraph per frame), keyed by the paragraph object — which
RichTextFX replaces on any edit or restyle of that line — and re-measures only when the box size, the wrap
width, the font or the tab size changes. Measured on a viewport with 182 squiggles: 2.5–5.9 ms per repaint
before, 0.14–0.5 ms after. The same overlay bounds its work per line: a line over 16 KiB is not checked,
one line gets at most 250 squiggles, and a line is cut into tokens once (`SpellChecker.checkableWords`)
rather than once per misspelled word.

## Startup

Time-to-first-paint is measured by `scripts/measure-startup.sh` (the `perf/Startup` marks). The rules below keep
the show path free of work the user is not waiting for; measure against them when touching `App`,
`WindowManager.buildWindow`, `MainController.init` or the session restore.

- **The config prefetch starts before the single-instance probe.** `App.main` starts the `config-prefetch`
  thread and only then probes the endpoint file (a stale one can cost up to the connect timeout). Until the
  probe has decided that this process will run, the thread does only `App.warmConfigLoad` — the JSON mapper,
  the store classes and the main files' bytes, read and dropped — because a load can move files (a migration
  backup, a restored set-aside copy, the TOML conversion) and a launch about to be forwarded must not touch
  the running editor's directory. After the gate it loads the shared config and everything the first window
  used to read on the FX thread: the plugin manifests (`WindowManager.discoverPlugins`), the session of the
  project or untitled window the launch will focus (`WindowManager.primaryWindowStateFile`, adopted through
  `adoptBootstrapConfig(bootstrap, projectWindow)`) and the readability of every saved session entry
  (`config/SessionScan`, handed over by `ConfigManager.setStartupSessionScan` and taken once by
  `WindowSessionCoordinator.openInitialBuffer`, which therefore stats nothing on FX). A window built any other
  way reads and scans inline, with the same rules.
- **Probes wait for the first painted frame.** `perf/AfterFirstPaint` is held by `App.start` before the first
  window and released by the frame after the first content lands (`FileWorkflowCoordinator.firstPainted`, the
  same two-tick timer as the `FIRST_PAINT` mark) or, for a window that never loads content that way, by
  `WindowManager.FIRST_PAINT_FALLBACK` after show. Behind it: the login-shell PATH warm-up
  (`ProcessRunner.warmAugmentedPathAsync`, at once when LSP is on since a session start reads it on FX; the
  `LspManager` constructor no longer spawns it), the `rg --version` probe (and none at all while the ripgrep
  setting is off), the `gh` probe, the Hunspell dictionary build (`SpellDictionaries.ensureBuilt` keeps the
  waiting callbacks), the clipboard read behind the Paste button, and the release check, which also waits
  `UpdateCheckScheduler.DELAY` after that. Outside a launch (tests, a later window) `AfterFirstPaint.run`
  runs its task at once, so callers need not know whether a hold exists. Keep the queued tasks starters —
  an executor submit, a thread start — since they run on the FX thread at release.
- **The focus a window gains by being shown is not a regain.** `MainController.onWindowFocusGained` skips the
  regain fan-out (external-change check, `git status` + diff, build-tool detection, clipboard, Project tree
  re-listing, open diffs) for that first focus; it only computes the repository state when no tab switch did
  (`StartupFocus`), so a window opening on Welcome still gets its Git status once.
- **No clipboard round trip without a buffer.** `refreshPasteState` asks the clipboard owner only when a
  buffer could take the paste; the editability flip of every restored shell goes through the coalesced
  `schedulePasteStateRefresh`.
- **The projects index is not `fsync`ed on FX at launch.** `WindowManager.launch` and `finishRestore` save it
  through `SharedConfig.saveProjectsQueued` (`ProjectManager.saveQueued`: serialized now, written on the config
  writer; a direct `save()` made meanwhile is newer and wins), and the orphan session sweep
  (`gcOrphanWindowSessions`) lists and deletes on a virtual thread.

What stays: every store is still re-serialized once at load for the `StoreSync` baseline. The baseline must be
the canonical form of the store as loaded — the "nothing changed" check and the three-way merge compare
against it — and a decoded-bytes baseline would change what a merge keeps of another writer's keys. That
serialization runs on the prefetch thread for every store a launch reads.

## Packaged-runtime tuning

The dist `<javaOptions>` (mirrored into `javafx:run` so dev == prod) pin heap and GC:

- **`-Xmx2g`** — predictable across the release matrix, and safe for a 50 MB file (the huge-file
  read cap) with deep undo.
- **`-Xms64m`** — set explicitly, because the default initial heap is 1/64 of physical RAM
  *clamped up to `-Xmx`*: on a big-RAM machine that equals `-Xmx`, so the whole 2 GB heap is
  committed before `main` runs. Measured on Linux with a 4-file session: peak RSS median 908 MB
  (n=4), held ~58 s until the periodic GC uncommits, versus 653 MB (n=6) with `-Xms64m`, and no
  startup cost (5 interleaved pairs, mean −27 ms). The live heap is only 63–75 MB idle.
- **`-XX:+UseG1GC -XX:G1PeriodicGCInterval=30000`** — G1, *not* SerialGC: measured on a real
  session, SerialGC cost 434 MB more RSS and a 186 ms max pause against G1's 35 ms. The periodic
  interval is what returns idle memory to the OS. The full measurement notes live in the pom
  beside the options.

The jlinked runtime is stripped for size. An AOT cache (JDK 25 Leyden) shaves ~300–480 ms off cold
start and costs ~71 MB of resident, file-backed, shared mapping. None of this changes behavior —
but if you touch startup or large-file handling, measure against these settings, since they're
what ships.

When measuring memory yourself, know that **`jcmd GC.run` + `GC.heap_info` does not report the live
set** — `heap_info` prints *used*, which a few seconds after a forced GC can still be twice the live
bytes (measured 216 MB against 103 MB live). Use `GC.class_histogram`'s total, which forces its own
stop-the-world full GC. A `Concurrent Mark Cycle` line in the GC log is likewise **not a pause** — grep
`GC(n) Pause`. And if you take a heap dump to chase a suspected leak, make sure the diagnostic itself
holds no reference to the object: a live local slot makes it a "Java frame" GC root and the dump then
shows a retention that is purely your own doing.

Two more things to know before measuring memory yourself: **settled RSS has a ±90 MB run-to-run noise
floor** (two runs of an identical config landed at 552 MB and 688 MB — the variance is how much of
the heap region stays resident after an uncommit), so judge changes on **peak RSS and NMT category
totals**, which are stable, and never on a single settled reading.
