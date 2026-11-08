package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PopupControl;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Skin;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.dap.DapModels;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The card an exception stop opens under the line that threw (Visual Studio's Exception Helper, VS Code's
 * exception widget): whether the program catches it, its type and message, the exceptions that caused it and the
 * stack trace the adapter sends (debugpy and js-debug do; java-debug names the type and message only), with Copy and
 * — for a caught exception — a way to stop stopping on caught ones.
 *
 * <p>It never takes focus: typing stays in the editor, Esc there closes it, and so does a click anywhere else
 * (which still reaches what was clicked). It follows the line when the editor scrolls and closes when the line
 * leaves the screen. Like the value popup, it is owned by the window so the window theme, not the editor's text
 * rules, colours it.
 */
final class DebugExceptionCard {

    /** What the card's buttons do besides closing it. */
    record Actions(Runnable stopOnCaughtOff, Runnable copied) {}

    static final double WIDTH = 560;

    /** Most lines of a stack trace the card shows (the rest scrolls). */
    static final int MAX_TRACE_LINES = 12;

    private final Actions actions;
    private final PopupControl popup = new PopupControl();
    private final Label title = new Label();
    private final Label type = new Label();
    private final Label message = new Label();
    private final VBox causes = new VBox(2);
    private final Label trace = new Label();
    private final ScrollPane traceScroll = new ScrollPane(trace);
    private final Button copy = new Button(tr("debug.exceptionCard.copy"));
    private final Button caughtOff = new Button(tr("debug.exceptionCard.caughtOff"));
    private final Button close = new Button("✕");
    private final VBox card;

    private DapModels.@Nullable ExceptionInfo shown;
    private @Nullable CodeArea area;
    private int paragraph = -1;

