package com.editora.editor;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The file's problem counts at the top right of the editor, IntelliJ's inspection widget: errors, warnings and notes
 * (information and hints, IntelliJ's weak warnings, muted) with their glyphs, or a check when the language server
 * found nothing at all, and previous/next arrows. Clicking the counts opens
 * Problems; the arrows go to the previous and next problem. Shown only while a language server checks the file.
 */
final class ProblemCounter extends HBox {

    private static final String ERROR_PATH =
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z";
    private static final String WARNING_PATH = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z";
    /** Material "info": a note (an information or hint diagnostic, ShellCheck's quoting and style advice). */
    private static final String NOTE_PATH =
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z";

    private static final String CHECK_PATH = "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    private static final String UP_PATH = "M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z";
    private static final String DOWN_PATH = "M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z";

    /** Material "hourglass_empty": not checked yet. */
    private static final String PENDING_PATH =
            "M6 2v6h.01L6 8.01 10 12l-4 4 .01.01H6V22h12v-5.99h-.01L18 16l-4-4 4-3.99"
                    + "-.01-.01H18V2H6zm10 14.5V20H8v-3.5l4-4 4 4zm-4-5-4-4V4h8v3.5l-4 4z";

    /** Below this width of the code pane the counter hides: it would cover the first line's text (fifth review AV9). */
    static final double MIN_PANE_WIDTH = 520;

    private final Label errors = new Label();
    private final Label warnings = new Label();
    private final Label notes = new Label();
    private final Node ok = glyph(CHECK_PATH, "problem-counter-ok");
    private final HBox counts = new HBox(6);
    private final Button previous = arrow(UP_PATH, "problem-counter-previous");
    private final Button next = arrow(DOWN_PATH, "problem-counter-next");
    private final Node pendingGlyph = glyph(PENDING_PATH, "problem-counter-pending");
    private int errorCount = -1;
    private int warningCount = -1;
    private int noteCount = -1;
    /** Why the counts are not known (not checked yet, the project did not import), or null when they are. */
    private @Nullable String pending;

    private java.util.function.Function<String, @Nullable String> chords = id -> null;
    private boolean roomy = true;
    private final javafx.beans.value.ChangeListener<Number> paneWidth = (o, was, now) -> fit(now.doubleValue());
    private @Nullable Region pane;

    ProblemCounter(Runnable open, Runnable onPrevious, Runnable onNext) {
        super(2);
        getStyleClass().add("problem-counter");
        setAlignment(Pos.CENTER);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        errors.setGraphic(glyph(ERROR_PATH, "problem-counter-error-glyph"));
        errors.getStyleClass().add("problem-counter-errors");
        warnings.setGraphic(glyph(WARNING_PATH, "problem-counter-warning-glyph"));
        warnings.getStyleClass().add("problem-counter-warnings");
        notes.setGraphic(glyph(NOTE_PATH, "problem-counter-note-glyph"));
        notes.getStyleClass().add("problem-counter-notes");
        counts.getStyleClass().add("problem-counter-counts");
        counts.setAlignment(Pos.CENTER);
        counts.setCursor(Cursor.HAND);
        counts.setOnMouseClicked(e -> {
            open.run();
            e.consume();
        });
        LazyTooltip.install(counts, this::summary);
        previous.setOnAction(e -> onPrevious.run());
        next.setOnAction(e -> onNext.run());
        LazyTooltip.install(previous, () -> withChord(tr("editor.problemCounter.previous"), "lsp.previousProblem"));
        LazyTooltip.install(next, () -> withChord(tr("editor.problemCounter.next"), "lsp.nextProblem"));
        previous.setAccessibleText(tr("editor.problemCounter.previous")); // a screen reader named two bare buttons
        next.setAccessibleText(tr("editor.problemCounter.next"));
        getChildren().setAll(counts, previous, next);
        // the row it sits in is put into the code pane after this: follow it there and hide in a narrow pane
        parentProperty().addListener((o, was, row) -> {
            if (row != null) {
                row.parentProperty().addListener((o2, w2, codePane) -> watch(codePane));
                watch(row.getParent());
            }
        });
        show(List.of(), null);
    }

    /** The active keymap's chords for command ids, for the tooltips; set by the window. */
    void setChords(java.util.function.Function<String, @Nullable String> chords) {
        this.chords = chords == null ? id -> null : chords;
    }

    private String withChord(String text, String commandId) {
        String chord = chords.apply(commandId);
        return chord == null || chord.isBlank() ? text : text + " (" + chord + ")";
    }

    private void watch(javafx.scene.@Nullable Parent codePane) {
        if (pane != null) {
            pane.widthProperty().removeListener(paneWidth);
        }
        pane = codePane instanceof Region r ? r : null;
        if (pane != null) {
            pane.widthProperty().addListener(paneWidth);
            fit(pane.getWidth());
        }
    }

    private void fit(double width) {
        roomy = width <= 0 || width >= MIN_PANE_WIDTH;
        setVisible(roomy);
    }

    /**
     * Counts {@code diagnostics}' errors, warnings and notes; the arrows go only when there is somewhere to go. The
     * check means no problem at all: ShellCheck reports its commonest findings (unquoted variables) as information,
     * and a script full of them showed "No problems found" with no arrows to reach them (Bash review C5). With a
     * {@code pendingReason} the counts are not known: an hourglass and the reason instead of a check, which said "no
     * problems" before the server had looked (fifth review CV1).
     */
    void show(List<LspDiagnostic> diagnostics, @Nullable String pendingReason) {
        pending = pendingReason;
        if (pendingReason != null) {
            errorCount = -1;
            warningCount = -1;
            noteCount = -1;
            counts.getChildren().setAll(pendingGlyph);
            counts.setAccessibleText(pendingReason);
            previous.setVisible(false);
            previous.setManaged(false);
            next.setVisible(false);
            next.setManaged(false);
            return;
        }
        int e = 0;
        int w = 0;
        int n = 0;
        for (LspDiagnostic d : diagnostics) {
            if (d.severity() == LspDiagnostic.Severity.ERROR) {
                e++;
            } else if (d.severity() == LspDiagnostic.Severity.WARNING) {
                w++;
            } else {
                n++;
            }
        }
        if (e == errorCount && w == warningCount && n == noteCount) {
            return;
        }
        errorCount = e;
        warningCount = w;
        noteCount = n;
        errors.setText(Integer.toString(e));
        warnings.setText(Integer.toString(w));
        notes.setText(Integer.toString(n));
        if (e + w + n == 0) {
            counts.getChildren().setAll(ok);
        } else {
            counts.getChildren().clear();
            if (e > 0) {
                counts.getChildren().add(errors);
            }
            if (w > 0) {
                counts.getChildren().add(warnings);
            }
            if (n > 0) {
                counts.getChildren().add(notes);
            }
        }
        counts.setAccessibleText(summary());
        boolean any = e + w + n > 0;
        previous.setVisible(any);
        previous.setManaged(any);
        next.setVisible(any);
        next.setManaged(any);
    }

    int errors() {
        return Math.max(0, errorCount);
    }

    int warnings() {
        return Math.max(0, warningCount);
    }

    int notes() {
        return Math.max(0, noteCount);
    }

    /** What the counts say, for a tooltip and a screen reader. */
    String summary() {
        String why = pending;
        if (why != null) {
            return why;
        }
        return errors() + warnings() + notes() == 0
                ? tr("editor.problemCounter.none")
                : tr("editor.problemCounter.summary", errors(), warnings(), notes());
    }

    private static Node glyph(String path, String styleClass) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.getStyleClass().add(styleClass);
        svg.setScaleX(0.55);
        svg.setScaleY(0.55);
        return new Group(svg);
    }

    private static Button arrow(String path, String styleClass) {
        Button b = new Button();
        b.setGraphic(glyph(path, "problem-counter-arrow"));
        b.getStyleClass().addAll("flat", "problem-counter-step", styleClass);
        b.setFocusTraversable(false);
        return b;
    }
}
