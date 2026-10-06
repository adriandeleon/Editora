package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Text;

import com.editora.editor.FoldRegions.Region;
import org.fxmisc.richtext.CharacterHit;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.TwoDimensional.Bias;

/**
 * Computes foldable regions for a {@link CodeArea}, drives folding/unfolding, and supplies the gutter
 * graphic factory that draws line numbers alongside IntelliJ-style fold chevrons
 * ({@code &#9662;} expanded / {@code &#9656;} collapsed).
 *
 * <p>Folding is delegated to {@code CodeArea}'s public fold API, which collapses paragraphs by tagging
 * their paragraph style with {@code "collapse"} (hidden via {@code visibility: collapse} in RichTextFX's
 * built-in stylesheet). Regions are recomputed on a debounced text change.
 */
public final class FoldManager {

    private final CodeArea area;
    private java.util.function.IntSupplier caretLine;
    private Supplier<CodeArea> secondView = () -> null;
    /** Full-text source; EditorBuffer supplies its per-version shared snapshot. */
    private final Supplier<String> textSnapshot;

    private List<Region> regions = List.of();
    /** Server-supplied regions ({@code textDocument/foldingRange}, #738); null while none have arrived, in
     *  which case {@link #recompute()} falls back to the {@link FoldRegions} heuristic. */
    private List<Region> serverRegions;

    private Map<Integer, Region> byStart = Map.of();
    private String language = "plaintext";
    /** Supersedes debounced background detections when the text/language/region sources change again. */
    private long recomputeGeneration;
    /** Max number of lines shown in a collapsed region's hover preview. */
    private static final int PREVIEW_LINES = 40;
    /** Minimum digit width the line-number gutter pads to, so a file of fewer than 10 lines still reserves
     *  2-digit width — adding the 10th line then doesn't widen the gutter and shift the text rightward. */
    private static final int MIN_LINE_DIGITS = 2;
    /** Fixed width of the gutter's bookmark-marker column, reserved on every row so the gutter (and
     *  thus each line's text indentation) keeps a constant width whether or not the line is bookmarked. */
    private static final double BOOKMARK_SLOT_WIDTH = 12;
    /** Width of the Git change-bar column, reserved on every row while change tracking is on so toggling
     *  a bar never shifts the text indentation (mirrors the bookmark slot). */
    private static final double CHANGE_SLOT_WIDTH = 3;
    /** Width of the Run column, reserved on every row only while this is a compact source file. Snug
     *  around the (narrow) play glyph so it doesn't leave dead space next to the line number. */
    private static final double RUN_SLOT_WIDTH = 13;
    /** Width of the breakpoint column (leftmost), reserved on every row only while debugging is enabled.
     *  Clicking anywhere in this strip toggles a breakpoint (IntelliJ-style), so it never collides with
     *  the gutter's bookmark-toggle click. */
    private static final double BREAKPOINT_SLOT_WIDTH = 14;
    /** Material "circle" — the filled red breakpoint dot. */
    static final String BREAKPOINT_GLYPH_PATH = "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2z";
    /** Material "play_arrow" triangle, shared by the gutter Run glyph and the right-click Run menu item. */
    static final String RUN_GLYPH_PATH = "M8 5v14l11-7z";

    /** Notified after a user-driven fold/unfold so callers can persist the new state. */
    private Runnable onFoldStateChanged = () -> {};
    /** Notified after regions are recomputed (e.g. on text or language change). */
    private Runnable onRegionsChanged = () -> {};
    /** Suppresses change notifications while we programmatically restore saved folds. */
    private boolean restoring;

    /**
     * A document at least this long restores its folds from a background detection rather than scanning the
     * whole text on the FX thread while the file opens (measured: 150 ms of a 4.9 MB open). Below it the scan
     * costs a few milliseconds and the restore stays synchronous.
     */
    static final int DEFERRED_RESTORE_CHARS = 256 * 1024;
    /** True from a deferred restore until its regions (or any newer ones) are applied. */
    private boolean regionsPending;
    /** Saved collapsed header lines waiting for those regions, kept in step with edits; null when none. */
    private List<Integer> pendingCollapsed;

    /** Whether a line carries a bookmark (drawn as a gutter marker); default none. */
    private IntPredicate isBookmarked = i -> false;

    /** Whether this is a compact source file (reserve the Run slot on every row while true). */
    private BooleanSupplier runEnabled = () -> false;
    /** Whether a line draws the clickable green Run glyph (a single {@code main}, or each .http request). */
    private IntPredicate isRunLine = i -> false;
    /** Invoked with the clicked line when the user clicks a gutter Run glyph. */
    private IntConsumer onRun = i -> {};
    /** An extra style class for a line's Run glyph (e.g. {@code test-run-marker}), or {@code null}. */
    private IntFunction<String> runExtraClass = i -> null;
    /** Hover text for a line's Run glyph (e.g. "Run test method foo"), or {@code null} for no tooltip. */
    private IntFunction<String> runTooltip = i -> null;

    /** Whether debugging is enabled for this buffer (reserve the leftmost breakpoint slot on every row). */
    private BooleanSupplier breakpointsEnabled = () -> false;
    /** Whether a line carries a breakpoint (draws the red dot there). */
    private IntPredicate isBreakpoint = i -> false;
    /** Extra CSS class for a line's breakpoint glyph (e.g. {@code conditional}/{@code logpoint}/disabled),
     *  or {@code null} for a plain breakpoint. */
    private IntFunction<String> breakpointClass = i -> null;
    /** Hover text for a line's breakpoint glyph (what a live debug session says about it), or {@code null}. */
    private IntFunction<String> breakpointTooltip = i -> null;
    /** Invoked when the user clicks the breakpoint strip on a line (toggles the breakpoint). */
    private IntConsumer onBreakpointToggle = i -> {};

    /** Whether Git change tracking is active for this buffer (reserve the change-bar slot). */
    private BooleanSupplier changeBarsEnabled = () -> false;
    /** CSS style class for a line's Git change bar (e.g. {@code git-added}), or {@code null} for none. */
    private IntFunction<String> changeClass = i -> null;
    /** Hunk text (unified diff) for a line's change bar hover tooltip, or {@code null} for none. */
    private IntFunction<String> changeTooltip = i -> null;

    /** Whether the IntelliJ-style blame "Annotate" column is shown (reserve the leftmost annotation slot). */
    private BooleanSupplier blameEnabled = () -> false;
    /** Per-line blame annotation (author/date/tooltip/heatmap bg/hash), or {@code null} for an empty row. */
    private IntFunction<BlameInfo> blameInfo = i -> null;
    /** Fixed pixel width of the annotation column (computed once from the widest annotation), so the
     *  line numbers stay aligned regardless of which row is built. */
    private DoubleSupplier blameColumnWidth = () -> 0;
    /** Invoked when the user clicks a line's blame annotation (shows that line's commit). */
    private IntConsumer onBlameClick = i -> {};

    /** Digit width the gutter line numbers were last padded to; re-pad visible rows when it changes. */
    private int lastLineDigits = 1;

    /** Shared hover preview of a collapsed line's hidden content; reused across all lines. */
    private final Tooltip linePreview = new Tooltip();
    /** Paragraph whose preview is currently showing, or -1 when hidden. */
    private int previewPar = -1;
    /** Current preview foreground, also exposed as a looked-up color to plain styled-text runs. */
    private Color previewForeground = Color.web("#24292f");

    /**
     * A split shows the document in two views, each with its own caret. The "at caret" commands then mean the
     * caret of the view the user is in ({@code focused}); left alone it is the primary view's. A fold is made
     * through the primary view, so RichTextFX moves only that view's caret off a line it hides: the
     * {@code second} view's (null while there is none) is put on the fold's header here.
     */
    void setSplitViews(Supplier<CodeArea> focused, Supplier<CodeArea> second) {
        this.caretLine = () -> focused.get().getCurrentParagraph();
        this.secondView = second;
    }

    private void foldStateChanged() {
        CodeArea view = secondView.get();
        int line = view == null ? -1 : view.getCurrentParagraph();
        if (line > 0 && view.isFolded(line)) {
            while (line > 0 && view.isFolded(line)) {
                line--;
            }
            view.moveTo(line, view.getParagraphLength(line));
        }
        onFoldStateChanged.run();
    }

    public FoldManager(CodeArea area) {
        this(area, area::getText);
    }

