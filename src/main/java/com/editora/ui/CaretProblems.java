package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.text.LspDiagnostic;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The problems at the caret, for the keyboard's ways to read them: Show Problem at Caret and Show Documentation.
 * lemminx spells out what would be valid in its messages ("One of the following is expected: - to"), over
 * several lines; the status line cut that off at the first and hover said "No documentation at the caret", so
 * only a mouse user (the squiggle's tooltip) or the flattened Problems row ever saw it (XML review C6). The
 * whole message now leads the hover card, as VS Code shows it.
 */
final class CaretProblems {

    private CaretProblems() {}

    /** Whether {@code d} spans the caret at ({@code line}, {@code col}), both ends included. */
    static boolean atCaret(LspDiagnostic d, int line, int col) {
        return d.startLine() <= line
                && line <= d.endLine()
                && (d.startLine() < line || d.startCol() <= col)
                && (d.endLine() > line || col <= d.endCol());
    }

    /** The problems spanning the caret, most severe first (stable otherwise). */
    static List<LspDiagnostic> at(List<LspDiagnostic> all, int line, int col) {
        List<LspDiagnostic> out = new ArrayList<>();
        for (LspDiagnostic d : all) {
            if (atCaret(d, line, col)) {
                out.add(d);
            }
        }
        out.sort(Comparator.comparingInt(d -> d.severity().ordinal()));
        return out;
    }

    /** Whether any problem touches {@code line}. */
    static boolean onLine(List<LspDiagnostic> all, int line) {
        for (LspDiagnostic d : all) {
            if (d.startLine() <= line && line <= d.endLine()) {
                return true;
            }
        }
        return false;
    }

    /** The problem Show Problem at Caret names: the one at the caret, else the line's most severe; or null. */
    static @Nullable LspDiagnostic nearest(List<LspDiagnostic> all, int line, int col) {
        LspDiagnostic best = null;
        for (LspDiagnostic d : all) {
            boolean onLine = d.startLine() <= line && line <= d.endLine();
            if (!onLine) {
                continue;
            }
            if (atCaret(d, line, col)) {
                return d;
            }
            if (best == null || d.severity().ordinal() < best.severity().ordinal()) {
                best = d;
            }
        }
        return best;
    }

    /** One problem as the hover card reads it: severity, the whole message, then its code. */
    static String text(LspDiagnostic d) {
        String severity = switch (d.severity()) {
            case ERROR -> tr("lsp.problem.severity.error");
            case WARNING -> tr("lsp.problem.severity.warning");
            case INFO -> tr("lsp.problem.severity.info");
            case HINT -> tr("lsp.problem.severity.hint");
        };
        String message = d.message().strip();
        String code =
                d.code() == null || d.code().isBlank() ? "" : " [" + d.code().strip() + "]";
        return severity + ": " + message + code;
    }

    /** The card's problem section: one wrapped label per problem, or null for none. Plain text, never markup. */
    static @Nullable Node node(List<LspDiagnostic> problems) {
        if (problems.isEmpty()) {
            return null;
        }
        VBox box = new VBox(6);
        box.getStyleClass().add("lsp-hover-problems");
        for (LspDiagnostic d : problems) {
            Label label = new Label(text(d));
            label.setWrapText(true);
            label.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
            label.getStyleClass()
                    .addAll("lsp-hover-problem", d.severity().name().toLowerCase(java.util.Locale.ROOT));
            box.getChildren().add(label);
        }
        return box;
    }
}
