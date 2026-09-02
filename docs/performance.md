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

For text-driven work, debounce on the RichTextFX stream rather than per-change:
`area.multiPlainChanges().successionEnds(Duration.ofMillis(250)).subscribe(...)`.

### 3. Work incrementally, and only on what's visible

- Highlighting re-tokenizes only from the **changed line**, carrying grammar state across lines.
- Overlays iterate just the **visible paragraphs**
  (`firstVisibleParToAllParIndex … lastVisibleParToAllParIndex`) and skip folded lines.
- Avoid O(document) work on an edit or a scroll.
- **Never** call `getCharacterBoundsOnScreen` synchronously inside a layout/viewport event.
- **Never** ask `getCharacterBoundsOnScreen` for an **empty** range — it allocates a blinking
  `CaretNode` whose timer is never stopped, permanently leaking a pulse receiver. Measure a
  **one-character** range instead (`getCaretBounds()` is focus-dependent and not a substitute). See
  [gotchas.md](gotchas.md#never-ask-getcharacterboundsonscreen-for-an-empty-range).

### 4. Don't defeat the per-node CSS style cache

- Keep token rules as the compound `.text.<class>` selector (see
  [`styles/syntax.css`](../src/main/resources/com/editora/styles/syntax.css)).
- Coalesce adjacent same-style spans (`SpanMerger`) before `setStyleSpans`.

### 5. Preserve the large/huge-file guards

Highlighting + minimap are disabled at **≥ 5 MB**; the file goes read-only with a capped load
at **≥ 50 MB**. Many overlays check `largeFile`/`hugeFile` and no-op. Keep these guards when
touching that code, and bound memory (undo history is capped; loads are capped).

Initial opens of local files at **≥ 256 KB**, and all remote files, use a tab shell while
`MainController` reads, binary-sniffs, resolves the charset, and decodes on a virtual thread. The
only required FX-thread step is the final RichTextFX insertion. Apply the large/heavy/read-only
profile before that insertion so disabled features and undo history never observe the initial
document change. Navigation requested against the shell must remain queued until loading completes.

Folding's debounced document detection is also generation-guarded background work. Explicit fold
commands remain synchronous, while large-file mode skips heuristic detection entirely; server and
manual regions can still be applied without scanning the document.

### 6. Bound retained GPU textures

JavaFX's Prism texture pool has a fixed ceiling (default 512 MB); exhausting it makes the
render thread NPE on a null texture — a black window, seen only in the packaged build. So don't
let GPU-backed resources grow with the number of open files:

- A background (non-selected) tab drops its minimap snapshot via
  `EditorBuffer.setRenderingActive(false)`.
- Image caches (`PreviewImageLoader`, `MermaidImages`) are LRU-bounded, not unbounded maps —
  each pins an `Image` (a texture).
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
