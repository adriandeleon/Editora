package com.editora.ui;

import java.util.function.Consumer;

import javafx.beans.InvalidationListener;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.KeyEvent;

import org.fxmisc.richtext.CodeArea;

/**
 * Reads the next character typed into an editor area for a command that asks for one — zap-to-char's target,
 * a register's name — and hands it over. Escape, a key that types nothing printable, moving the caret or
 * leaving the editor cancel it, as any non-character key does in Emacs: an armed reader that survived
 * navigation acted on the next ordinary keystroke, much later.
 *
 * <p>It listens on the area's parent, not the area: JavaFX runs <em>every</em> filter registered on one node,
 * in registration order and whether or not an earlier one consumed the event, so a filter on the area itself
 * ran after the buffer's own typing assists — auto-indent, smart Backspace/Tab and auto-close had already
 * edited the document with the key meant for the command. A filter on an ancestor runs first, and consuming
 * there ends dispatch.
 */
final class ReadCharSession implements EventHandler<KeyEvent> {

    private final CodeArea area;
    private final Node keyHost;
    private final Runnable onEnd;
    private final Consumer<Character> onChar;
    private final InvalidationListener cancel = obs -> disarm();

    private ReadCharSession(CodeArea area, Runnable onEnd, Consumer<Character> onChar) {
        this.area = area;
        this.keyHost = keyHost(area);
        this.onEnd = onEnd;
        this.onChar = onChar;
    }

    /**
     * Arms a reader on {@code area}: the next printable character goes to {@code onChar}. {@code onEnd} runs
     * whenever the reader stands down, with a character or without one (it clears the prompt).
     */
    static void read(CodeArea area, Runnable onEnd, Consumer<Character> onChar) {
        ReadCharSession session = new ReadCharSession(area, onEnd, onChar);
        session.keyHost.addEventFilter(KeyEvent.KEY_TYPED, session);
        area.caretPositionProperty().addListener(session.cancel);
        area.focusedProperty().addListener(session.cancel);
    }

    /** The node a modal key session listens on: the area's parent (see the class comment). */
    static Node keyHost(CodeArea area) {
        return area.getParent() != null ? area.getParent() : area;
    }

    private void disarm() {
        keyHost.removeEventFilter(KeyEvent.KEY_TYPED, this);
        area.caretPositionProperty().removeListener(cancel);
        area.focusedProperty().removeListener(cancel);
        onEnd.run();
    }

    @Override
    public void handle(KeyEvent e) {
        disarm();
        e.consume();
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || ch.charAt(0) < ' ' || ch.charAt(0) == 0x7F) {
            return; // Escape / no printable character: cancel
        }
        onChar.accept(ch.charAt(0));
    }
}
