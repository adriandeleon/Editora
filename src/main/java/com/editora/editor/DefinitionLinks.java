package com.editora.editor;

import java.util.OptionalInt;
import java.util.function.Consumer;

import javafx.animation.PauseTransition;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import com.editora.editops.SelectOccurrences;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional.Bias;
import org.jspecify.annotations.Nullable;

/**
 * Ctrl/Cmd-hover links: while the shortcut key is held over a view, the symbol under the mouse that Ctrl/Cmd-click
 * would follow is underlined and the pointer becomes a hand, the way VS Code and IntelliJ show it. The click itself
 * stays {@link EditorBuffer}'s Ctrl/Cmd-click handler; this only shows, ahead of it, where a click goes.
 *
 * <p>Whether a word is a link is the buffer's {@link Probe} (a language server's definition request, an HTML
 * {@code href}/{@code src}), asked once the mouse rests on a new word for {@link #SETTLE}; the answer for the word
 * is kept, a link or not, so moving within it asks nothing more. A late answer for a word the mouse has left, or one
 * given after the key was let go, is dropped.
 *
 * <p>The overlay is one mouse-transparent region with a one-pixel bar, hidden until there is a link: no canvas,
 * nothing painted per scroll. A split's second view gets its own (see {@link #follower}), driven by its own mouse.
 */
public final class DefinitionLinks extends Region implements SecondaryPane.Followed {

    /** How long the mouse rests on a word before the buffer is asked whether it is a link. */
    static final Duration SETTLE = Duration.millis(60);

    /** A span of the document {@code [start, end)}, and whether a Ctrl/Cmd-click there goes somewhere. */
    public record Span(int start, int end, boolean link) {
        boolean covers(int offset) {
            return start <= offset && offset < end;
        }
    }

    /** Works out whether the text at {@code offset} of {@code view} is a link; answers on the FX thread. */
    @FunctionalInterface
    public interface Probe {
        /** {@code answer} hears the span looked at, or null when there is nothing there to look at. */
        void probe(CodeArea view, int offset, Consumer<@Nullable Span> answer);
    }

    private final CodeArea view;
    private final Probe probe;
    private final Region bar = new Region();
    private final PauseTransition settle = new PauseTransition(SETTLE);

    /** The underlined link, or null. */
    private @Nullable Span shown;
    /** The last span asked about (answered or still out): the mouse moving inside it asks nothing new. */
    private @Nullable Span asked;
    /** Bumped by every new question and every reset, so an answer can tell whether it is still wanted. */
    private long generation;

    private int pendingOffset = -1;
    private boolean inside;
    private @Nullable Cursor cursorBefore;
    private @Nullable Scene keyScene;

    private final EventHandler<KeyEvent> keys = this::onKey;

