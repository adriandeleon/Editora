package com.editora.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import org.fxmisc.richtext.CodeArea;

/**
 * AceJump (avy-goto-char): press the trigger, type a character, and a short label is drawn over every
 * visible occurrence; type the label to move the caret there. {@code Esc}/{@code C-g} cancels.
 *
 * <p>Modal: while active it owns the editor's keys (consumes {@code KEY_TYPED}/{@code KEY_PRESSED} on
 * the {@link CodeArea} so nothing is inserted and no chord fires, plus the {@code editora.ownsKeys}
 * property). A mouse-transparent {@link Canvas} draws the labels; visible-only; scrolling cancels.
 */
final class AceJumpOverlay extends Region {

    private static final Color PILL = Color.web("#ffe0b2"); // pastel peach/amber
    private static final Color PILL_TEXT = Color.web("#5a3a00"); // dark brown for contrast

    private final CodeArea area;
    private final Canvas canvas = new Canvas(1, 1);
    private final Font font = Font.font("monospace", FontWeight.BOLD, 12);
    private final Map<String, Integer> labelToOffset = new LinkedHashMap<>();

    private boolean active;
    private boolean awaitingChar;
    private String typed = "";
    private boolean redrawPending;

    AceJumpOverlay(CodeArea area) {
        this.area = area;
        getStyleClass().add("acejump-overlay");
        setMouseTransparent(true);
        setVisible(false);
        getChildren().add(canvas);
        // Scrolling/edits move offsets out from under the labels — cancel rather than mis-jump.
        area.viewportDirtyEvents().subscribe(ignore -> {
            if (active && !awaitingChar) {
                exit();
            }
        });
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        area.addEventFilter(KeyEvent.KEY_TYPED, this::onKeyTyped);
    }

    /** Begins AceJump: the next typed character chooses the targets. */
    void start() {
        if (active) {
            return;
        }
        active = true;
        awaitingChar = true;
        typed = "";
        labelToOffset.clear();
        setVisible(true);
        area.getProperties().put("editora.ownsKeys", Boolean.TRUE);
    }

    /** Begins AceJump line-mode (avy-goto-line): every visible line is labeled immediately (no char step);
     *  typing a label jumps the caret to that line's first non-whitespace character. */
    void startLine() {
        if (active) {
            return;
        }
        active = true;
        typed = "";
        labelToOffset.clear();
        setVisible(true);
        area.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        List<Integer> offsets = visibleLineStarts();
        if (offsets.isEmpty()) {
            exit();
            return;
        }
        if (offsets.size() == 1) {
            jump(offsets.get(0));
            return;
        }
        List<String> labels = AceLabels.labels(offsets.size(), AceLabels.DEFAULT_ALPHABET);
        for (int i = 0; i < labels.size(); i++) {
            labelToOffset.put(labels.get(i), offsets.get(i));
        }
        awaitingChar = false; // labels are already assigned — skip the char-selection step
        scheduleRedraw();
    }

    private void exit() {
        active = false;
        awaitingChar = false;
        typed = "";
        labelToOffset.clear();
        area.getProperties().remove("editora.ownsKeys");
        setVisible(false);
        clear();
    }

    private void onKeyPressed(KeyEvent e) {
        if (!active) {
            return;
        }
        if (e.getCode() == KeyCode.ESCAPE || (e.getCode() == KeyCode.G && e.isControlDown())) {
            exit();
        }
        e.consume(); // block caret movement / chords while modal; characters arrive via KEY_TYPED
    }

    private void onKeyTyped(KeyEvent e) {
        if (!active) {
            return;
        }
        e.consume();
        String s = e.getCharacter();
        if (s == null || s.isEmpty() || s.charAt(0) < 0x20) {
            return;
        }
        char c = s.charAt(0);
        if (awaitingChar) {
            chooseTargets(c);
        } else {
            typeLabel(c);
        }
    }