    FoldManager(CodeArea area, Supplier<String> textSnapshot) {
        this.area = area;
        this.caretLine = area::getCurrentParagraph;
        this.textSnapshot = textSnapshot;
        area.multiPlainChanges().successionEnds(Duration.ofMillis(250)).subscribe(ignore -> {
            if (heuristicEnabled) {
                recomputeAsync();
            }
        });
        // Manual regions are anchored to nothing, so they shift through every edit immediately (the
        // BookmarkManager pattern) — the debounced recompute above re-detects, it cannot re-anchor.
        // The empty-list early-out keeps this a single field check per keystroke for every buffer
        // without manual folds, i.e. almost all of them.
        area.plainTextChanges().subscribe(ch -> {
            recomputeGeneration++;
            noteEditNearFold(ch.getPosition(), ch.getInserted(), ch.getRemoved());
            if (manualRegions.isEmpty() && pendingCollapsed == null) {
                return;
            }
            int startLine = area.offsetToPosition(
                            ch.getPosition(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
            int removedLines = ManualFolds.lineBreaks(ch.getRemoved());
            int insertedLines = ManualFolds.lineBreaks(ch.getInserted());
            manualRegions = ManualFolds.shift(manualRegions, startLine, removedLines, insertedLines);
            if (pendingCollapsed != null) {
                // The detection this edit just superseded is redone on the settle; its headers moved meanwhile.
                pendingCollapsed = ManualFolds.shiftLines(pendingCollapsed, startLine, removedLines, insertedLines);
            }
        });
        installHoverPreview();
    }

    /** User-defined fold ranges with no syntactic basis (see {@link ManualFolds}); merged into
     *  {@link #regions} by {@link #recompute}. */
    private List<Region> manualRegions = List.of();

    /** False for large-file mode: manual/server folds still work, but no whole-document heuristic scan runs. */
    private boolean heuristicEnabled = true;

    public void setHeuristicEnabled(boolean enabled) {
        if (heuristicEnabled == enabled) {
            return;
        }
        heuristicEnabled = enabled;
        recompute();
    }

    /**
     * Adds a manual fold range (already-present or degenerate spans are ignored) and folds it. Returns
     * whether it was added.
     */
    public boolean addManualFold(Region r) {
        if (r == null || r.endLine() <= r.startLine() || manualRegions.contains(r)) {
            return false;
        }
        List<Region> next = new ArrayList<>(manualRegions);
        next.add(r);
        manualRegions = next;
        recompute(); // the new header needs its chevron before fold() records the collapse
        fold(r);
        return true;
    }

    /** Removes every manual fold range, unfolding any that are collapsed. Returns how many were removed. */
    public int removeManualFolds() {
        settleRegions();
        List<Region> removed = manualRegions;
        if (removed.isEmpty()) {
            return 0;
        }
        for (Region r : removed) {
            if (isCollapsed(r.startLine())) {
                unfold(r.startLine());
            }
        }
        manualRegions = List.of();
        recompute();
        return removed.size();
    }

    /** The manual fold ranges, for persistence (see {@code MainController.persistFolds}). */
    public List<Region> manualRegions() {
        return manualRegions;
    }

    /** Installs restored manual fold ranges (session restore); folding state arrives separately via
     *  {@link #applyCollapsedStartLines}, which needs the merged regions in place first. */
    public void setManualRegions(List<Region> restored) {
        manualRegions = restored == null || restored.isEmpty() ? List.of() : List.copyOf(restored);
        recompute();
    }

    /**
     * Shows the fold preview whenever the pointer is anywhere on a collapsed header line (text or
     * gutter), and hides it otherwise. The vertical position of the pointer selects the paragraph,
     * so the whole line is a hover target.
     */
    private void installHoverPreview() {
        linePreview.getStyleClass().add("fold-preview-tooltip");
        linePreview.setShowDuration(javafx.util.Duration.INDEFINITE);
        area.addEventHandler(MouseEvent.MOUSE_MOVED, this::updateHoverPreview);
        area.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hidePreview());
    }

    /**
     * Colors the collapsed-region preview to match the editor theme. A Tooltip renders in its own
     * popup that the scene's editor-theme stylesheet doesn't reach, so the colors are set inline.
     */
    public void setPreviewColors(Color background, Color foreground) {
        previewForeground = foreground;
        Color border = background.interpolate(foreground, 0.3);
        linePreview.setStyle("-fx-background-color: " + hex(background) + ";"
                + "-fx-text-fill: " + hex(foreground) + ";"
                + "-fx-border-color: " + hex(border) + ";"
                + "-fx-border-width: 1;"
                + "-fx-padding: 6 8 6 8;"
                + "-fx-font-family: \"JetBrains Mono\", \"monospace\";"
                + "-fx-font-size: 12px;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0.15, 0, 3);");
    }

    private static String hex(Color c) {
        return String.format(
                "#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int)
                        Math.round(c.getBlue() * 255));
    }

    private void updateHoverPreview(MouseEvent e) {
        int par = paragraphAt(e.getX(), e.getY());
        if (par >= 0 && byStart.containsKey(par) && isCollapsed(par)) {
            if (par != previewPar || !linePreview.isShowing()) {
                previewPar = par;
                linePreview.setText(null);
                linePreview.setGraphic(foldPreviewGraphic(byStart.get(par)));
                linePreview.show(area, e.getScreenX() + 12, e.getScreenY() + 16);
            }
        } else {
            hidePreview();
        }
    }

    private void hidePreview() {
        previewPar = -1;
        if (linePreview.isShowing()) {
            linePreview.hide();
        }
    }

    private int paragraphAt(double x, double y) {
        try {
            CharacterHit hit = area.hit(x, y);
            return area.offsetToPosition(hit.getInsertionIndex(), Bias.Forward).getMajor();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    public void setOnFoldStateChanged(Runnable callback) {
        this.onFoldStateChanged = callback == null ? () -> {} : callback;
    }

    /** Sets a callback invoked whenever foldable regions are recomputed. */
    public void setOnRegionsChanged(Runnable callback) {
        this.onRegionsChanged = callback == null ? () -> {} : callback;
    }

    /**
     * Installs the regions a language server reported ({@code textDocument/foldingRange}, #738), or
     * {@code null}/empty to go back to the {@link FoldRegions} heuristic — which is what happens when LSP is
     * off, the file is remote, or the server has no folding provider.
     *
     * <p>Recomputes only when the region list actually changed. The request rides the same debounced
     * document pulse as diagnostics, and a server re-reports identical regions for any edit that doesn't
     * move a block boundary — by far the common case — so skipping the no-op keeps the gutter untouched
     * instead of rebuilding fold graphics on every settle.
     */
    public void setServerRegions(List<Region> newRegions) {
        List<Region> next = newRegions == null || newRegions.isEmpty() ? null : List.copyOf(newRegions);
        if (java.util.Objects.equals(serverRegions, next)) {
            return;
        }
        serverRegions = next;
        recompute();
    }

    /** Sets the language (see {@link LanguageRegistry}) and recomputes regions. */
    public void setLanguage(String language) {
        this.language = language;
        recompute();
    }

    /**
     * Recomputes foldable regions from the current text. Only the gutter graphics whose fold-start
     * status actually changed are recreated, so editing never reinstalls the whole factory (which
     * would reset the viewport).
     */
    public void recompute() {
        recomputeGeneration++; // invalidate a debounced result captured before this explicit recompute
        String text = heuristicEnabled ? textSnapshot.get() : "";
        applyRegions(detectRegions(text, language, serverRegions, manualRegions, heuristicEnabled));
    }

    /**
     * Runs the expensive brace/indent/comment scan away from the FX thread after typing or initial load.
     * Capturing RichTextFX text stays on FX; only the pure detector crosses threads. A generation check
     * prevents an older snapshot from replacing regions for newer text or language state.
     */
    private void recomputeAsync() {
        long generation = ++recomputeGeneration;
        String text = textSnapshot.get();
        String languageSnapshot = language;
        List<Region> serverSnapshot = serverRegions;
        List<Region> manualSnapshot = manualRegions;
        Thread.ofVirtual().name("editora-fold-detect").start(() -> {
            List<Region> detected = detectRegions(text, languageSnapshot, serverSnapshot, manualSnapshot, true);
            javafx.application.Platform.runLater(() -> {
                if (generation == recomputeGeneration && heuristicEnabled) {
                    applyRegions(detected);
                }
            });
        });
    }

    private static List<Region> detectRegions(
            String text,
            String language,
            List<Region> serverRegions,
            List<Region> manualRegions,
            boolean heuristicEnabled) {
        // A language server's regions win when it supplied any: it parses the grammar, so it knows the
        // import block is one region and where a javadoc ends — neither of which brace/indent scanning can
        // derive. An empty or absent answer falls back to the heuristic rather than leaving the file
        // unfoldable, which also covers the window between opening a file and its server reporting ready.
        boolean serverBacked = serverRegions != null && !serverRegions.isEmpty();
        List<Region> base =
                serverBacked ? serverRegions : heuristicEnabled ? FoldRegions.detect(text, language) : List.of();
        // Block comments, #region markers, and the user's manual ranges are ADDITIVE regardless of where
        // the base came from — a server's answer replaces the structural heuristic, not these. A server
        // that also reports comment regions (LSP folding kinds) just dedups in canonicalOrder.
        List<Region> comments = heuristicEnabled ? FoldRegions.blockComments(text, language) : List.of();
        List<Region> markerRegions = heuristicEnabled ? FoldRegions.markers(text, language) : List.of();
        if (comments.isEmpty() && markerRegions.isEmpty() && manualRegions.isEmpty()) {
            return base;
        }
        List<Region> merged = new ArrayList<>(base);
        merged.addAll(comments);
        merged.addAll(markerRegions);
        merged.addAll(manualRegions);
        return FoldRegions.canonicalOrder(merged);
    }

    private void applyRegions(List<Region> detected) {
        List<Integer> restoreCollapsed = pendingCollapsed;
        regionsPending = false;
        pendingCollapsed = null;
        Set<Integer> oldStarts = byStart.keySet();
        regions = detected;
        Map<Integer, Region> map = new HashMap<>();
        for (Region r : regions) {
            // Keep the outermost region for a given header line (largest span wins).
            Region existing = map.get(r.startLine());
            if (existing == null || r.endLine() > existing.endLine()) {
                map.put(r.startLine(), r);
            }
        }
        Set<Integer> changed = new HashSet<>(oldStarts);
        changed.addAll(map.keySet());
        changed.removeIf(line -> oldStarts.contains(line) && map.containsKey(line));
        byStart = map;
        if (orphanCheckPending) {
            orphanCheckPending = false;
            expandOrphanRuns();
        }
        int total = area.getParagraphs().size();
        // Only recreate the gutter graphics for changed fold-start lines that are *currently visible*; the
        // graphic factory reads the (now-updated) byStart when it lazily builds offscreen rows on scroll,
        // so recreating them eagerly is wasted work — and recreating thousands at once (e.g. the initial
        // recompute of a large file, which marks every fold header changed) froze the FX thread for seconds.
        // Ask where the viewport is ONLY when something actually has to be recreated. Both
        // firstVisibleParToAllParIndex and lastVisibleParToAllParIndex force a VirtualFlow layout, so
        // querying them unconditionally spent a synchronous layout pass to discover there was nothing to do
        // — and that is the common case twice over: on the typing path most edits leave the fold structure
        // untouched (this runs on every 250 ms settle), and at startup the recompute that setLanguage
        // triggers runs against a still-empty document, measured at ~10 ms of pure forced layout.
        if (!changed.isEmpty()) {
            recreateVisibleGutter(area, changed, total);
            CodeArea second = secondView.get();
            if (second != null && second.getScene() != null) { // a split's second view shows the same chevrons
                recreateVisibleGutter(second, changed, total);
            }
        }
        // The line-number gutter pads to the digit width of the line count (see formatLineNo). Since the
        // line number is now set directly (no live binding), re-pad the visible rows when that width
        // changes — i.e. the count crossed a power of 10. Runs only on this debounced recompute, not per
        // keystroke; offscreen rows get the right width when they're next built.
        int d = digits(total);
        if (d != lastLineDigits) {
            lastLineDigits = d;
            repadVisibleLineNumbers(total);
        }
        onRegionsChanged.run();
        if (restoreCollapsed != null) {
            collapseDeferred(restoreCollapsed);
        }
    }

    /**
     * Collapses the saved headers of a deferred restore. Unlike the synchronous restore this runs after the
     * file is on screen, so it must leave the caret, the selection and the viewport where they are — and it
     * keeps open a region the caret has since been taken into (a queued {@code file:line} jump, a find), which
     * is where the synchronous order ends up too: restore first, then the jump unfolds its target.
     */
    private void collapseDeferred(List<Integer> startLines) {
        Set<Integer> wanted = new HashSet<>(startLines);
        int caret = area.getCaretPosition();
        int anchor = area.getAnchor();
        int caretLine = area.getCurrentParagraph();
        int anchorLine = area.offsetToPosition(anchor, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        int topPar = -1;
        boolean folded = false;
        boolean anchorHidden = false;
        restoring = true;
        try {
            for (Region r : regions) {
                if (!wanted.contains(r.startLine())
                        || isCollapsed(r.startLine())
                        || (caretLine > r.startLine() && caretLine <= r.endLine())) {
                    continue;
                }
                if (!folded) {
                    topPar = firstVisiblePar(); // forces a layout: asked once, and only when something folds
                    folded = true;
                }
                anchorHidden |= anchorLine > r.startLine() && anchorLine <= r.endLine();
                area.foldParagraphs(r.startLine(), r.endLine());
                shadeHeader(r.startLine(), true);
            }
        } finally {
            restoring = false;
        }
        if (folded) {
            int to = Math.min(caret, area.getLength()); // foldParagraphs() moved the caret to a fold header
            area.selectRange(anchorHidden ? to : Math.min(anchor, area.getLength()), to);
            restoreViewport(topPar);
        }
    }

    /** Whether a restore of this document's folds should wait for a background detection. */
    private boolean deferRestore() {
        return heuristicEnabled && area.getLength() >= DEFERRED_RESTORE_CHARS;
    }

    /** Starts the background detection of a deferred restore, carrying {@code collapsed} to its arrival. */
    private void restoreDeferred(List<Integer> collapsed) {
        if (collapsed != null && !collapsed.isEmpty()) {
            List<Integer> merged = new ArrayList<>(pendingCollapsed == null ? List.of() : pendingCollapsed);
            merged.addAll(collapsed);
            pendingCollapsed = merged;
        }
        regionsPending = true;
        recomputeAsync();
    }

    /**
     * Regions a deferred restore is still detecting are computed now: an explicit fold command acts on the
     * regions, so it cannot run against the empty list of a file that has only just opened.
     */
    private void settleRegions() {
        if (regionsPending) {
            recompute();
        }
    }

    /** As {@link #settleRegions}, for a caller that only cares about folds that are saved but not yet applied. */
    private void settleCollapsed() {
        if (pendingCollapsed != null) {
            recompute();
        }
    }

    /** Recreates the gutter graphics of those of {@code lines} (null = all) that {@code view} is showing. */
    private static void recreateVisibleGutter(CodeArea view, java.util.Collection<Integer> lines, int total) {
        int first;
        int last;
        try {
            first = Math.max(0, view.firstVisibleParToAllParIndex());
            last = Math.min(total - 1, view.lastVisibleParToAllParIndex());
        } catch (RuntimeException notLaidOutYet) {
            return; // no viewport yet (e.g. during open) — the factory builds correct graphics on layout
        }
        if (lines == null) {
            for (int i = first; i <= last; i++) {
                view.recreateParagraphGraphic(i);
            }
            return;
        }
        for (int line : lines) {
            if (line >= first && line <= last) {
                view.recreateParagraphGraphic(line);
            }
        }
    }

    /** Recreates the visible rows' gutter graphics so their line numbers re-pad to a new digit width. */
    private void repadVisibleLineNumbers(int total) {
        recreateVisibleGutter(area, null, total);
        CodeArea second = secondView.get();
        if (second != null && second.getScene() != null) {
            recreateVisibleGutter(second, null, total);
        }
    }

    /** Wires the bookmark gutter marker: a predicate for which lines are bookmarked. The marker is purely
     *  a visual indicator — adding/removing a bookmark is done from the command palette or the editor's
     *  right-click menu, never by clicking the gutter (see {@link #buildGutter}). */
    public void setBookmarkHooks(IntPredicate isBookmarked) {
        this.isBookmarked = isBookmarked == null ? i -> false : isBookmarked;
    }

    /** Wires the Personal-Notes gutter marker: a predicate for which lines carry a note + a click handler
     *  (clicking the marker opens/edits the note on that line). */

    /**
     * Wires the gutter Run column (Java 25 compact source files): {@code enabled} reserves the fixed-width
     * slot on every row while this is a compact source file (so the glyph appearing never shifts text),
     * {@code isRunLine} marks the top-level {@code main} line, and {@code onRun} runs the file on a click.
     */
    public void setRunHooks(BooleanSupplier enabled, IntPredicate isRunLine, IntConsumer onRun) {
        setRunHooks(enabled, isRunLine, onRun, null);
    }

    /** As {@link #setRunHooks(BooleanSupplier, IntPredicate, IntConsumer)}, plus {@code extraClassFor} — an
     *  extra CSS class per Run-glyph line (e.g. {@code test-run-marker} to tint a JUnit test ▶), or null. */
    public void setRunHooks(
            BooleanSupplier enabled, IntPredicate isRunLine, IntConsumer onRun, IntFunction<String> extraClassFor) {
        setRunHooks(enabled, isRunLine, onRun, extraClassFor, null);
    }

    /** As above, plus {@code tooltipFor} — hover text per Run-glyph line (e.g. "Run test method foo"), or null. */
    public void setRunHooks(
            BooleanSupplier enabled,
            IntPredicate isRunLine,
            IntConsumer onRun,
            IntFunction<String> extraClassFor,
            IntFunction<String> tooltipFor) {
        this.runEnabled = enabled == null ? () -> false : enabled;
        this.isRunLine = isRunLine == null ? i -> false : isRunLine;
        this.onRun = onRun == null ? i -> {} : onRun;
        this.runExtraClass = extraClassFor == null ? i -> null : extraClassFor;
        this.runTooltip = tooltipFor == null ? i -> null : tooltipFor;
    }

    /**
     * Wires the gutter breakpoint column (leftmost): {@code enabled} reserves the fixed-width strip on
     * every row while debugging is on, {@code isBreakpoint} draws the red dot, {@code classFor} gives an
     * extra glyph class (conditional/logpoint/disabled) or {@code null}, and {@code onToggle} fires when
     * the user clicks the strip on a line.
     */
    public void setBreakpointHooks(
            BooleanSupplier enabled, IntPredicate isBreakpoint, IntFunction<String> classFor, IntConsumer onToggle) {
        this.breakpointsEnabled = enabled == null ? () -> false : enabled;
        this.isBreakpoint = isBreakpoint == null ? i -> false : isBreakpoint;
        this.breakpointClass = classFor == null ? i -> null : classFor;
        this.onBreakpointToggle = onToggle == null ? i -> {} : onToggle;
    }

    /** Supplies the hover text of a line's breakpoint glyph ({@code null} = none). */
    public void setBreakpointTooltip(IntFunction<String> tooltipFor) {
        this.breakpointTooltip = tooltipFor == null ? i -> null : tooltipFor;
    }

    /**
     * Wires the Git change-bar gutter column: {@code enabled} decides whether the (fixed-width) slot is
     * reserved on every row, and {@code classFor} returns the CSS style class for a line's bar
     * (e.g. {@code git-modified}) or {@code null} when the line is unchanged.
     */
    public void setChangeHook(BooleanSupplier enabled, IntFunction<String> classFor, IntFunction<String> tooltipFor) {
        this.changeBarsEnabled = enabled == null ? () -> false : enabled;
        this.changeClass = classFor == null ? i -> null : classFor;
        this.changeTooltip = tooltipFor == null ? i -> null : tooltipFor;
    }

    /**
     * Wires the IntelliJ-style blame "Annotate" gutter column (leftmost): {@code enabled} reserves the
     * fixed-width annotation slot on every row while blame is on, {@code infoFor} supplies a line's
     * author/date/tooltip/heatmap-bg/hash (or {@code null} for a blank row), {@code columnWidth} is the
     * stable column width (so line numbers don't jitter as rows recycle), and {@code onClick} shows that
     * line's commit when the annotation is clicked.
     */
    public void setBlameHooks(
            BooleanSupplier enabled, IntFunction<BlameInfo> infoFor, DoubleSupplier columnWidth, IntConsumer onClick) {
        this.blameEnabled = enabled == null ? () -> false : enabled;
        this.blameInfo = infoFor == null ? i -> null : infoFor;
        this.blameColumnWidth = columnWidth == null ? () -> 0 : columnWidth;
        this.onBlameClick = onClick == null ? i -> {} : onClick;
    }

    public Optional<Region> regionStartingAt(int line) {
        return Optional.ofNullable(byStart.get(line));
    }

    /** The foldable regions detected in the current text, in document order. */
    public List<Region> regions() {
        return regions;
    }

    // --- keeping a collapsed fold whole through edits ----------------------------------------------
    //
    // A fold is nothing but the `collapse` paragraph style on its body, and "collapsed" is inferred from the
    // paragraph after the header. So an edit that separates the header from its hidden run — a line break
    // typed at the header's end, the header line deleted or joined, a visible line joined INTO the run —
    // left text in the document that was not drawn and that no chevron or fold command could reveal.
    // Two rules keep that from happening: an edit that adds or removes a line break on a collapsed header
    // or in its hidden run expands that fold, and after the next region detection any hidden run whose
    // header no longer starts a region is expanded too (the header was edited into something else).

    /** Stands in for a line break inside a collapsed fold in {@link #linesAsUnits}. Never typed; a document
     *  that contains it is simply not masked. */
    static final char HIDDEN_BREAK = '\u0000';

    private boolean orphanCheckPending;

    /**
     * The last paragraph of the hidden run under the collapsed header {@code par}, or {@code par} itself
     * when nothing is hidden under it — so a line command can take the header together with its body.
     */
    public int hiddenRunEnd(int par) {
        int n = area.getParagraphs().size();
        int q = par;
        if (par >= 0 && par < n && !area.isFolded(par)) {
            while (q + 1 < n && area.isFolded(q + 1)) {
                q++;
            }
        }
        return q;
    }

    /**
     * {@code text} as the pure line commands (kill line, duplicate, move, transpose) should see it: a
     * collapsed header and its hidden body are ONE line, as they are on screen. The line breaks inside a
     * fold become {@link #HIDDEN_BREAK}, which keeps every offset unchanged; pass the edit's replacement
     * through {@link #unmask}. Returns {@code text} itself when no collapsed fold is at, directly above or
     * directly below the caret's line {@code par} — the only folds such a command can reach.
     */
    public String linesAsUnits(String text, int par) {
        int n = area.getParagraphs().size();
        boolean near = false;
        for (int p = Math.max(0, par - 1); p <= par + 2 && p < n && !near; p++) {
            near = area.isFolded(p);
        }
        if (!near || text.indexOf(HIDDEN_BREAK) >= 0) {
            return text;
        }
        char[] chars = text.toCharArray();
        int line = 0;
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '\n' && ++line < n && area.isFolded(line)) {
                chars[i] = HIDDEN_BREAK;
            }
        }
        return new String(chars);
    }

    /** Undoes {@link #linesAsUnits} on an edit's replacement text. */
    public static String unmask(String replacement) {
        return replacement.indexOf(HIDDEN_BREAK) < 0 ? replacement : replacement.replace(HIDDEN_BREAK, '\n');
    }

    /**
     * Expands the collapsed fold headed by the paragraph containing {@code offset} (if any) and returns
     * the end offset of what was its last hidden line; {@code offset} itself when that paragraph heads no
     * collapsed fold. For a line command that rewrites every line of the unit in place (comment).
     */
    public int expandHeaderAt(int offset) {
        int par = area.offsetToPosition(offset, Bias.Forward).getMajor();
        int end = hiddenRunEnd(par);
        if (end == par) {
            return offset;
        }
        unfold(par);
        return area.getAbsolutePosition(end, area.getParagraphLength(end));
    }

    /**
     * The span a whole-line rewrite (comment) should cover for the selection {@code [selStart, selEnd]}:
     * unchanged, unless the selection ends on a collapsed header — then that fold is expanded and the span
     * runs from the start of the selection's first line to the end of the fold's last line. A non-empty
     * selection that ends at the very start of a line does not include that line.
     */
    public int[] expandHeaderSpan(int selStart, int selEnd) {
        var pos = area.offsetToPosition(selEnd, Bias.Forward);
        int end = selEnd > selStart && pos.getMinor() == 0 ? selEnd : expandHeaderAt(selEnd);
        if (end == selEnd) {
            return new int[] {selStart, selEnd};
        }
        return new int[] {
            selStart - area.offsetToPosition(selStart, Bias.Forward).getMinor(), end
        };
    }

    /** Per text change (so: per keystroke) — one position lookup and at most three paragraph-style reads. */
    private void noteEditNearFold(int position, String inserted, String removed) {
        int n = area.getParagraphs().size();
        var at = area.offsetToPosition(Math.min(position, area.getLength()), Bias.Forward);
        int first = at.getMajor();
        boolean insertedBreak = inserted.indexOf('\n') >= 0;
        int last = insertedBreak
                ? area.offsetToPosition(Math.min(position + inserted.length(), area.getLength()), Bias.Forward)
                        .getMajor()
                : first;
        if (!area.isFolded(first) && !area.isFolded(last) && !(last + 1 < n && area.isFolded(last + 1))) {
            return;
        }
        orphanCheckPending = true; // a same-line edit: the header may stop being a region start
        // Lines inserted in front of a header (at its column 0) push it down whole, body attached.
        boolean pushedDown = removed.isEmpty() && at.getMinor() == 0 && !area.isFolded(first) && !area.isFolded(last);
        if ((insertedBreak || removed.indexOf('\n') >= 0) && !pushedDown) {
            // Deferred: changing paragraph styles from inside the change notification would re-enter the
            // document (and, during an undo, the undo manager).
            Platform.runLater(() -> expandRunsAt(first, last, last + 1));
        }
    }

    private void expandRunsAt(int... pars) {
        boolean changed = false;
        for (int par : pars) {
            if (par >= 0 && par < area.getParagraphs().size() && area.isFolded(par)) {
                expandRun(par);
                changed = true;
            }
        }
        if (changed && !restoring) {
            foldStateChanged();
        }
    }

    /** Reveals the run of hidden paragraphs containing {@code par}, whether or not anything heads it. */
    private void expandRun(int par) {
        int start = par;
        while (start > 0 && area.isFolded(start - 1)) {
            start--;
        }
        if (start > 0) {
            area.unfoldParagraphs(start - 1);
            shadeHeader(start - 1, false);
            return;
        }
        // A run that begins the document has no paragraph above it to unfold from.
        for (int p = 0; p < area.getParagraphs().size() && area.isFolded(p); p++) {
            List<String> style = new ArrayList<>(area.getParagraph(p).getParagraphStyle());
            style.remove("collapse");
            area.setParagraphStyle(p, style);
        }
    }

    /** Expands every hidden run whose header is not (any longer) the start of a region. */
    private void expandOrphanRuns() {
        boolean changed = false;
        for (int p = 0; p < area.getParagraphs().size(); p++) {
            if (area.isFolded(p) && (p == 0 || (!area.isFolded(p - 1) && !byStart.containsKey(p - 1)))) {
                expandRun(p);
                changed = true;
            }
        }
        if (changed && !restoring) {
            foldStateChanged();
        }
    }

    /** True if the region whose header is {@code startLine} is currently collapsed. */
    public boolean isCollapsed(int startLine) {
        int next = startLine + 1;
        return next < area.getParagraphs().size() && area.isFolded(next);
    }

    public void fold(Region region) {
        hidePreview();
        int topPar = firstVisiblePar();
        int caret = area.getCaretPosition();
        int anchor = area.getAnchor();
        int bodyStart = area.getAbsolutePosition(region.startLine(), area.getParagraphLength(region.startLine()));
        int bodyEnd = area.getAbsolutePosition(region.endLine(), area.getParagraphLength(region.endLine()));

        area.foldParagraphs(region.startLine(), region.endLine());
        shadeHeader(region.startLine(), true);

        // foldParagraphs() moves the caret to the fold header; restore it unless it was in the
        // now-hidden body, so folding a block elsewhere doesn't relocate the user's cursor — nor drop the
        // selection it ends, unless that started in the body.
        if (caret <= bodyStart || caret > bodyEnd) {
            int to = Math.min(caret, area.getLength());
            area.selectRange(anchor <= bodyStart || anchor > bodyEnd ? Math.min(anchor, area.getLength()) : to, to);
        }
        restoreViewport(topPar);
        if (!restoring) {
            foldStateChanged();
        }
    }

    public void unfold(int startLine) {
        hidePreview();
        int topPar = firstVisiblePar();
        int anchor = area.getAnchor();
        int caret = area.getCaretPosition();
        area.unfoldParagraphs(startLine);
        if (anchor != caret) {
            area.selectRange(anchor, caret); // unfoldParagraphs() collapses the selection, as folding does
        }
        shadeHeader(startLine, false);
        restoreViewport(topPar);
        if (!restoring) {
            foldStateChanged();
        }
    }

    /**
     * Reveals {@code line} if it is hidden inside one or more collapsed regions, unfolding from the
     * outermost in. No-op if the line is already visible. (Used by Go to Line so a folded target is
     * shown rather than silently scrolled to a hidden paragraph.)
     */
    public void unfoldContaining(int line) {
        settleCollapsed();
        int n = area.getParagraphs().size();
        if (line < 0 || line >= n || !area.isFolded(line)) {
            return;
        }
        boolean changed = false;
        // Each pass unfolds the innermost-visible region above the line; repeat for nested folds.
        int guard = 0;
        while (line < area.getParagraphs().size() && area.isFolded(line) && guard++ < n) {
            int header = line;
            while (header > 0 && area.isFolded(header)) {
                header--;
            }
            area.unfoldParagraphs(header);
            shadeHeader(header, false);
            changed = true;
        }
        if (changed && !restoring) {
            foldStateChanged();
        }
    }

    public void foldAll() {
        settleRegions();
        int topPar = firstVisiblePar();
        for (Region r : regions) {
            if (!isCollapsed(r.startLine())) {
                area.foldParagraphs(r.startLine(), r.endLine());
                shadeHeader(r.startLine(), true);
            }
        }
        restoreViewport(topPar);
        if (!restoring) {
            foldStateChanged();
        }
    }

    public void unfoldAll() {
        settleRegions();
        if (!hasCollapsedParagraph()) {
            return; // nothing folded: skip firstVisiblePar() (a forced VirtualFlow layout) and the shade sweep
        }
        int topPar = firstVisiblePar();
        int n = area.getParagraphs().size();
        for (int p = 0; p + 1 < n; p++) {
            if (!area.isFolded(p) && area.isFolded(p + 1)) {
                area.unfoldParagraphs(p);
            }
        }
        for (Region r : regions) {
            shadeHeader(r.startLine(), false);
        }
        restoreViewport(topPar);
        if (!restoring) {
            foldStateChanged();
        }
    }

    /** Whether any paragraph is currently collapsed. A plain style read per paragraph — no layout, unlike
     *  {@link #firstVisiblePar()} — so it is safe to use as an early-out on a hot path. */
    private boolean hasCollapsedParagraph() {
        int n = area.getParagraphs().size();
        for (int p = 0; p < n; p++) {
            if (area.isFolded(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Folds everything except the regions containing {@code line} (VS Code's {@code foldAllExcept}):
     * the top-level regions that don't contain it, plus — inside each ancestor kept open — that
     * ancestor's direct children that don't contain it. Deliberately not "every non-containing region
     * recursively": folding the top-most excluded region already hides its interior, and recording
     * thousands of nested collapses in a big file bloats the persisted fold state for no visible change.
     */
    public void foldAllExcept(int line) {
        settleRegions();
        int topPar = firstVisiblePar();
        for (Region r : regions) {
            if (r.startLine() <= line && line <= r.endLine()) {
                continue; // an ancestor of the caret — stays open
            }
            Region parent = FoldTree.parentFold(regions, r.startLine());
            // Fold r only if every ancestor it has contains the line (i.e. r is a top-most excluded
            // region); an excluded ancestor will be folded itself and hides r anyway.
            boolean topMostExcluded = true;
            while (parent != null) {
                if (!(parent.startLine() <= line && line <= parent.endLine())) {
                    topMostExcluded = false;
                    break;
                }
                parent = FoldTree.parentFold(regions, parent.startLine());
            }
            if (topMostExcluded && !isCollapsed(r.startLine())) {
                area.foldParagraphs(r.startLine(), r.endLine());
                shadeHeader(r.startLine(), true);
            }
        }
        restoreViewport(topPar);
        if (!restoring) {
            foldStateChanged();
        }
    }

    /**
     * Unfolds every collapsed region except those containing {@code line} (VS Code's
     * {@code unfoldAllExcept} — the header line counts as contained, which is where the caret sits on a
     * collapsed fold, so "except the fold I'm on" reads naturally).
     */
    public void unfoldAllExcept(int line) {
        settleRegions();
        int topPar = firstVisiblePar();
        boolean changed = false;
        for (Integer s : collapsedStartLines()) {
            // collapsedStartLines also reports regions merely HIDDEN inside a folded ancestor (that is
            // what lets nested fold state persist). Unfolding such a phantom header would rip the
            // ancestor open instead — only a fold whose own header line is visible is really "a fold on
            // screen" this command should touch.
            if (area.isFolded(s)) {
                continue;
            }
            Region r = byStart.get(s);
            if (r != null && r.startLine() <= line && line <= r.endLine()) {
                continue;
            }
            area.unfoldParagraphs(s);
            shadeHeader(s, false);
            changed = true;
        }
        restoreViewport(topPar);
        if (changed && !restoring) {
            foldStateChanged();
        }
    }

    /** {@link #foldAllExcept(int)} at the caret's line. */
    public void foldAllExceptCaret() {
        foldAllExcept(caretLine.getAsInt());
    }

    /** {@link #unfoldAllExcept(int)} at the caret's line. */
    public void unfoldAllExceptCaret() {
        unfoldAllExcept(caretLine.getAsInt());
    }

    /** Folds every multi-line block comment (VS Code's {@code foldAllBlockComments}). Returns the count. */
    public int foldAllBlockComments() {
        settleRegions();
        return foldExactly(FoldRegions.blockComments(textSnapshot.get(), language));
    }

    /** Folds every {@code #region} marker region (VS Code's {@code foldAllMarkerRegions}). Returns the count. */
    public int foldAllMarkerRegions() {
        settleRegions();
        return foldExactly(FoldRegions.markers(textSnapshot.get(), language));
    }

    /** Unfolds every {@code #region} marker region ({@code unfoldAllMarkerRegions}). Returns the count. */
    public int unfoldAllMarkerRegions() {
        settleRegions();
        int topPar = firstVisiblePar();
        int n = 0;
        for (Region r : FoldRegions.markers(textSnapshot.get(), language)) {
            // Same phantom-header guard as unfoldAllExcept: a marker region hidden inside a folded
            // ancestor reads as collapsed, and unfolding it would rip the ancestor open.
            if (isCollapsed(r.startLine()) && !area.isFolded(r.startLine())) {
                area.unfoldParagraphs(r.startLine());
                shadeHeader(r.startLine(), false);
                n++;
            }
        }
        restoreViewport(topPar);
        if (n > 0 && !restoring) {
            foldStateChanged();
        }
        return n;
    }

    /** Folds exactly the given regions (a kind-specific detector's output), skipping collapsed ones. */
    private int foldExactly(List<Region> toFold) {
        int topPar = firstVisiblePar();
        int n = 0;
        for (Region r : toFold) {
            if (!isCollapsed(r.startLine())) {
                area.foldParagraphs(r.startLine(), r.endLine());
                shadeHeader(r.startLine(), true);
                n++;
            }
        }
        restoreViewport(topPar);
        if (n > 0 && !restoring) {
            foldStateChanged();
        }
        return n;
    }

    /** Collapses the innermost expanded foldable region around the caret; no-op if none applies. */
    public void foldAtCaret() {
        settleRegions();
        int line = caretLine.getAsInt();
        Region target = null; // innermost (largest startLine) containing, expanded region
        for (Region r : regions) {
            if (r.startLine() <= line
                    && line <= r.endLine()
                    && !isCollapsed(r.startLine())
                    && (target == null || r.startLine() > target.startLine())) {
                target = r;
            }
        }
        if (target != null) {
            fold(target);
        }
    }

    /** Expands the collapsed region at the caret (its header line, or the innermost containing it). */
    public void unfoldAtCaret() {
        settleRegions();
        int line = caretLine.getAsInt();
        Region atHeader = byStart.get(line);
        if (atHeader != null && isCollapsed(atHeader.startLine())) {
            unfold(atHeader.startLine());
            return;
        }
        Region target = null; // innermost containing, collapsed region
        for (Region r : regions) {
            if (r.startLine() <= line
                    && line <= r.endLine()
                    && isCollapsed(r.startLine())
                    && (target == null || r.startLine() > target.startLine())) {
                target = r;
            }
        }
        if (target != null) {
            unfold(target.startLine());
        }
    }

    /** Toggles the region at the caret: expands it if collapsed, otherwise collapses it. */
    public void toggleFoldAtCaret() {
        settleRegions();
        int line = caretLine.getAsInt();
        boolean collapsedHere = false;
        for (Region r : regions) {
            if (r.startLine() <= line && line <= r.endLine() && isCollapsed(r.startLine())) {
                collapsedHere = true;
                break;
            }
        }
        if (collapsedHere) {
            unfoldAtCaret();
        } else {
            foldAtCaret();
        }
    }

    /**
     * Folds every region at fold {@code level} (1-based, VS Code {@code editor.foldLevel1..7}): the whole
     * document is expanded first, then each region at depth {@code level - 1} is collapsed — so shallower
     * levels stay open and deeper regions are hidden inside the folds (revealing them when unfolded).
     */
    public void foldLevel(int level) {
        settleRegions();
        int topPar = firstVisiblePar();
        unfoldEverythingNoFire();
        for (Region r : FoldTree.atLevel(regions, level)) {
            area.foldParagraphs(r.startLine(), r.endLine());
            shadeHeader(r.startLine(), true);
        }
        restoreViewport(topPar);
        if (!restoring) {
            foldStateChanged();
        }
    }

    /** Collapses the innermost region around the caret <b>and</b> every region nested inside it. */
    public void foldRecursivelyAtCaret() {
        settleRegions();
        Region target = FoldTree.innermostContaining(regions, caretLine.getAsInt());
        if (target == null) {
            return;
        }
        int topPar = firstVisiblePar();
        // Deepest-first: each region is still visible (its parent not yet folded) when we collapse it, so
        // the nested folds are genuinely recorded — unlike foldAll, which only truly folds the outermost.
        List<Region> toFold = new ArrayList<>(FoldTree.descendantsOf(regions, target));
        toFold.add(target);
        toFold.sort((a, b) -> FoldTree.depthOf(regions, b) - FoldTree.depthOf(regions, a));
        for (Region r : toFold) {
            if (!isCollapsed(r.startLine())) {
                area.foldParagraphs(r.startLine(), r.endLine());
                shadeHeader(r.startLine(), true);
            }
        }
        restoreViewport(topPar);
        if (!restoring) {
            foldStateChanged();
        }
    }

    /** Expands the collapsed region around the caret <b>and</b> every region nested inside it. */
    public void unfoldRecursivelyAtCaret() {
        settleRegions();
        int line = caretLine.getAsInt();
        Region target = byStart.get(line);
        if (target == null || !isCollapsed(target.startLine())) {
            target = null;
            for (Region r : regions) { // innermost collapsed region containing the caret
                if (r.startLine() <= line
                        && line <= r.endLine()
                        && isCollapsed(r.startLine())
                        && (target == null || r.startLine() > target.startLine())) {
                    target = r;
                }
            }
        }
        if (target == null) {
            return;
        }
        int topPar = firstVisiblePar();
        // Shallowest-first: revealing the parent exposes each child header, which is then itself a visible
        // collapsed header we can unfold (the detector lists regions innermost-first, so we must re-order).
        Region root = target;
        List<Region> toUnfold = new ArrayList<>(FoldTree.descendantsOf(regions, root));
        toUnfold.add(root);
        toUnfold.sort((a, b) -> FoldTree.depthOf(regions, a) - FoldTree.depthOf(regions, b));
        for (Region r : toUnfold) {
            if (isCollapsed(r.startLine())) {
                area.unfoldParagraphs(r.startLine());
                shadeHeader(r.startLine(), false);
            }
        }
        restoreViewport(topPar);
        if (!restoring) {
            foldStateChanged();
        }
    }

    /** Reveals the whole document without firing the fold-state callback (used by {@link #foldLevel}). */
    private void unfoldEverythingNoFire() {
        int n = area.getParagraphs().size();
        for (int p = 0; p + 1 < n; p++) {
            if (!area.isFolded(p) && area.isFolded(p + 1)) {
                area.unfoldParagraphs(p);
            }
        }
        for (Region r : regions) {
            shadeHeader(r.startLine(), false);
        }
    }

    private int firstVisiblePar() {
        try {
            return area.firstVisibleParToAllParIndex();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Re-anchors the viewport to the paragraph that was at the top, keeping the view from jumping. */
    private void restoreViewport(int topPar) {
        if (topPar < 0) {
            return;
        }
        int target = nearestVisible(topPar);
        Platform.runLater(() -> {
            try {
                area.showParagraphAtTop(target);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
    }

    /** Nearest non-folded paragraph at or above {@code par}. */
    private int nearestVisible(int par) {
        int p = Math.max(0, Math.min(par, area.getParagraphs().size() - 1));
        while (p > 0 && area.isFolded(p)) {
            p--;
        }
        return p;
    }

    /** Header line indices of every currently collapsed region, for persistence. */
    public List<Integer> collapsedStartLines() {
        settleCollapsed();
        List<Integer> out = new ArrayList<>();
        for (Region r : regions) {
            if (isCollapsed(r.startLine())) {
                out.add(r.startLine());
            }
        }
        return out;
    }

    /** Re-applies previously saved collapsed regions (by header line) without firing change events. */
    public void applyCollapsedStartLines(List<Integer> startLines) {
        if (startLines == null || startLines.isEmpty()) {
            return;
        }
        if (deferRestore()) {
            restoreDeferred(startLines);
            return;
        }
        restoring = true;
        try {
            recompute();
        } finally {
            restoring = false;
        }
        collapseRestored(startLines);
    }

    /**
     * Restores manual ranges and collapsed headers with one region recomputation instead of two. For a long
     * document ({@link #DEFERRED_RESTORE_CHARS}) that recomputation runs in the background and the collapsed
     * headers are applied when its regions arrive; anything that needs them sooner computes them on demand.
     */
    public void restore(List<Region> manual, List<Integer> collapsedStartLines) {
        manualRegions = manual == null || manual.isEmpty() ? List.of() : List.copyOf(manual);
        if (deferRestore()) {
            restoreDeferred(collapsedStartLines);
            return;
        }
        recompute();
        if (collapsedStartLines != null && !collapsedStartLines.isEmpty()) {
            collapseRestored(collapsedStartLines);
        }
    }

    private void collapseRestored(List<Integer> startLines) {
        restoring = true;
        try {
            for (Region r : regions) {
                if (startLines.contains(r.startLine()) && !isCollapsed(r.startLine())) {
                    area.foldParagraphs(r.startLine(), r.endLine());
                    shadeHeader(r.startLine(), true);
                }
            }
        } finally {
            restoring = false;
        }
    }

    /**
     * Shades (or clears) the folded region's header line so a collapsed block is visible at a glance.
     *
     * <p>No-ops when the line already carries the wanted style. {@code setParagraphStyle} is a styled-document
     * mutation (it rebuilds the paragraph and emits a rich change), not a cheap property write, and the
     * fold-all/unfold-all commands clear the shade on <em>every</em> region in the file — thousands in a large
     * source file, almost none of which were ever shaded. Measured on {@code EditorBuffer.java}, the unguarded
     * clear was the single biggest cost of {@code unfoldAll} (and so of entering Simple UI mode, which unfolds
     * every open buffer).
     */
    private void shadeHeader(int line, boolean folded) {
        if (line < 0 || line >= area.getParagraphs().size()) {
            return;
        }
        Collection<String> want = folded ? List.of("fold-header-line") : List.of();
        Collection<String> have = area.getParagraph(line).getParagraphStyle();
        if (have != null && have.size() == want.size() && have.containsAll(want)) {
            return;
        }
        area.setParagraphStyle(line, want);
    }

    /**
     * A gutter graphic factory: an optional right-aligned line number plus a fold chevron on lines
     * that begin a foldable region. Clicking the chevron toggles that region.
     */
    public IntFunction<Node> gutterFactory(boolean showLineNumbers) {
        lastLineDigits = digits(area.getParagraphs().size());
        return idx -> buildGutter(idx, showLineNumbers);
    }

    /**
     * Keeps a press on a gutter control from also being a press on the text. The gutter is a paragraph
     * graphic <em>inside</em> the area, so a press that bubbles out of it reaches the area's own handlers,
     * which move the caret to that row and drop the selection and any extra carets — toggling a breakpoint
     * or a fold must not cost you your place. The click itself is a separate event and still arrives. One
     * shared handler: gutter rows are rebuilt as cells recycle.
     */
    private static final javafx.event.EventHandler<javafx.scene.input.MouseEvent> OWN_POINTER = e -> {
        var type = e.getEventType();
        if (type == javafx.scene.input.MouseEvent.MOUSE_PRESSED) {
            // The press it swallows was also what focused the editor; keep that, in whichever view this is.
            for (Node n = (Node) e.getSource(); n != null; n = n.getParent()) {
                if (n instanceof CodeArea view) {
                    view.requestFocus();
                    break;
                }
            }
        }
        if (type == javafx.scene.input.MouseEvent.MOUSE_PRESSED
                || type == javafx.scene.input.MouseEvent.MOUSE_DRAGGED
                || type == javafx.scene.input.MouseEvent.MOUSE_RELEASED
                || type == javafx.scene.input.MouseEvent.DRAG_DETECTED) {
            e.consume();
        }
    };

    private static void ownPointer(Node control) {
        control.addEventHandler(javafx.scene.input.MouseEvent.ANY, OWN_POINTER);
    }

    private Node buildGutter(int idx, boolean showLineNumbers) {
        HBox box = new HBox();
        box.getStyleClass().add("fold-gutter");
        box.setAlignment(Pos.CENTER_RIGHT);
        // A folded paragraph's cell is laid out at zero height, but the line-number Label doesn't clip
        // to it, so the hidden lines' numbers overflow and stack into a smear on the fold-header row.
        // Clip the gutter to its own bounds so a collapsed (0-height) cell shows nothing.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(box.widthProperty());
        clip.heightProperty().bind(box.heightProperty());
        box.setClip(clip);
        // The gutter itself is deliberately NOT clickable: a whole-gutter click used to toggle a bookmark,
        // which misfired constantly on the narrow breakpoint / Run targets sitting inside it (a slightly-off
        // click on the ▶ or the breakpoint strip added a stray bookmark instead). Bookmarks are added from
        // the command palette (`bookmarks.toggle`) or the editor right-click menu; only the individual
        // slots below (breakpoint, Run, blame, fold chevron) handle clicks, each on its own glyph/strip.

        // Blame "Annotate" column (leftmost, IntelliJ-style): a fixed-width per-line author + date with an
        // age-heatmap background tint, a hover tooltip (full commit), and click → show that line's commit.
        // The slot is reserved on every row while blame is on, so toggling it shifts the editor right as a
        // block (like IntelliJ) and the line numbers stay aligned.
        if (blameEnabled.getAsBoolean()) {
            box.getChildren().add(buildBlameSlot(idx));
        }

        // Breakpoint strip (leftmost), reserved on every row only while debugging is enabled. Clicking
        // anywhere in the strip toggles a breakpoint. The red dot is drawn only on breakpointed lines.
        if (breakpointsEnabled.getAsBoolean()) {
            StackPane bpSlot = new StackPane();
            bpSlot.getStyleClass().add("breakpoint-slot");
            bpSlot.setMinWidth(BREAKPOINT_SLOT_WIDTH);
            bpSlot.setPrefWidth(BREAKPOINT_SLOT_WIDTH);
            bpSlot.setMaxWidth(BREAKPOINT_SLOT_WIDTH);
            bpSlot.setMaxHeight(Double.MAX_VALUE);
            bpSlot.setCursor(Cursor.HAND);
            if (isBreakpoint.test(idx)) {
                bpSlot.getChildren().add(breakpointMarker(breakpointClass.apply(idx)));
                String bpTip = breakpointTooltip.apply(idx);
                if (bpTip != null && !bpTip.isEmpty()) {
                    LazyTooltip.install(bpSlot, () -> bpTip);
                }
            }
            ownPointer(bpSlot);
            bpSlot.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    onBreakpointToggle.accept(idx);
                    e.consume();
                }
            });
            box.getChildren().add(bpSlot);
        }

        // A fixed-width bookmark slot is reserved on EVERY row, so toggling a bookmark only fills/empties
        // the slot and never changes the gutter width — which would otherwise shift that line's text
        // indentation rightward (the paragraph graphic's width is the text's left inset). The glyph
        // itself is created only for bookmarked lines, so unbookmarked rows allocate just an empty slot.
        StackPane bookmarkSlot = new StackPane();
        bookmarkSlot.getStyleClass().add("bookmark-slot");
        bookmarkSlot.setMinWidth(BOOKMARK_SLOT_WIDTH);
        bookmarkSlot.setPrefWidth(BOOKMARK_SLOT_WIDTH);
        bookmarkSlot.setMaxWidth(BOOKMARK_SLOT_WIDTH);
        if (isBookmarked.test(idx)) {
            bookmarkSlot.getChildren().add(bookmarkMarker());
        }
        box.getChildren().add(bookmarkSlot);

        // (Personal-Notes markers are drawn inline at the note's start by NoteHighlightOverlay, not in the
        // gutter — see EditorBuffer's note overlay.)

        // Run slot (compact source files only): sits just to the LEFT of the line number, with a clickable
        // green play glyph on the top-level main line — IntelliJ-style. Reserved per-row (like the bookmark
        // slot) so the glyph never shifts that line's text; only present for compact files, so other files'
        // gutters are unaffected.
        if (runEnabled.getAsBoolean()) {
            StackPane runSlot = new StackPane();
            runSlot.getStyleClass().add("run-slot");
            runSlot.setAlignment(Pos.CENTER_RIGHT); // hug the line number rather than centering with dead space
            runSlot.setMinWidth(RUN_SLOT_WIDTH);
            runSlot.setPrefWidth(RUN_SLOT_WIDTH);
            runSlot.setMaxWidth(RUN_SLOT_WIDTH);
            if (isRunLine.test(idx)) {
                Node marker = runGlyph(runExtraClass.apply(idx));
                marker.setCursor(Cursor.HAND);
                String runTip = runTooltip.apply(idx);
                if (runTip != null && !runTip.isBlank()) {
                    LazyTooltip.install(marker, () -> runTip, null, 300);
                }
                final int runIdx = idx;
                ownPointer(marker);
                marker.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) {
                        onRun.accept(runIdx);
                        e.consume();
                    }
                });
                runSlot.getChildren().add(marker);
            }
            box.getChildren().add(runSlot);
        }

        if (showLineNumbers) {
            Label lineNo = new Label();
            lineNo.getStyleClass().add("lineno");
            lineNo.setAlignment(Pos.CENTER_RIGHT);
            // Set the text directly from the live (O(1)) paragraph count rather than a per-row reactive
            // binding — the binding's subscribe/unsubscribe churn as cells recycle was a measurable cost
            // on every scroll (a layout-forced scroll sweep over README.md was ~12-21% faster without it).
            // Padding re-pads via repadVisibleLineNumbers() only when the digit width of the line count
            // actually changes (a power-of-10 crossing — rare), not per row.
            lineNo.setText(formatLineNo(idx + 1, area.getParagraphs().size()));
            box.getChildren().add(lineNo);
        }

        // The chevron column is reserved on every row, but only a fold-start line gets the Label: a Label is
        // a Control with a skin and a Text child, and nine rows in ten would build one to show a space. The
        // rest get a bare Region that the stylesheet gives the same width (.fold-chevron-slot).
        if (regionStartingAt(idx).isEmpty()) {
            javafx.scene.layout.Region slot = new javafx.scene.layout.Region();
            slot.getStyleClass().add("fold-chevron-slot");
            box.getChildren().add(slot);
        } else {
            Label chevron = new Label(isCollapsed(idx) ? "▸" : "▾"); // ▸ / ▾
            chevron.getStyleClass().add("fold-chevron");
            chevron.setCursor(Cursor.HAND);
            ownPointer(chevron);
            chevron.setOnMouseClicked(e -> {
                if (isCollapsed(idx)) {
                    unfold(idx);
                } else {
                    // Resolved now, not when this row was built: a header's graphic is only rebuilt when its
                    // fold-START status changes, so a block that grew since would fold at its old extent.
                    regionStartingAt(idx).ifPresent(this::fold);
                }
                e.consume(); // a fold click is not a text click
            });
            box.getChildren().add(chevron);
        }

        // Git change bar: a thin full-height stripe at the gutter's inner edge (next to the text),
        // IntelliJ-style. The slot is reserved on every row while tracking is on, so a bar
        // appearing/disappearing never shifts the line's text indentation.
        if (changeBarsEnabled.getAsBoolean()) {
            javafx.scene.layout.Region bar = new javafx.scene.layout.Region();
            bar.getStyleClass().add("git-change-bar");
            bar.setMinWidth(CHANGE_SLOT_WIDTH);
            bar.setPrefWidth(CHANGE_SLOT_WIDTH);
            bar.setMaxWidth(CHANGE_SLOT_WIDTH);
            bar.setMaxHeight(Double.MAX_VALUE);
            String cls = changeClass.apply(idx);
            if (cls != null) {
                bar.getStyleClass().add(cls);
            }
            String hunk = changeTooltip.apply(idx);
            if (hunk != null && !hunk.isBlank()) {
                LazyTooltip.install(bar, () -> hunk, "git-diff-tooltip", 300);
            }
            box.getChildren().add(bar);
        }
        return box;
    }

    /**
     * Builds the leftmost blame "Annotate" cell for a row: a fixed-width author (left, ellipsized) + date
     * (right) with an age-heatmap background, a full-commit hover tooltip, and click → show that line's
     * commit. A blank slot of the same width is returned for an empty / not-yet-loaded row so the column
     * width — and thus the line-number alignment — stays constant across rows.
     */
    /**
     * The age-heatmap backgrounds, one per distinct tint. An inline style string is parsed by the CSS engine
     * for every cell that carries it, and blame puts one on every row scrolled into view; the tints are a
     * few dozen values per file, so each is resolved once. FX-thread only.
     */
    private static final java.util.Map<String, Background> BLAME_TINTS = new java.util.HashMap<>();

    /** The cached {@link Background} for a heatmap colour, or {@code null} when it is not a plain colour. */
    static Background blameTint(String web) {
        Background tint = BLAME_TINTS.get(web);
        if (tint == null) {
            try {
                tint = new Background(new BackgroundFill(
                        javafx.scene.paint.Color.web(web), CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY));
            } catch (RuntimeException notAPlainColour) {
                return null;
            }
            if (BLAME_TINTS.size() >= 1024) {
                BLAME_TINTS.clear(); // a different file's range of ages; the cache only needs the current ones
            }
            BLAME_TINTS.put(web, tint);
        }
        return tint;
    }

    private Node buildBlameSlot(int idx) {
        double w = Math.max(0, blameColumnWidth.getAsDouble());
        HBox slot = new HBox();
        slot.getStyleClass().add("blame-slot");
        slot.setMinWidth(w);
        slot.setPrefWidth(w);
        slot.setMaxWidth(w);
        slot.setMaxHeight(Double.MAX_VALUE);
        slot.setAlignment(Pos.CENTER_LEFT);
        BlameInfo info = blameInfo.apply(idx);
        if (info == null || info.isEmpty()) {
            return slot; // reserve the column on blank/unloaded rows so nothing shifts
        }
        if (info.bg() != null && !info.bg().isBlank()) {
            Background tint = blameTint(info.bg());
            if (tint != null) {
                slot.setBackground(tint);
            } else {
                slot.setStyle("-fx-background-color: " + info.bg() + ";"); // not a plain colour: let CSS parse it
            }
        }
        Label author = new Label(info.author());
        author.getStyleClass().add("blame-author");
        author.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(author, Priority.ALWAYS);
        Label date = new Label(info.date());
        date.getStyleClass().add("blame-date");
        slot.getChildren().addAll(author, date);
        if (info.tooltip() != null && !info.tooltip().isBlank()) {
            LazyTooltip.install(slot, info::tooltip, "blame-tooltip", 400);
        }
        slot.setCursor(Cursor.HAND);
        final int blameIdx = idx;
        ownPointer(slot);
        slot.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                onBlameClick.accept(blameIdx);
                e.consume();
            }
        });
        return slot;
    }

    /** A small bookmark glyph for the gutter (Material "bookmark"); colored via the {@code .bookmark-marker}
     *  CSS class (an SVG fill). Display only — it has no click behavior. */
    private Node bookmarkMarker() {
        SVGPath svg = new SVGPath();
        svg.setContent("M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z");
        svg.getStyleClass().add("bookmark-marker");
        svg.setScaleX(0.55);
        svg.setScaleY(0.55);
        return new Group(svg); // Group bounds reflect the scaled glyph, so the gutter stays narrow
    }

    /** A small green play glyph (Material "play_arrow") for the gutter Run marker + the Run menu item;
     *  colored via the {@code .run-marker} CSS class. Returned in a {@link Group} so its bounds reflect
     *  the scaled size and the gutter stays narrow. */
    static Node runGlyph() {
        return runGlyph(null);
    }

    /** {@code extraClass} (e.g. {@code test-run-marker}) tints/distinguishes the glyph; null for the plain ▶. */
    static Node runGlyph(String extraClass) {
        SVGPath svg = new SVGPath();
        svg.setContent(RUN_GLYPH_PATH);
        svg.getStyleClass().add("run-marker");
        if (extraClass != null) {
            svg.getStyleClass().add(extraClass);
        }
        svg.setScaleX(1.217); // 20% bigger than before (1.014) so the Run target is easier to click
        svg.setScaleY(1.217);
        return new Group(svg);
    }

    /** A small filled red dot for the gutter breakpoint marker; colored via {@code .breakpoint-marker}.
     *  {@code extraClass} (e.g. {@code conditional}/{@code logpoint}/{@code disabled}, space-separated when
     *  there are several) tweaks the look. */
    private Node breakpointMarker(String extraClass) {
        SVGPath svg = new SVGPath();
        svg.setContent(BREAKPOINT_GLYPH_PATH);
        svg.getStyleClass().add("breakpoint-marker");
        if (extraClass != null && !extraClass.isEmpty()) {
            for (String one : extraClass.split(" ")) { // a kind and/or the live-session state
                svg.getStyleClass().add("breakpoint-" + one);
            }
        }
        svg.setScaleX(0.5);
        svg.setScaleY(0.5);
        return new Group(svg);
    }

    /**
     * The collapsed region rendered from the editor's already-applied style spans, capped at
     * {@link #PREVIEW_LINES}. Reading those spans is both cheaper and safer than re-tokenizing on the FX
     * thread: TextMate grammars are shared with the background highlighter and are not thread-safe.
     */
    Node foldPreviewGraphic(Region region) {
        int total = area.getParagraphs().size();
        int last = Math.min(region.endLine(), region.startLine() + PREVIEW_LINES - 1);
        VBox lines = new VBox();
        lines.getStyleClass().add("fold-preview-content");
        lines.setStyle("-fold-preview-foreground: " + hex(previewForeground) + ";");
        if (area.getScene() != null) {
            // A Tooltip owns a separate popup scene, so it cannot inherit the editor theme. Copy the live
            // scene's app, syntax, and selected editor-theme stylesheets onto the graphic itself.
            lines.getStylesheets().setAll(area.getScene().getStylesheets());
        }
        for (int p = region.startLine(); p <= last && p < total; p++) {
            lines.getChildren().add(styledLine(p));
        }
        if (region.endLine() > last) {
            lines.getChildren().add(textRun("…", null));
        }
        return lines;
    }

    /** One logical line split into the same styled text runs the editor displays. */
    private HBox styledLine(int paragraph) {
        HBox line = new HBox();
        String raw = area.getParagraph(paragraph).getText();
        StyleSpans<Collection<String>> spans;
        try {
            spans = area.getStyleSpans(paragraph);
        } catch (RuntimeException ex) {
            spans = null; // a transient styling mismatch must not make the fold preview disappear
        }
        if (spans == null) {
            line.getChildren().add(textRun(raw, null));
            return line;
        }
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            if (pos >= raw.length()) {
                break;
            }
            int end = Math.min(raw.length(), pos + span.getLength());
            if (end > pos) {
                line.getChildren().add(textRun(raw.substring(pos, end), span.getStyle()));
            }
            pos = end;
        }
        if (pos < raw.length()) {
            line.getChildren().add(textRun(raw.substring(pos), null));
        }
        if (line.getChildren().isEmpty()) {
            // Preserve blank logical lines. An empty Text has no layout height and would collapse the row.
            line.getChildren().add(textRun("\u200b", null));
        }
        return line;
    }

    private static Text textRun(String value, Collection<String> styles) {
        Text text = new Text(value);
        text.getStyleClass().add("text");
        if (styles != null) {
            text.getStyleClass().addAll(styles);
        }
        return text;
    }

    private static String formatLineNo(int line, int total) {
        return String.format("%1$" + digits(total) + "s", line);
    }

    /**
     * Digit width the line-number gutter pads to. This is the source of the gutter's (and thus each line's
     * text-indent) width, so we clamp it to a {@link #MIN_LINE_DIGITS} floor: a file of &lt;10 lines still
     * reserves 2-digit width, so adding the 10th line no longer widens the gutter and shifts the text
     * rightward. (Crossing a higher power of ten — 99→100 — still steps once, matching VS Code/IntelliJ.)
     */
    private static int digits(int total) {
        return Math.max(MIN_LINE_DIGITS, rawDigits(total));
    }

    /** Number of decimal digits needed for {@code total} (>= 1). */
    private static int rawDigits(int total) {
        return (int) Math.floor(Math.log10(Math.max(1, total))) + 1;
    }
}