    public DefinitionLinks(CodeArea view, Probe probe) {
        this.view = view;
        this.probe = probe;
        getStyleClass().add("definition-link-overlay");
        bar.getStyleClass().add("definition-link-underline");
        bar.setManaged(false);
        bar.setVisible(false);
        getChildren().add(bar);
        setMouseTransparent(true);
        setPickOnBounds(false);
        settle.setOnFinished(e -> ask());
        view.addEventFilter(MouseEvent.MOUSE_MOVED, this::onMove);
        view.addEventFilter(MouseEvent.MOUSE_ENTERED, e -> enter());
        view.addEventFilter(MouseEvent.MOUSE_EXITED, e -> exit());
        // A press (the click that follows the link, or a drag) and anything that moves the text under the mouse
        // ends the link; the next move with the key still held looks again.
        view.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> reset());
        view.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> reset());
        view.plainTextChanges().subscribe(c -> reset());
        view.estimatedScrollXProperty().addListener((o, a, b) -> reset());
        view.estimatedScrollYProperty().addListener((o, a, b) -> reset());
        view.sceneProperty().addListener((o, was, now) -> {
            exit(); // a view taken out of its window (a tab moved or closed) keeps no key filter on the old scene
        });
    }

    /** A second view's own links, asking the same buffer. */
    @Override
    public DefinitionLinks follower(CodeArea secondView) {
        return new DefinitionLinks(secondView, probe);
    }

    /**
     * Whether {@code e}'s modifiers are the link chord: the shortcut key alone (Ctrl, or Cmd on macOS). Shift and
     * Alt held with it mean another gesture (a column selection, an added caret) and show nothing.
     */
    static boolean chord(boolean shortcut, boolean shift, boolean alt) {
        return shortcut && !shift && !alt;
    }

    /**
     * The identifier {@code [start, end)} of {@code line} that the character at {@code column} belongs to, or null
     * when that character is not part of one. Unlike a caret, the mouse is over a character: the gap after a word
     * is not the word.
     */
    static int @Nullable [] wordAt(String line, int column, @Nullable String language) {
        if (column < 0 || column >= line.length()) {
            return null;
        }
        int[] word = SelectOccurrences.wordAt(line, column, language);
        return word != null && word[0] <= column && column < word[1] ? word : null;
    }

    /** TEST SEAM — whether a question is waiting for the mouse to settle (the witness that none was asked). */
    boolean settlingForTest() {
        return settle.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    /** The span shown now, for tests. */
    @Nullable
    Span shownForTest() {
        return shown;
    }

    private void enter() {
        inside = true;
        Scene scene = view.getScene();
        if (scene != keyScene) {
            unhookKeys();
            if (scene != null) {
                // The scene's, not the view's: a view the mouse is over but that does not have the focus (the other
                // half of a split) still hears the key go down and up.
                scene.addEventFilter(KeyEvent.KEY_PRESSED, keys);
                scene.addEventFilter(KeyEvent.KEY_RELEASED, keys);
                keyScene = scene;
            }
        }
    }

    private void exit() {
        inside = false;
        unhookKeys();
        reset();
    }

    private void unhookKeys() {
        if (keyScene != null) {
            keyScene.removeEventFilter(KeyEvent.KEY_PRESSED, keys);
            keyScene.removeEventFilter(KeyEvent.KEY_RELEASED, keys);
            keyScene = null;
        }
    }

    private double mouseScreenX = Double.NaN;
    private double mouseScreenY = Double.NaN;

    private void onMove(MouseEvent e) {
        if (!inside) {
            enter(); // a view that appeared under a still mouse hears no ENTERED
        }
        // Screen coordinates: MOUSE_MOVED comes from RichTextFX's text nodes, and getX/getY need not be the
        // view's own (see NoteHover); the screen position is the same all along the dispatch chain.
        mouseScreenX = e.getScreenX();
        mouseScreenY = e.getScreenY();
        look(chord(e.isShortcutDown(), e.isShiftDown(), e.isAltDown()));
    }

    private void onKey(KeyEvent e) {
        if (!inside) {
            return;
        }
        boolean held = chord(e.isShortcutDown(), e.isShiftDown(), e.isAltDown());
        if (!held || e.getEventType() == KeyEvent.KEY_PRESSED && !e.getCode().isModifierKey()) {
            reset(); // the key let go, or a shortcut being typed (Ctrl+S): not a link gesture any more
            return;
        }
        look(true);
    }

    /** With the chord {@code held}, looks at the character under the mouse; without it, drops any link. */
    private void look(boolean held) {
        if (!held || Double.isNaN(mouseScreenX)) {
            reset();
            return;
        }
        int offset = characterUnderMouse();
        if (offset < 0) {
            reset();
            return;
        }
        if (shown != null && shown.covers(offset)) {
            return;
        }
        if (asked != null && asked.covers(offset)) {
            return; // the answer for this word is in (not a link) or on its way
        }
        hide();
        asked = null;
        pendingOffset = offset;
        settle.playFromStart();
    }

    private int characterUnderMouse() {
        try {
            javafx.geometry.Point2D local = view.screenToLocal(mouseScreenX, mouseScreenY);
            if (local == null) {
                return -1;
            }
            OptionalInt ch = view.hit(local.getX(), local.getY()).getCharacterIndex();
            return ch.isPresent() && ch.getAsInt() < view.getLength() ? ch.getAsInt() : -1;
        } catch (RuntimeException ex) {
            return -1; // the viewport is mid-layout
        }
    }

    private void ask() {
        int offset = pendingOffset;
        if (offset < 0 || !inside) {
            return;
        }
        long mine = ++generation;
        asked = new Span(offset, offset + 1, false); // until the answer says how far the word reaches
        probe.probe(view, offset, span -> {
            if (mine != generation) {
                return;
            }
            Span looked = span != null && span.covers(offset) ? span : new Span(offset, offset + 1, false);
            asked = looked;
            if (looked.link()) {
                show(looked);
            }
        });
    }

    private void show(Span span) {
        Bounds b = underlineBounds(span);
        if (b == null) {
            return;
        }
        shown = span;
        double thickness = Math.max(1, Math.round(UiZoom.px(1)));
        bar.resizeRelocate(b.getMinX(), b.getMaxY() - thickness, b.getWidth(), thickness);
        bar.setVisible(true);
        if (view.getCursor() != Cursor.HAND) {
            cursorBefore = view.getCursor();
            view.setCursor(Cursor.HAND);
        }
    }

    /** The bounds of {@code span} in this overlay, or null when it is off screen or not on one line. */
    private @Nullable Bounds underlineBounds(Span span) {
        try {
            var start = view.offsetToPosition(span.start(), Bias.Forward);
            var end = view.offsetToPosition(span.end(), Bias.Backward);
            if (start.getMajor() != end.getMajor()) {
                return null; // a link value broken over lines: no single underline fits it
            }
            Bounds screen =
                    view.getCharacterBoundsOnScreen(span.start(), span.end()).orElse(null);
            return screen == null ? null : screenToLocal(screen);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void hide() {
        if (shown == null) {
            return;
        }
        shown = null;
        bar.setVisible(false);
        if (view.getCursor() == Cursor.HAND) {
            view.setCursor(cursorBefore);
        }
        cursorBefore = null;
    }

    /** Drops the link, the question out and what is known about the word under the mouse. */
    void reset() {
        settle.stop();
        pendingOffset = -1;
        if (asked != null || shown != null) {
            generation++;
            asked = null;
        }
        hide();
    }

    @Override
    protected void layoutChildren() {
        // the bar is placed by show(), not by layout
    }
}
