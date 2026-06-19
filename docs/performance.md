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

### 4. Don't defeat the per-node CSS style cache

- Keep token rules as the compound `.text.<class>` selector (see
  [`styles/syntax.css`](../src/main/resources/com/editora/styles/syntax.css)).
- Coalesce adjacent same-style spans (`SpanMerger`) before `setStyleSpans`.

### 5. Preserve the large/huge-file guards

Highlighting + minimap are disabled at **≥ 5 MB**; the file goes read-only with a capped load
at **≥ 50 MB**. Many overlays check `largeFile`/`hugeFile` and no-op. Keep these guards when
touching that code, and bound memory (undo history is capped; loads are capped).

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

The dist `<javaOptions>` (mirrored into `javafx:run` so dev == prod) cap heap and GC: `-Xmx2g`
(predictable across the release matrix), `-XX:+UseSerialGC` (lowest idle RSS for a mostly-idle
single-user editor on a small heap). The jlinked runtime is stripped for size. An AOT cache
(JDK 25 Leyden) shaves ~300–480 ms off cold start. None of this changes behavior — but if you
touch startup or large-file handling, measure against these settings, since they're what ships.
