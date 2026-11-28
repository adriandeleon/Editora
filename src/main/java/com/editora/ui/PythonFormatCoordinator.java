package com.editora.ui;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.format.PythonFormatter;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Format Document on a Python file (adl's decision 4): Ruff or Black, found in the file's environment first,
 * then on PATH, fed the buffer's text and its result applied as one undoable edit. A Python language server
 * that formats by itself ({@code ruff server}, {@code pylsp}) keeps the job — this is for Pyright, which
 * does not, and for when the language server is off. With Settings' Python format on save on, the same runs after
 * each explicit save ({@link #afterUserSave}).
 */
final class PythonFormatCoordinator {

    /** What this needs from its window. */
    interface Host {
        @Nullable
        EditorBuffer activeBuffer();

        boolean activeEditable();

        /** Whether a running language server will format {@code file} itself. */
        boolean serverFormats(Path file);

        /** The environment folder of {@code file}'s interpreter, or null. Called off the FX thread. */
        @Nullable
        Path venvOf(@Nullable Path file);

        /**
         * The environment the folder of {@code file} ships but that is not used because the folder is not trusted, or null
         * (a formatter in it would run the folder's code: HN dry run, 2026-11-27).
         */
        default @Nullable Path withheldVenvOf(@Nullable Path file) {
            return null;
        }

        void setStatus(String message);

        void setError(@Nullable String message);
    }

    private final Host host;

    /** The window's settings: whether to format on save. */
    private final Supplier<Settings> settings;

    /** Saves a buffer to its file off the FX thread, then says on it whether it could (PERF-01). */
    private final java.util.function.BiConsumer<EditorBuffer, java.util.function.Consumer<Boolean>> save;

    /** Buffers being saved again by {@link #afterUserSave}: that save does not start another round. */
    private final Set<EditorBuffer> savingAgain = Collections.newSetFromMap(new WeakHashMap<>());

    PythonFormatCoordinator(
            Host host,
            Supplier<Settings> settings,
            java.util.function.BiConsumer<EditorBuffer, java.util.function.Consumer<Boolean>> save) {
        this.host = host;
        this.settings = settings;
        this.save = save;
    }

    /**
     * Formats the active buffer when it is a Python file a language server will not format; true when it took the
     * job (whatever came of it), false to let Format Document go to the language server.
     */
    boolean formatActive() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !buffer.isPython()) {
            return false;
        }
        Path path = buffer.getPath();
        if (path != null && host.serverFormats(path)) {
            return false; // a Python server that formats by itself (ruff server, pylsp), here or on the file's host
        }
        String refusal = refusal(path, buffer.isNarrowed(), host.activeEditable());
        if (refusal != null) {
            host.setStatus(tr(refusal));
            return true;
        }
        format(buffer, false, formatted -> {});
        return true;
    }

    /**
     * Pure: why Ruff/Black cannot format this buffer now, as a message key, or null when they can (Python review 2,
     * A112). Each case used to say "No formatter available for this file", even with Ruff installed.
     */
    static @Nullable String refusal(@Nullable Path path, boolean narrowed, boolean editable) {
        if (path != null && !com.editora.process.ExecTargets.runsHere(path)) {
            return "status.python.formatRemote"; // the tools would have to run on the file's host
        }
        if (narrowed) {
            return "status.python.formatNarrowed";
        }
        return editable ? null : "status.lsp.readOnly";
    }

    /**
     * After an explicit save of a local Python file, with Settings' Python format on save on: Ruff or Black, then
     * the file is saved again when they changed it (Python review 2, A111). Never on autosave (the caller is the
     * explicit-save path). Typing in the meantime drops the format, as a stale result would be wrong.
     */
    void afterUserSave(@Nullable EditorBuffer buffer) {
        if (buffer == null || savingAgain.remove(buffer)) {
            return;
        }
        Path path = buffer.getPath();
        if (!settings.get().isPythonFormatOnSave()
                || !buffer.isPython()
                || path == null
                || refusal(path, buffer.isNarrowed(), buffer.isEditable()) != null
                || buffer.isDisposed()
                || buffer.isLargeFile()) {
            return;
        }
        String saved = buffer.getContent();
        format(buffer, true, formatted -> {
            if (formatted
                    && !buffer.isDisposed()
                    && buffer.isDirty()
                    && !buffer.getContent().equals(saved)) {
                savingAgain.add(buffer);
                save.accept(buffer, written -> {
                    if (!written) {
                        savingAgain.remove(buffer);
                    }
                });
            }
        });
    }

    /**
     * Runs Ruff or Black on {@code buffer}'s text off the FX thread and applies the result as one edit; {@code done}
     * hears on the FX thread whether the text changed. {@code quiet}: a save's format, which says nothing when
     * nothing changed and does not announce itself.
     */
    private void format(EditorBuffer buffer, boolean quiet, Consumer<Boolean> done) {
        Path path = buffer.getPath();
        String snapshot = buffer.getArea().getText();
        if (!quiet) {
            host.setStatus(tr("status.python.formatting"));
        }
        Thread.ofVirtual().name("python-format").start(() -> {
            Optional<PythonFormatter.Found> found = PythonFormatter.findLocal(host.venvOf(path));
            Path withheld = found.isEmpty() ? host.withheldVenvOf(path) : null;
            // The folder's own environment has one, waiting for trust: say so, not "install one" (only looked at).
            String why = withheld != null && PythonFormatter.findLocal(withheld).isPresent()
                    ? tr("status.python.formatNeedsTrust", String.valueOf(withheld.getFileName()))
                    : tr("status.python.noFormatter");
            if (found.isEmpty()) {
                Platform.runLater(() -> {
                    if (quiet) {
                        host.setStatus(why); // every save: told, not alarmed
                    } else {
                        host.setError(why);
                    }
                    done.accept(false);
                });
                return;
            }
            PythonFormatter.Outcome outcome = PythonFormatter.run(found.get(), path, snapshot);
            Platform.runLater(() -> done.accept(apply(buffer, snapshot, found.get(), outcome, quiet)));
        });
    }

    /** Puts the formatter's result into {@code buffer}; true when the text changed. FX thread. */
    private boolean apply(
            EditorBuffer buffer,
            String snapshot,
            PythonFormatter.Found formatter,
            PythonFormatter.Outcome outcome,
            boolean quiet) {
        String text = outcome.text(); // set whenever the formatter succeeded
        if (!outcome.ok() || text == null) {
            host.setError(outcome.error()); // the text stays as it was
            return false;
        }
        // Left for another tab mid-format, it still goes into its own buffer; edited, a re-run formats cleanly.
        if (buffer.isDisposed() || !buffer.getArea().getText().equals(snapshot)) {
            if (!quiet) {
                host.setStatus(tr("status.lsp.formatStale"));
            }
            return false;
        }
        int[] span = PythonFormatter.changedSpan(snapshot, text);
        if (span == null) {
            if (!quiet) {
                host.setStatus(tr("status.python.formatNoChange", formatter.name()));
            }
            return false;
        }
        if (!NoUndoGuard.allow(buffer, tr("noUndo.op.format"))) {
            return false;
        }
        buffer.getArea().replaceText(span[0], span[1], text.substring(span[0], span[2]));
        host.setStatus(tr("status.python.formatted", formatter.name()));
        return true;
    }
}
