package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.editor.http.HttpVariableMarks;
import com.editora.markdown.MarkdownLint;
import com.editora.structured.JsonLines;

import static com.editora.i18n.Messages.tr;

/**
 * A JSON Lines buffer's squiggles: each line that is not exactly one JSON value ({@link JsonLines#check}). No
 * language server reads the format (the JSON one would call every line after the first an error), so this is
 * the check JSON files get from theirs. It runs on its own worker after an edit settles, on the squiggle overlay
 * the HTML markup check uses, and is off in large-file mode.
 */
final class JsonLinesMarks {

    /** The language id the check applies to. */
    static final String LANGUAGE = "jsonl";

    /** The {@code code} of the marks (shown in the hover). */
    static final String SOURCE = "jsonl";

    private static final ExecutorService CHECKS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jsonl-check");
        t.setDaemon(true);
        return t;
    });

    private JsonLinesMarks() {}

    /** Gives {@code buffer} the check; it runs only while the buffer is JSON Lines and not a large file. */
    static void wire(EditorBuffer buffer) {
        buffer.addMarkupCheck(new HttpVariableMarks.MarkupCheck(
                () -> LANGUAGE.equals(buffer.getLanguage()) && !buffer.isLargeFile(),
                (text, done) -> CompletableFuture.supplyAsync(() -> marks(text), CHECKS)
                        .exceptionally(t -> List.of())
                        .thenAccept(marks -> Platform.runLater(() -> done.accept(marks)))));
    }

    /** The marks for {@code text}: one error per bad line, over its content (or what follows its first value). */
    static List<MarkdownLint.Diagnostic> marks(String text) {
        List<JsonLines.Problem> problems = JsonLines.check(text);
        List<MarkdownLint.Diagnostic> out = new ArrayList<>(problems.size());
        for (JsonLines.Problem p : problems) {
            out.add(new MarkdownLint.Diagnostic(
                    p.line(), p.column(), Math.max(1, p.length()), "error", SOURCE, message(p), null));
        }
        return List.copyOf(out);
    }

    /** The mark's text in the UI language (the parser's own detail stays English, as the JSON server's does). */
    static String message(JsonLines.Problem p) {
        return switch (p.kind()) {
            case TRAILING -> tr("jsonl.check.trailing");
            case INVALID -> tr("jsonl.check.invalid", String.valueOf(p.errorColumn()), p.detail());
        };
    }
}
