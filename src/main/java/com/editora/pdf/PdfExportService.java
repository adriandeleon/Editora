package com.editora.pdf;

import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpans;

import javafx.application.Platform;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.TextMateHighlighter;

/**
 * Generates PDFs off the JavaFX thread (the {@code GitService}/{@code MermaidService} idiom): a single
 * daemon executor runs the (blocking) PDFBox work and posts a {@link Result} back via
 * {@link Platform#runLater}. Tokenization for the code PDF also runs here, off the FX thread.
 */
public final class PdfExportService {

    /** Outcome of an export: {@code ok} plus an error {@code message} on failure. */
    public record Result(boolean ok, String message) {
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pdf-export");
        t.setDaemon(true);
        return t;
    });

    /**
     * Exports {@code text} as a code PDF. Highlighting (when {@code highlight}) is computed from the
     * grammar for {@code fileName}; a file with no bundled grammar exports as plain text.
     */
    public void exportCode(String text, String fileName, boolean highlight, boolean lineNumbers,
            int tabSize, String pageSize, Path out, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                StyleSpans<Collection<String>> spans = null;
                if (highlight) {
                    IGrammar grammar = GrammarRegistry.shared().forFileName(fileName);
                    if (grammar != null) {
                        spans = TextMateHighlighter.compute(text, grammar);
                    }
                }
                CodePdfWriter.write(text, spans, lineNumbers, tabSize, pageSize, out);
                result = new Result(true, "");
            } catch (Exception e) {
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Exports {@code markdown} as a native-vector PDF. {@code mmdcCommand} (or null) renders embedded
     * ```mermaid blocks as diagrams. Runs off the FX thread.
     */
    public void exportMarkdown(String markdown, Path baseDir, String pageSize, java.util.List<String> mmdcCommand,
            Path out, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                MarkdownPdfWriter.write(markdown, baseDir, pageSize, mmdcCommand, out);
                result = new Result(true, "");
            } catch (Exception e) {
                result = new Result(false, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            Result r = result;
            Platform.runLater(() -> onResult.accept(r));
        });
    }
}
