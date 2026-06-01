package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

import org.fxmisc.richtext.CharacterHit;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.reactfx.collection.LiveList;
import org.reactfx.value.Val;

import com.editora.editor.FoldRegions.Region;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

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

    /** Notified after a user-driven fold/unfold so callers can persist the new state. */
    private Runnable onFoldStateChanged = () -> {
    };
    /** Notified after regions are recomputed (e.g. on text or language change). */
    private Runnable onRegionsChanged = () -> {
    };
    /** Suppresses change notifications while we programmatically restore saved folds. */
    private boolean restoring;

    /** Shared hover preview of a collapsed line's hidden content; reused across all lines. */
    private final Tooltip linePreview = new Tooltip();
    /** Paragraph whose preview is currently showing, or -1 when hidden. */
    private int previewPar = -1;

    public FoldManager(CodeArea area) {
        this.area = area;
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(250))
                .subscribe(ignore -> recompute());
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
        linePreview.setStyle(
                "-fx-background-color: " + hex(background) + ";"
                + "-fx-text-fill: " + hex(foreground) + ";"
                + "-fx-border-color: " + hex(border) + ";"
                + "-fx-border-width: 1;"
                + "-fx-padding: 6 8 6 8;"
                + "-fx-font-family: \"JetBrains Mono\", \"monospace\";"
                + "-fx-font-size: 12px;"
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0.15, 0, 3);");
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
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
        this.onFoldStateChanged = callback == null ? () -> {
        } : callback;
    }

    /** Sets a callback invoked whenever foldable regions are recomputed. */
    public void setOnRegionsChanged(Runnable callback) {
        this.onRegionsChanged = callback == null ? () -> {
        } : callback;
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
        onRegionsChanged.run();
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
        int bodyStart = area.getAbsolutePosition(region.startLine(),
                area.getParagraphLength(region.startLine()));
        int bodyEnd = area.getAbsolutePosition(region.endLine(),
                area.getParagraphLength(region.endLine()));

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
            if (r.startLine() <= line && line <= r.endLine() && !isCollapsed(r.startLine())
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
            if (r.startLine() <= line && line <= r.endLine() && isCollapsed(r.startLine())
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
        Val<Integer> nParagraphs = LiveList.sizeOf(area.getParagraphs());
        return idx -> buildGutter(idx, showLineNumbers, nParagraphs);
    }

    private Node buildGutter(int idx, boolean showLineNumbers, Val<Integer> nParagraphs) {
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

        if (showLineNumbers) {
            Label lineNo = new Label();
            lineNo.getStyleClass().add("lineno");
            lineNo.setAlignment(Pos.CENTER_RIGHT);
            Val<String> formatted = nParagraphs.map(n -> formatLineNo(idx + 1, n));
            lineNo.textProperty().bind(formatted.conditionOnShowing(lineNo));
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
            });
        }
        box.getChildren().add(chevron);
        return box;
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
        int digits = (int) Math.floor(Math.log10(Math.max(1, total))) + 1;
        return String.format("%1$" + digits + "s", line);
    }
}
