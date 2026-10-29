package com.editora.ui;

import java.nio.file.Path;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.format.HtmlFormatter;
import com.editora.format.XmlFormatter;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Format Document on an XML file no language server formats — the XML server off, not installed, or LSP support
 * off altogether (XML review C8): Editora's own {@link XmlFormatter}, indenting with the buffer's indent unit,
 * applied as one undoable edit. A running server that formats keeps the job. An HTML file gets the same treatment
 * with {@link HtmlFormatter} (HTML review C17).
 *
 * <p>The formatting itself runs on a virtual thread: a 6 MB file took over a second, all of it on the FX thread
 * (XML review 2 C10). The result is applied on the FX thread only when the tab is still the active one and its
 * text has not changed meanwhile, and the caret goes back to the same character it was on rather than to the end
 * of the reformatted text (C4).
 *
 * <p>One format at a time (XML/pom review 3, C29): a second Format Document while one runs says so, where it used to
 * start another that then reported the first one's edit as "the file changed". And in large-file mode, which has no
 * undo and redraws slowly, a format that more than doubles the line count (60,000 lines into 480,000) asks first.
 */
final class XmlFormatCoordinator {

    private final PythonFormatCoordinator.Host host;

    /** The buffer being formatted off the FX thread, or null. */
    private @Nullable EditorBuffer running;

    /** Asks whether to format a large-file buffer from {@code lines[0]} lines into {@code lines[1]}; a test seam. */
    private java.util.function.BiPredicate<EditorBuffer, long[]> askGrowth = XmlFormatCoordinator::askGrowth;

    XmlFormatCoordinator(PythonFormatCoordinator.Host host) {
        this.host = host;
    }

    void setAskGrowthForTest(java.util.function.BiPredicate<EditorBuffer, long[]> ask) {
        this.askGrowth = ask;
    }

    /** Formats the active buffer when it is XML (or HTML) a server will not format; true when it took the job. */
    boolean formatActive() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !("xml".equals(buffer.getLanguage()) || "html".equals(buffer.getLanguage()))) {
            return false;
        }
        boolean html = "html".equals(buffer.getLanguage());
        Path path = buffer.getPath();
        if (path != null && host.serverFormats(path)) {
            return false;
        }
        if (!host.activeEditable() || buffer.isNarrowed()) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return true;
        }
        if (running != null) {
            host.setStatus(tr("status.xml.formatRunning"));
            return true;
        }
        String before = buffer.getArea().getText();
        String indent = buffer.indentUnit();
        int caret = buffer.getArea().getCaretPosition();
        running = buffer;
        host.setStatus(tr("status.lsp.formatting"));
        Thread.ofVirtual().name("xml-format").start(() -> {
            String after = null;
            int newCaret = caret;
            try {
                after = html ? formatHtml(before, indent) : format(before, indent);
                newCaret = after == null ? caret : caretAfter(before, after, caret);
            } finally {
                String formatted = after;
                int at = newCaret;
                Platform.runLater(() -> {
                    running = null;
                    apply(buffer, before, formatted, at, html);
                });
            }
        });
        return true;
    }

    private void apply(EditorBuffer buffer, String before, @Nullable String after, int caret, boolean html) {
        if (buffer != host.activeBuffer() || !buffer.getArea().getText().equals(before)) {
            host.setStatus(tr("status.lsp.formatStale")); // edited (or left) mid-format: a re-run formats cleanly
            return;
        }
        if (after == null) {
            host.setError(tr(html ? "status.html.formatOpenConstruct" : "status.xml.formatNotWellFormed"));
            return;
        }
        int[] span = com.editora.format.PythonFormatter.changedSpan(before, after);
        if (span == null) {
            host.setStatus(tr("status.lsp.formatNoChange"));
            return;
        }
        long[] lines = {lines(before), lines(after)};
        if (buffer.isLargeFile() && lines[1] > 2 * lines[0] && !askGrowth.test(buffer, lines)) {
            host.setStatus(tr("status.xml.formatSkipped"));
            return;
        }
        if (!NoUndoGuard.allow(buffer, tr("noUndo.op.format"))) {
            return;
        }
        buffer.getArea().replaceText(span[0], span[1], after.substring(span[0], span[2]));
        buffer.getArea().moveTo(Math.min(caret, buffer.getArea().getLength()));
        buffer.getArea().requestFollowCaret();
        host.setStatus(tr(html ? "status.html.formatted" : "status.xml.formatted"));
    }

    private static long lines(String text) {
        return text.chars().filter(ch -> ch == '\n').count() + 1;
    }

    /** The question before a large-file format that multiplies the line count; Cancel is the default. */
    private static boolean askGrowth(EditorBuffer buffer, long[] lines) {
        var format = new javafx.scene.control.ButtonType(
                tr("dialog.xml.formatGrowth.accept"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        var cancel = javafx.scene.control.ButtonType.CANCEL;
        var alert = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                tr("dialog.xml.formatGrowth.body", String.format("%,d", lines[0]), String.format("%,d", lines[1])),
                format,
                cancel));
        var scene = buffer.getArea().getScene();
        if (scene != null && scene.getWindow() != null) {
            alert.initOwner(scene.getWindow());
        }
        alert.setTitle(tr("noUndo.op.format"));
        alert.setHeaderText(tr(
                "dialog.xml.formatGrowth.header", java.util.Objects.requireNonNullElse(buffer.getDisplayName(), "")));
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(format)).setDefaultButton(false);
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(cancel)).setDefaultButton(true);
        return alert.showAndWait().orElse(cancel) == format;
    }

    /**
     * {@code text}, an HTML page, laid out by the built-in {@link HtmlFormatter} with {@code indent} per level (HTML
     * review C17), or null when a tag, comment or script is left open.
     */
    static @Nullable String formatHtml(String text, @Nullable String indent) {
        return HtmlFormatter.format(text, indent == null || indent.isEmpty() ? "  " : indent);
    }

    /** {@code text} formatted with {@code indent} per level, or null when it does not nest properly. */
    static @Nullable String format(String text, @Nullable String indent) {
        return XmlFormatter.format(text, indent == null || indent.isEmpty() ? "  " : indent);
    }

    /**
     * Where a caret at {@code caret} in {@code before} belongs in {@code after}, a copy that differs only in
     * whitespace: on the same non-whitespace character. A caret just after a character stays just after it (the
     * end of a line stays the end of that line); one in whitespace before a character moves to just before it
     * (into the new indentation, not the end of the previous line).
     */
    static int caretAfter(String before, String after, int caret) {
        int at = Math.max(0, Math.min(caret, before.length()));
        int count = 0; // non-whitespace characters before the caret
        for (int i = 0; i < at; i++) {
            if (!Character.isWhitespace(before.charAt(i))) {
                count++;
            }
        }
        boolean stickToPrevious = at > 0 && !Character.isWhitespace(before.charAt(at - 1));
        int seen = 0;
        for (int i = 0; i < after.length(); i++) {
            if (Character.isWhitespace(after.charAt(i))) {
                continue;
            }
            if (!stickToPrevious && seen == count) {
                return i;
            }
            seen++;
            if (stickToPrevious && seen == count) {
                return i + 1;
            }
        }
        return after.length();
    }
}