    private void chooseTargets(char c) {
        List<Integer> offsets = visibleOccurrences(c);
        if (offsets.isEmpty()) {
            exit();
            return;
        }
        if (offsets.size() == 1) {
            jump(offsets.get(0));
            return;
        }
        List<String> labels = AceLabels.labels(offsets.size(), AceLabels.DEFAULT_ALPHABET);
        labelToOffset.clear();
        for (int i = 0; i < labels.size(); i++) {
            labelToOffset.put(labels.get(i), offsets.get(i));
        }
        awaitingChar = false;
        typed = "";
        scheduleRedraw();
    }

    private void typeLabel(char c) {
        typed += Character.toLowerCase(c);
        Integer exact = labelToOffset.get(typed);
        if (exact != null) {
            jump(exact);
            return;
        }
        boolean anyPrefix = labelToOffset.keySet().stream().anyMatch(k -> k.startsWith(typed));
        if (!anyPrefix) {
            exit();
            return;
        }
        scheduleRedraw();
    }

    private void jump(int offset) {
        exit();
        area.moveTo(offset);
        area.requestFollowCaret();
        area.requestFocus();
    }

    private List<Integer> visibleOccurrences(char c) {
        List<Integer> out = new ArrayList<>();
        try {
            int total = area.getParagraphs().size();
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            char target = Character.toLowerCase(c);
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue;
                }
                String line = area.getParagraph(p).getText();
                for (int i = 0; i < line.length(); i++) {
                    if (Character.toLowerCase(line.charAt(i)) == target) {
                        out.add(area.getAbsolutePosition(p, i));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // viewport mid-layout
        }
        return out;
    }

    /** The offset of the first non-whitespace character of each visible, non-folded line (line start for a
     *  blank/all-whitespace line) — the jump targets for line-mode. */
    private List<Integer> visibleLineStarts() {
        List<Integer> out = new ArrayList<>();
        try {
            int total = area.getParagraphs().size();
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue;
                }
                String line = area.getParagraph(p).getText();
                int col = 0;
                while (col < line.length() && Character.isWhitespace(line.charAt(col))) {
                    col++;
                }
                out.add(area.getAbsolutePosition(p, Math.min(col, line.length())));
            }
        } catch (RuntimeException ignored) {
            // viewport mid-layout
        }
        return out;
    }

    @Override
    protected void layoutChildren() {
        double w = CanvasGuards.clampDim(getWidth());
        double h = CanvasGuards.clampDim(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
        }
        canvas.relocate(0, 0);
        if (active && !awaitingChar) {
            scheduleRedraw();
        }
    }

    private void scheduleRedraw() {
        if (redrawPending) {
            return;
        }
        redrawPending = true;
        Platform.runLater(() -> {
            redrawPending = false;
            redraw();
        });
    }

    private void clear() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (!active || awaitingChar || !CanvasGuards.paintable(getWidth(), getHeight())) {
            return;
        }
        g.setFont(font);
        for (Map.Entry<String, Integer> entry : labelToOffset.entrySet()) {
            String label = entry.getKey();
            if (!label.startsWith(typed)) {
                continue;
            }
            Bounds b = toLocal(area.getCharacterBoundsOnScreen(entry.getValue(), entry.getValue() + 1)
                    .orElse(null));
            if (b == null || b.getMaxX() < 0 || b.getMinX() > w || b.getMaxY() < 0 || b.getMinY() > h) {
                continue;
            }
            double pillW = label.length() * 8.0 + 6;
            g.setFill(PILL);
            g.fillRoundRect(b.getMinX(), b.getMinY(), pillW, b.getHeight(), 4, 4);
            g.setFill(PILL_TEXT);
            g.fillText(label, b.getMinX() + 3, b.getMinY() + b.getHeight() * 0.78);
        }
    }

    private Bounds toLocal(Bounds screen) {
        return screen == null ? null : canvas.screenToLocal(screen);
    }
}
