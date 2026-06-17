package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
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

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

import com.editora.editor.FoldRegions.Region;
import org.fxmisc.richtext.CharacterHit;
import org.fxmisc.richtext.CodeArea;
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
    private List<Region> regions = List.of();
    private Map<Integer, Region> byStart = Map.of();
    private String language = "plaintext";
    /** Max number of lines shown in a collapsed region's hover preview. */
    private static final int PREVIEW_LINES = 40;
    /** Fixed width of the gutter's bookmark-marker column, reserved on every row so the gutter (and
     *  thus each line's text indentation) keeps a constant width whether or not the line is bookmarked. */
    private static final double BOOKMARK_SLOT_WIDTH = 12;
    /** Width of the Git change-bar column, reserved on every row while change tracking is on so toggling
     *  a bar never shifts the text indentation (mirrors the bookmark slot). */
    private static final double CHANGE_SLOT_WIDTH = 3;
    /** Fixed width of the Personal-Notes marker column, reserved on every row (mirrors the bookmark slot). */
    private static final double NOTE_SLOT_WIDTH = 12;
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

    /** Whether a line carries a bookmark (drawn as a gutter marker); default none. */
    private IntPredicate isBookmarked = i -> false;
    /** Invoked when the user clicks a line's gutter bookmark marker. */
    private IntConsumer onBookmarkToggle = i -> {};

    /** Whether a line carries a Personal Note (drawn as a gutter marker); default none. */
    private IntPredicate isNoted = i -> false;
    /** Invoked when the user clicks a line's gutter note marker. */
    private IntConsumer onNoteClick = i -> {};

    /** Whether this is a compact source file (reserve the Run slot on every row while true). */
    private BooleanSupplier runEnabled = () -> false;
    /** Whether a line draws the clickable green Run glyph (a single {@code main}, or each .http request). */
    private IntPredicate isRunLine = i -> false;
    /** Invoked with the clicked line when the user clicks a gutter Run glyph. */
    private IntConsumer onRun = i -> {};

    /** Whether debugging is enabled for this buffer (reserve the leftmost breakpoint slot on every row). */
    private BooleanSupplier breakpointsEnabled = () -> false;
    /** Whether a line carries a breakpoint (draws the red dot there). */
    private IntPredicate isBreakpoint = i -> false;
    /** Extra CSS class for a line's breakpoint glyph (e.g. {@code conditional}/{@code logpoint}/disabled),
     *  or {@code null} for a plain breakpoint. */
    private IntFunction<String> breakpointClass = i -> null;
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

    public FoldManager(CodeArea area) {
        this.area = area;
        area.multiPlainChanges().successionEnds(Duration.ofMillis(250)).subscribe(ignore -> recompute());
        installHoverPreview();
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
                linePreview.setText(foldPreview(byStart.get(par)));
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
        Set<Integer> oldStarts = byStart.keySet();
        regions = FoldRegions.detect(area.getText(), language);
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
        int total = area.getParagraphs().size();
        for (int line : changed) {
            if (line >= 0 && line < total) {
                area.recreateParagraphGraphic(line);
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
    }

    /** Recreates the visible rows' gutter graphics so their line numbers re-pad to a new digit width. */
    private void repadVisibleLineNumbers(int total) {
        try {
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            for (int i = first; i <= last; i++) {
                area.recreateParagraphGraphic(i);
            }
        } catch (RuntimeException ignored) {
            // viewport mid-layout — the next build picks up the new width anyway
        }
    }

    /** Wires the bookmark gutter marker: a predicate for which lines are bookmarked + a click handler. */
    public void setBookmarkHooks(IntPredicate isBookmarked, IntConsumer onToggle) {
        this.isBookmarked = isBookmarked == null ? i -> false : isBookmarked;
        this.onBookmarkToggle = onToggle == null ? i -> {} : onToggle;
    }

    /** Wires the Personal-Notes gutter marker: a predicate for which lines carry a note + a click handler
     *  (clicking the marker opens/edits the note on that line). */
    public void setNoteHooks(IntPredicate isNoted, IntConsumer onClick) {
        this.isNoted = isNoted == null ? i -> false : isNoted;
        this.onNoteClick = onClick == null ? i -> {} : onClick;
    }

    /**
     * Wires the gutter Run column (Java 25 compact source files): {@code enabled} reserves the fixed-width
     * slot on every row while this is a compact source file (so the glyph appearing never shifts text),
     * {@code isRunLine} marks the top-level {@code main} line, and {@code onRun} runs the file on a click.
     */
    public void setRunHooks(BooleanSupplier enabled, IntPredicate isRunLine, IntConsumer onRun) {
        this.runEnabled = enabled == null ? () -> false : enabled;
        this.isRunLine = isRunLine == null ? i -> false : isRunLine;
        this.onRun = onRun == null ? i -> {} : onRun;
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

    /** True if the region whose header is {@code startLine} is currently collapsed. */
    public boolean isCollapsed(int startLine) {
        int next = startLine + 1;
        return next < area.getParagraphs().size() && area.isFolded(next);
    }

    public void fold(Region region) {
        hidePreview();
        int topPar = firstVisiblePar();
        int caret = area.getCaretPosition();
        int bodyStart = area.getAbsolutePosition(region.startLine(), area.getParagraphLength(region.startLine()));
        int bodyEnd = area.getAbsolutePosition(region.endLine(), area.getParagraphLength(region.endLine()));

        area.foldParagraphs(region.startLine(), region.endLine());
        shadeHeader(region.startLine(), true);

        // foldParagraphs() moves the caret to the fold header; restore it unless it was in the
        // now-hidden body, so folding a block elsewhere doesn't relocate the user's cursor.
        if (caret <= bodyStart || caret > bodyEnd) {
            area.moveTo(Math.min(caret, area.getLength()));
        }
        restoreViewport(topPar);
        if (!restoring) {
            onFoldStateChanged.run();
        }
    }

    public void unfold(int startLine) {
        hidePreview();
        int topPar = firstVisiblePar();
        area.unfoldParagraphs(startLine);
        shadeHeader(startLine, false);
        restoreViewport(topPar);
        if (!restoring) {
            onFoldStateChanged.run();
        }
    }

    /**
     * Reveals {@code line} if it is hidden inside one or more collapsed regions, unfolding from the
     * outermost in. No-op if the line is already visible. (Used by Go to Line so a folded target is
     * shown rather than silently scrolled to a hidden paragraph.)
     */
    public void unfoldContaining(int line) {
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
            onFoldStateChanged.run();
        }
    }

    public void foldAll() {
        int topPar = firstVisiblePar();
        for (Region r : regions) {
            if (!isCollapsed(r.startLine())) {
                area.foldParagraphs(r.startLine(), r.endLine());
                shadeHeader(r.startLine(), true);
            }
        }
        restoreViewport(topPar);
        if (!restoring) {
            onFoldStateChanged.run();
        }
    }

    public void unfoldAll() {
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
            onFoldStateChanged.run();
        }
    }

    /** Collapses the innermost expanded foldable region around the caret; no-op if none applies. */
    public void foldAtCaret() {
        int line = area.getCurrentParagraph();
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
        int line = area.getCurrentParagraph();
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
        int line = area.getCurrentParagraph();
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
        restoring = true;
        try {
            recompute();
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

    /** Shades (or clears) the folded region's header line so a collapsed block is visible at a glance. */
    private void shadeHeader(int line, boolean folded) {
        if (line >= 0 && line < area.getParagraphs().size()) {
            area.setParagraphStyle(line, folded ? List.of("fold-header-line") : List.of());
        }
    }

    /**
     * A gutter graphic factory: an optional right-aligned line number plus a fold chevron on lines
     * that begin a foldable region. Clicking the chevron toggles that region.
     */
    public IntFunction<Node> gutterFactory(boolean showLineNumbers) {
        lastLineDigits = digits(area.getParagraphs().size());
        return idx -> buildGutter(idx, showLineNumbers);
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
        // Clicking the gutter toggles a bookmark on that line (add, or — handled upstream — confirm a
        // removal). The fold chevron consumes its own click so folding doesn't also toggle a bookmark.
        box.setCursor(Cursor.HAND);
        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                onBookmarkToggle.accept(idx);
            }
        });

        // Blame "Annotate" column (leftmost, IntelliJ-style): a fixed-width per-line author + date with an
        // age-heatmap background tint, a hover tooltip (full commit), and click → show that line's commit.
        // The slot is reserved on every row while blame is on, so toggling it shifts the editor right as a
        // block (like IntelliJ) and the line numbers stay aligned.
        if (blameEnabled.getAsBoolean()) {
            box.getChildren().add(buildBlameSlot(idx));
        }

        // Breakpoint strip (leftmost), reserved on every row only while debugging is enabled. Clicking
        // anywhere in the strip toggles a breakpoint and consumes the event, so it never also triggers the
        // gutter's bookmark-toggle click. The red dot is drawn only on breakpointed lines.
        if (breakpointsEnabled.getAsBoolean()) {
            StackPane bpSlot = new StackPane();
            bpSlot.getStyleClass().add("breakpoint-slot");
            bpSlot.setMinWidth(BREAKPOINT_SLOT_WIDTH);
            bpSlot.setPrefWidth(BREAKPOINT_SLOT_WIDTH);
            bpSlot.setMaxWidth(BREAKPOINT_SLOT_WIDTH);
            bpSlot.setMaxHeight(Double.MAX_VALUE);
            if (isBreakpoint.test(idx)) {
                bpSlot.getChildren().add(breakpointMarker(breakpointClass.apply(idx)));
            }
            bpSlot.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    onBreakpointToggle.accept(idx);
                    e.consume(); // don't also toggle a bookmark via the gutter box click
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

        // Personal-Notes marker slot (reserved on every row, mirroring the bookmark slot). The glyph
        // appears only on noted lines; clicking it opens that line's note (and consumes, so the gutter's
        // bookmark-toggle click doesn't also fire).
        StackPane noteSlot = new StackPane();
        noteSlot.getStyleClass().add("note-slot");
        noteSlot.setMinWidth(NOTE_SLOT_WIDTH);
        noteSlot.setPrefWidth(NOTE_SLOT_WIDTH);
        noteSlot.setMaxWidth(NOTE_SLOT_WIDTH);
        if (isNoted.test(idx)) {
            Node marker = noteMarker();
            marker.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    onNoteClick.accept(idx);
                    e.consume();
                }
            });
            noteSlot.getChildren().add(marker);
        }
        box.getChildren().add(noteSlot);

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
                Node marker = runGlyph();
                marker.setCursor(Cursor.HAND);
                final int runIdx = idx;
                marker.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY) {
                        onRun.accept(runIdx);
                        e.consume(); // don't also toggle a bookmark via the gutter click
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

        Label chevron = new Label(" ");
        chevron.getStyleClass().add("fold-chevron");
        Optional<Region> region = regionStartingAt(idx);
        if (region.isPresent()) {
            boolean collapsed = isCollapsed(idx);
            chevron.setText(collapsed ? "▸" : "▾"); // ▸ / ▾
            chevron.setCursor(Cursor.HAND);
            chevron.setOnMouseClicked(e -> {
                if (isCollapsed(idx)) {
                    unfold(idx);
                } else {
                    fold(region.get());
                }
                e.consume(); // don't let a fold click also toggle a bookmark on the gutter
            });
        }
        box.getChildren().add(chevron);

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
                Tooltip tip = new Tooltip(hunk);
                tip.getStyleClass().add("git-diff-tooltip");
                tip.setShowDelay(javafx.util.Duration.millis(300));
                Tooltip.install(bar, tip);
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
            slot.setStyle("-fx-background-color: " + info.bg() + ";");
        }
        Label author = new Label(info.author());
        author.getStyleClass().add("blame-author");
        author.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(author, Priority.ALWAYS);
        Label date = new Label(info.date());
        date.getStyleClass().add("blame-date");
        slot.getChildren().addAll(author, date);
        if (info.tooltip() != null && !info.tooltip().isBlank()) {
            Tooltip tip = new Tooltip(info.tooltip());
            tip.getStyleClass().add("blame-tooltip");
            tip.setShowDelay(javafx.util.Duration.millis(400));
            Tooltip.install(slot, tip);
        }
        slot.setCursor(Cursor.HAND);
        final int blameIdx = idx;
        slot.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                onBlameClick.accept(blameIdx);
                e.consume(); // don't also toggle a bookmark via the gutter box click
            }
        });
        return slot;
    }

    /** A small bookmark glyph for the gutter (Material "bookmark"); colored via the {@code .bookmark-marker}
     *  CSS class (an SVG fill). Clicking it toggles the bookmark on that line. */
    private Node bookmarkMarker() {
        SVGPath svg = new SVGPath();
        svg.setContent("M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z");
        svg.getStyleClass().add("bookmark-marker");
        svg.setScaleX(0.55);
        svg.setScaleY(0.55);
        // No own click handler: the gutter box handles clicks (so clicking the marker isn't a double toggle).
        return new Group(svg); // Group bounds reflect the scaled glyph, so the gutter stays narrow
    }

    /** A small green play glyph (Material "play_arrow") for the gutter Run marker + the Run menu item;
     *  colored via the {@code .run-marker} CSS class. Returned in a {@link Group} so its bounds reflect
     *  the scaled size and the gutter stays narrow. */
    static Node runGlyph() {
        SVGPath svg = new SVGPath();
        svg.setContent(RUN_GLYPH_PATH);
        svg.getStyleClass().add("run-marker");
        svg.setScaleX(1.014); // 30% larger than before (0.78) so the Run target is easier to click; still fits the slot
        svg.setScaleY(1.014);
        return new Group(svg);
    }

    /** A small filled red dot for the gutter breakpoint marker; colored via {@code .breakpoint-marker}.
     *  {@code extraClass} (e.g. {@code conditional}/{@code logpoint}/{@code disabled}) tweaks the look. */
    private Node breakpointMarker(String extraClass) {
        SVGPath svg = new SVGPath();
        svg.setContent(BREAKPOINT_GLYPH_PATH);
        svg.getStyleClass().add("breakpoint-marker");
        if (extraClass != null && !extraClass.isEmpty()) {
            svg.getStyleClass().add("breakpoint-" + extraClass);
        }
        svg.setScaleX(0.5);
        svg.setScaleY(0.5);
        return new Group(svg);
    }

    /** A small comment/note glyph for the gutter (Material "comment"); colored via {@code .note-marker}. */
    private Node noteMarker() {
        SVGPath svg = new SVGPath();
        svg.setContent("M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z");
        svg.getStyleClass().add("note-marker");
        svg.setScaleX(0.5);
        svg.setScaleY(0.5);
        return new Group(svg);
    }

    /** The collapsed region's text (header through end line), capped at {@link #PREVIEW_LINES}. */
    private String foldPreview(Region region) {
        int total = area.getParagraphs().size();
        int last = Math.min(region.endLine(), region.startLine() + PREVIEW_LINES - 1);
        StringBuilder sb = new StringBuilder();
        for (int p = region.startLine(); p <= last && p < total; p++) {
            if (p > region.startLine()) {
                sb.append('\n');
            }
            sb.append(area.getParagraph(p).getText());
        }
        if (region.endLine() > last) {
            sb.append("\n…");
        }
        return sb.toString();
    }

    private static String formatLineNo(int line, int total) {
        return String.format("%1$" + digits(total) + "s", line);
    }

    /** Number of decimal digits needed for {@code total} (>= 1). */
    private static int digits(int total) {
        return (int) Math.floor(Math.log10(Math.max(1, total))) + 1;
    }
}