    private final EventHandler<KeyEvent> escape = e -> {
        if (e.getCode() == KeyCode.ESCAPE && popup.isShowing()) {
            hide();
            e.consume();
        }
    };
    /** Waits for the line to be drawn when the card is opened before it is. */
    private final javafx.animation.PauseTransition retry =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(40));

    /** How many times {@link #retry} has waited for the current card's line. */
    private int attempts;

    /** Gives up on a line not drawn after this many waits (it is off screen). */
    static final int MAX_ATTEMPTS = 10;

    private final ChangeListener<Object> follow = (o, was, now) -> Platform.runLater(this::reposition);

    DebugExceptionCard(Actions actions) {
        this.actions = actions;
        title.getStyleClass().add("debug-exception-title");
        type.getStyleClass().add("debug-exception-type");
        type.setWrapText(true);
        message.getStyleClass().add("debug-exception-message");
        message.setWrapText(true);
        trace.getStyleClass().add("debug-value-line");
        traceScroll.getStyleClass().add("debug-exception-trace");
        traceScroll.setFitToWidth(true);
        traceScroll.setMaxHeight(MAX_TRACE_LINES * 17 + 8);
        for (Button b : List.of(copy, caughtOff, close)) {
            b.getStyleClass().add("small");
            b.setFocusTraversable(false);
        }
        close.getStyleClass().add("flat");
        close.setOnAction(e -> hide());
        copy.setOnAction(e -> {
            DapModels.ExceptionInfo info = shown;
            if (info != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(text(info));
                Clipboard.getSystemClipboard().setContent(content);
                actions.copied().run();
            }
        });
        caughtOff.setOnAction(e -> {
            hide();
            actions.stopOnCaughtOff().run();
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(6, title, gap, close);
        head.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox buttons = new HBox(6, copy, caughtOff);
        card = new VBox(4, head, type, message, causes, traceScroll, buttons);
        card.getStyleClass().addAll("debug-panel", "debug-value-popup", "debug-exception-card");
        card.setPrefWidth(WIDTH);
        card.setMaxWidth(WIDTH);
        popup.setSkin(new Skin<>() {
            @Override
            public PopupControl getSkinnable() {
                return popup;
            }

            @Override
            public javafx.scene.Node getNode() {
                return card;
            }

            @Override
            public void dispose() {}
        });
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false); // the click that closes it still reaches what was clicked
        popup.setOnHidden(e -> release());
        retry.setOnFinished(e -> {
            if (area != null && !popup.isShowing() && !reposition() && ++attempts < MAX_ATTEMPTS) {
                retry.playFromStart();
            }
        });
    }

    /** The card's title for an exception: uncaught, caught, or just "exception" when the adapter did not say. */
    static String titleKey(DapModels.ExceptionInfo info) {
        if (info.uncaught()) {
            return "debug.exceptionCard.uncaught";
        }
        return info.caught() ? "debug.exceptionCard.caught" : "debug.exceptionCard.thrown";
    }

    /**
     * The message without the type in front: java-debug describes an exception by its {@code toString()},
     * {@code java.lang.IllegalStateException: boom} — as a quoted string value, quotes included — and the card shows
     * the type on its own line. Unlike the state line's summary, every line of the message is kept. Pure.
     */
    static String message(DapModels.ExceptionInfo info) {
        String m = info.message();
        if (m.length() >= 2 && m.startsWith("\"") && m.endsWith("\"")) {
            m = m.substring(1, m.length() - 1).strip();
        }
        String t = info.type();
        if (!t.isEmpty() && m.startsWith(t)) {
            String rest = m.substring(t.length()).strip();
            if (rest.isEmpty()) {
                return "";
            }
            if (rest.startsWith(":")) {
                return rest.substring(1).strip();
            }
        }
        return m;
    }

    /** What Copy puts on the clipboard: type and message, each cause, then the stack trace. Pure. */
    static String text(DapModels.ExceptionInfo info) {
        StringBuilder out = new StringBuilder(headline(info));
        appendCauses(out, info.causes());
        if (!info.stackTrace().isEmpty()) {
            out.append('\n').append(info.stackTrace());
        }
        return out.toString();
    }

    private static String headline(DapModels.ExceptionInfo info) {
        String m = message(info);
        if (info.type().isEmpty()) {
            return m;
        }
        return m.isEmpty() ? info.type() : info.type() + ": " + m;
    }

    private static void appendCauses(StringBuilder out, List<DapModels.ExceptionInfo> causes) {
        for (DapModels.ExceptionInfo c : causes) {
            out.append('\n').append(tr("debug.exceptionCard.causedBy", headline(c)));
            appendCauses(out, c.causes());
        }
    }

    /** The "Caused by" lines of {@code info}, outermost first. Pure. */
    static List<String> causeLines(DapModels.ExceptionInfo info) {
        List<String> out = new ArrayList<>();
        collect(out, info.causes());
        return out;
    }

    private static void collect(List<String> out, List<DapModels.ExceptionInfo> causes) {
        for (DapModels.ExceptionInfo c : causes) {
            out.add(tr("debug.exceptionCard.causedBy", headline(c)));
            collect(out, c.causes());
        }
    }

    /**
     * Shows {@code info} under paragraph {@code line} of {@code on}; {@code offerCaughtOff} adds "Don't stop on caught
     * exceptions" (the adapter said the program catches it, and the caught toggle is on). Nothing is shown while the
     * line is off screen.
     */
    void show(CodeArea on, int line, DapModels.ExceptionInfo info, boolean offerCaughtOff) {
        release();
        shown = info;
        area = on;
        paragraph = line;
        title.setText(tr(titleKey(info)));
        title.getStyleClass().removeAll("debug-exception-uncaught");
        if (info.uncaught()) {
            title.getStyleClass().add("debug-exception-uncaught");
        }
        type.setText(info.type().isEmpty() ? tr("debugpanel.exception") : info.type());
        String m = message(info);
        message.setText(m);
        visible(message, !m.isEmpty());
        causes.getChildren().clear();
        for (String c : causeLines(info)) {
            Label l = new Label(c);
            l.getStyleClass().add("debug-exception-cause");
            l.setWrapText(true);
            causes.getChildren().add(l);
        }
        visible(causes, !causes.getChildren().isEmpty());
        trace.setText(info.stackTrace());
        visible(traceScroll, !info.stackTrace().isEmpty());
        visible(caughtOff, offerCaughtOff);
        on.addEventFilter(KeyEvent.KEY_PRESSED, escape);
        on.estimatedScrollYProperty().addListener(follow);
        on.widthProperty().addListener(follow);
        attempts = 0;
        if (!reposition()) {
            retry.playFromStart(); // a file opened just now draws its lines a pulse or two later
        }
    }

    private static void visible(javafx.scene.Node node, boolean on) {
        node.setVisible(on);
        node.setManaged(on);
    }

    /** Under the line, at the text's left edge; closed while the line is off screen. Whether it is showing. */
    private boolean reposition() {
        CodeArea on = area;
        if (on == null || shown == null) {
            return false;
        }
        Bounds line;
        Bounds whole;
        try {
            line = paragraph < 0 || paragraph >= on.getParagraphs().size() ? null : lineBounds(on, paragraph);
            whole = on.localToScreen(on.getBoundsInLocal());
        } catch (RuntimeException notLaidOut) {
            line = null;
            whole = null;
        }
        if (line == null || whole == null || line.getMaxY() > whole.getMaxY() || line.getMinY() < whole.getMinY()) {
            if (popup.isShowing()) {
                popup.hide(); // the line scrolled away; it comes back with the line only through Show Exception
            }
            return false;
        }
        double x = Math.max(whole.getMinX(), Math.min(line.getMinX() + 24, whole.getMaxX() - WIDTH));
        double y = line.getMaxY() + 2;
        if (popup.isShowing()) {
            popup.setAnchorX(x);
            popup.setAnchorY(y);
            return true;
        }
        javafx.stage.Window window =
                on.getScene() == null ? null : on.getScene().getWindow();
        if (window != null) {
            popup.show(window, x, y);
        }
        return popup.isShowing();
    }

    /** Where line {@code paragraph} is on screen, or null while it is not drawn (off screen). */
    private static @Nullable Bounds lineBounds(CodeArea on, int paragraph) {
        return on.getParagraphBoundsOnScreen(paragraph).orElse(null);
    }

    void hide() {
        retry.stop();
        if (popup.isShowing()) {
            popup.hide();
        }
        release();
    }

    /** Stops following the editor (the card is closed). */
    private void release() {
        CodeArea on = area;
        if (on != null) {
            on.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
            on.estimatedScrollYProperty().removeListener(follow);
            on.widthProperty().removeListener(follow);
        }
        area = null;
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    /** The card, for tests. */
    VBox card() {
        return card;
    }

    /** The exception shown last (test read). */
    DapModels.@Nullable ExceptionInfo shown() {
        return shown;
    }
}
