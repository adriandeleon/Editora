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
bar's byte count, the Run-glyph scan (`RunScan`), external-change and recent-file checks, and directory
listings for Find File and the breadcrumb (`DirectoryListing`).

Console output is a special case of "don't flood FX": `process/OutputPump` delivers each drain inside an
`OutputBatch`, `ui/ConsoleAppender` applies a drain as one append, one style application and one trim, and
drains start at least 16 ms apart. Pipe and socket writes to language servers, debug adapters and the ACP
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
is normally but not always the next pulse. A true once-per-frame scheduler was prototyped and not adopted
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
- Avoid O(document) work on an edit or a scroll.
- **Never** call `getCharacterBoundsOnScreen` synchronously inside a layout/viewport event.
- **Never** ask `getCharacterBoundsOnScreen` for an **empty** range — it allocates a blinking
  `CaretNode` whose timer is never stopped, permanently leaking a pulse receiver. Measure a
  **one-character** range instead (`getCaretBounds()` is focus-dependent and not a substitute). See
  [gotchas.md](gotchas.md#never-ask-getcharacterboundsonscreen-for-an-empty-range).
- The box returned for a one-character range is wider than the glyph advance, so take a character advance
  from the difference of two `minX` values, never from a box width (`WhitespaceOverlay`).
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
(`LoadHeapGuard`: about 15× the file size plus a 128 MB margin).

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
manual regions can still be applied without scanning the document. A fold *restore* for a document of
256 KiB or more is deferred to that background detection: the saved collapsed lines are applied when the
regions arrive and are computed on demand by `collapsedStartLines()`, `unfoldContaining()` and the fold
commands.

Whole-document consumers must use `EditorBuffer`'s versioned text snapshot rather than independently
calling RichTextFX `getText()`. The first consumer of a document version materializes one immutable String
on the FX thread; highlighting, folding, TODO scans, lint, LSP sync, previews, run-target detection, saves,
and Undo History reuse it. Every plain-text edit invalidates the buffer's cached reference synchronously,
while background work keeps its local snapshot and retains the existing generation/version guard. This
bounds the cache to one current String per buffer and avoids repeating the same O(document) copy across
independently debounced consumers after an edit settles.

Undo is bounded by text as well as by count: the queue holds 300 entries and 64 M characters
(`BudgetedChangeQueue`), and Undo History checkpoints share an app-wide 64 M-character budget
(`UndoHistoryBudget`). An undoable whole-document rewrite must go through
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

- A background (non-selected) tab drops its minimap snapshot via
  `EditorBuffer.setRenderingActive(false)`.
- Image caches are count- and byte-bounded through `ImageCacheBudget` (Mermaid and Typst included), not
  unbounded maps — each entry pins an `Image` (a texture). Live-preview renders are kept one per surface
  and released when the buffer is disposed (`PreviewSurfaces`).
- Every per-buffer Canvas overlay implements `TabSurface`; `EditorBuffer.setRenderingActive` releases them
  all for a background tab, and an overlay with nothing to draw holds a 1×1 canvas (`CanvasGuards.fit` /
  `release`).
- A restored background tab's editor is kept out of the scene until the tab is first selected
  (`DeferredTabContent`). A hidden but sized RichTextFX area lays out a screenful of cells on every
  document update, which was most of the cost of restoring a session.
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
- often **lazily attached** on first activation so an off-feature buffer never builds the
  `Canvas`/subscriptions at all

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
