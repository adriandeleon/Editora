package com.editora.ui;

import java.nio.file.Path;
import java.util.Optional;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.format.PythonFormatter;

import static com.editora.i18n.Messages.tr;

/**
 * Format Document on a Python file (adl's decision 4): Ruff or Black, found in the file's environment first,
 * then on PATH, fed the buffer's text and its result applied as one undoable edit. A Python language server
 * that formats by itself ({@code ruff server}, {@code pylsp}) keeps the job — this is for Pyright, which
 * does not, and for when the language server is off.
 */
final class PythonFormatCoordinator {

    /** What this needs from its window. */
    interface Host {
        EditorBuffer activeBuffer();

        boolean activeEditable();

        /** Whether a running language server will format {@code file} itself. */
        boolean serverFormats(Path file);

        /** The environment folder of {@code file}'s interpreter, or null. Called off the FX thread. */
        Path venvOf(Path file);

        void setStatus(String message);

        void setError(String message);
    }

    private final Host host;

    PythonFormatCoordinator(Host host) {
        this.host = host;
    }

    /**
     * Formats the active buffer when it is a local Python file a language server will not format; true when it
     * took the job (whatever came of it), false to let Format Document go to the language server.
     */
    boolean formatActive() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !buffer.isPython()) {
            return false;
        }
        Path path = buffer.getPath();
        if (path != null && com.editora.vfs.Vfs.isRemote(path)) {
            return false; // a remote file's tools run on its host: the language server's job
        }
        if (path != null && host.serverFormats(path)) {
            return false;
        }
        if (!host.activeEditable() || buffer.isNarrowed()) {
            host.setStatus(tr("status.lsp.formatUnavailable"));
            return true;
        }
        String snapshot = buffer.getArea().getText();
        host.setStatus(tr("status.python.formatting"));
        Thread.ofVirtual().name("python-format").start(() -> {
            Optional<PythonFormatter.Found> found = PythonFormatter.findLocal(host.venvOf(path));
            if (found.isEmpty()) {
                Platform.runLater(() -> host.setError(tr("status.python.noFormatter")));
                return;
            }
            PythonFormatter.Outcome outcome = PythonFormatter.run(found.get(), path, snapshot);
            Platform.runLater(() -> apply(buffer, snapshot, found.get(), outcome));
        });
        return true;
    }

    private void apply(
            EditorBuffer buffer, String snapshot, PythonFormatter.Found formatter, PythonFormatter.Outcome outcome) {
        if (!outcome.ok()) {
            host.setError(outcome.error());
            return;
        }
        if (buffer != host.activeBuffer() || !buffer.getArea().getText().equals(snapshot)) {
            host.setStatus(tr("status.lsp.formatStale")); // edited (or left) mid-format: a re-run formats cleanly
            return;
        }
        int[] span = PythonFormatter.changedSpan(snapshot, outcome.text());
        if (span == null) {
            host.setStatus(tr("status.python.formatNoChange", formatter.name()));
            return;
        }
        if (!NoUndoGuard.allow(buffer, tr("noUndo.op.lsp"))) {
            return;
        }
        buffer.getArea().replaceText(span[0], span[1], outcome.text().substring(span[0], span[2]));
        host.setStatus(tr("status.python.formatted", formatter.name()));
    }
}
