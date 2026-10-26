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

import static com.editora.i18n.Messages.tr;

/**
 * The file's problem counts at the top right of the editor, IntelliJ's inspection widget: errors and warnings with
 * their glyphs, or a check when the language server found none, and previous/next arrows. Clicking the counts opens
 * Problems; the arrows go to the previous and next problem. Shown only while a language server checks the file.
 */
final class ProblemCounter extends HBox {

    private static final String ERROR_PATH =
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z";
    private static final String WARNING_PATH = "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z";
    private static final String CHECK_PATH = "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
    private static final String UP_PATH = "M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z";
    private static final String DOWN_PATH = "M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z";

    private final Label errors = new Label();
    private final Label warnings = new Label();
    private final Node ok = glyph(CHECK_PATH, "problem-counter-ok");
    private final HBox counts = new HBox(6);
    private final Button previous = arrow(UP_PATH, "problem-counter-previous");
    private final Button next = arrow(DOWN_PATH, "problem-counter-next");
    private int errorCount = -1;
    private int warningCount = -1;

    ProblemCounter(Runnable open, Runnable onPrevious, Runnable onNext) {
        super(2);
        getStyleClass().add("problem-counter");
        setAlignment(Pos.CENTER);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        errors.setGraphic(glyph(ERROR_PATH, "problem-counter-error-glyph"));
        errors.getStyleClass().add("problem-counter-errors");
        warnings.setGraphic(glyph(WARNING_PATH, "problem-counter-warning-glyph"));
        warnings.getStyleClass().add("problem-counter-warnings");
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
        LazyTooltip.install(previous, () -> tr("editor.problemCounter.previous"));
        LazyTooltip.install(next, () -> tr("editor.problemCounter.next"));
        getChildren().setAll(counts, previous, next);
        show(List.of());
    }

    /** Counts {@code diagnostics}' errors and warnings; the arrows go only when there is somewhere to go. */
    void show(List<LspDiagnostic> diagnostics) {
        int e = 0;
        int w = 0;
        for (LspDiagnostic d : diagnostics) {
            if (d.severity() == LspDiagnostic.Severity.ERROR) {
                e++;
            } else if (d.severity() == LspDiagnostic.Severity.WARNING) {
                w++;
            }
        }
        if (e == errorCount && w == warningCount) {
            return;
        }
        errorCount = e;
        warningCount = w;
        errors.setText(Integer.toString(e));
        warnings.setText(Integer.toString(w));
        if (e == 0 && w == 0) {
            counts.getChildren().setAll(ok);
        } else {
            counts.getChildren().clear();
            if (e > 0) {
                counts.getChildren().add(errors);
            }
            if (w > 0) {
                counts.getChildren().add(warnings);
            }
        }
        counts.setAccessibleText(summary());
        boolean any = e + w > 0;
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

    /** What the counts say, for a tooltip and a screen reader. */
    String summary() {
        return errors() + warnings() == 0
                ? tr("editor.problemCounter.none")
                : tr("editor.problemCounter.summary", errors(), warnings());
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
