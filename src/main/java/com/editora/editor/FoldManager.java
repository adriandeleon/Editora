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

    /** Sets the language (see {@link LanguageRules#name()}) and recomputes regions. */
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
        restoreViewport(topPar);
        if (!restoring) {
            onFoldStateChanged.run();
        }
    }

    public void foldAll() {
        int topPar = firstVisiblePar();
        for (Region r : regions) {
            if (!isCollapsed(r.startLine())) {
                area.foldParagraphs(r.startLine(), r.endLine());
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
        restoreViewport(topPar);
        if (!restoring) {
            onFoldStateChanged.run();
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
                }
            }
        } finally {
            restoring = false;
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
