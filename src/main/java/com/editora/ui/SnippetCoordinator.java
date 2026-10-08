package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.snippet.SnippetManager;
import com.editora.snippet.SnippetManager.Problem;

import static com.editora.i18n.Messages.tr;

/**
 * This window's snippets: the {@link SnippetManager}, what each buffer is wired to for Tab expansion, the
 * snippet commands, the status-bar hint for a running session, and keeping every window's cache in step
 * with the user snippet files.
 *
 * <p>Each window has its own manager (plugins add per-window sources), so a change to a user file — saved
 * from Settings, or saved in the editor as an ordinary file — is announced through {@link Ops#broadcast}
 * and the other windows drop their caches ({@link #changedElsewhere}), the way saved macros do.
 *
 * <p>A user file that cannot be used is reported once per load, as an error with the file name, the
 * reason and the line — at startup, on reload, and when the file is saved — rather than staying silent
 * until someone opens Settings.
 */
final class SnippetCoordinator {

    /** What the coordinator needs from the window beyond {@link CoordinatorHost}. */
    interface Ops {
        void openPath(Path file);

        void showPicker();

        /** The active project's root, or null. */
        Path projectRoot();

        /** Tells the other windows a user snippet file changed. */
        void broadcast();

        StatusBar statusBar();

        SettingsWindow settingsWindow();
    }

    private static final String USER_SNIPPET_TEMPLATE = """
            {
              // Each entry is a snippet: "prefix" is what you type before Tab, "body" what it expands to.
              // $1, $2 are tab stops, ${1:default} a placeholder, $0 where the caret ends up.
              // Saving this file reloads your snippets.
              "Example": {
                "prefix": "ex",
                "body": ["// ${1:summary}", "$0"],
                "description": "Example snippet — edit or add your own"
              }
            }
            """;

    private final CoordinatorHost host;
    private final Ops ops;
    private final SnippetManager manager;
    /** The buffer whose session the status bar is showing, or null. */
    private EditorBuffer shown;

    SnippetCoordinator(ConfigManager config, CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.manager = new SnippetManager(config);
        manager.setProblemListener(p -> onFx(() -> host.setError(describe(p))));
        manager.setOnUserFilesChanged(() -> onFx(ops::broadcast)); // a save from Settings
    }

    SnippetManager manager() {
        return manager;
    }

    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** Gives a new buffer its snippet lookup, the Tab-expansion setting and the session hint. */
    void wireBuffer(EditorBuffer buffer) {
        buffer.setSnippetProvider(manager::byTabTrigger);
        buffer.setSnippetTabExpansion(host.settings().isSnippetTabExpansion());
        buffer.setSnippetHooks(file -> ops.projectRoot(), progress -> sessionMoved(buffer, progress));
    }

    /** Re-applies {@code Settings.snippetTabExpansion} to every open buffer. */
    void applySettings() {
        boolean on = host.settings().isSnippetTabExpansion();
        host.forEachBuffer(b -> b.setSnippetTabExpansion(on));
    }

    void toggleTabExpansion() {
        Settings s = host.settings();
        s.setSnippetTabExpansion(!s.isSnippetTabExpansion());
        host.requestSave();
        applySettings();
        host.syncSettingsWindow();
        host.setStatus(
                tr("status.toggle.snippetTabExpansion", tr(s.isSnippetTabExpansion() ? "common.on" : "common.off")));
    }

    /** A session in {@code buffer} started, moved to another field, or (null) ended. */
    private void sessionMoved(EditorBuffer buffer, int[] progress) {
        if (progress == null) {
            if (shown == buffer) {
                shown = null;
                ops.statusBar().setSnippetSession(0, 0);
            }
            return;
        }
        if (buffer == host.activeBuffer()) {
            shown = buffer;
            ops.statusBar().setSnippetSession(progress[0], progress[1]);
        }
    }

    /** Leaves the active buffer's snippet session (what Escape does; the status-bar hint runs it on click). */
    void endSession() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            b.endSnippetSession();
        }
    }

    /** Opens the snippet picker for the active buffer's language (plus global snippets). */
    void showPicker() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        if (manager.forLanguage(b.getLanguage()).isEmpty()) {
            host.setStatus(tr("status.noSnippets"));
            return;
        }
        ops.showPicker();
    }

    /** Opens (creating from a template if needed) the user snippet file for the active language. */
    void editUserSnippets() {
        EditorBuffer b = host.activeBuffer();
        String lang = b == null ? "global" : b.getLanguage();
        Path file = manager.userFile(lang);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, USER_SNIPPET_TEMPLATE);
            }
            ops.openPath(file);
            host.setStatus(tr("status.editingSnippets", lang));
        } catch (IOException e) {
            host.setStatus(tr("status.snippetOpenFailed", e.getMessage()));
        }
    }

    /** The Reload Snippets command: re-reads everything here and in the other windows, and says what is wrong. */
    void reload() {
        manager.reload();
        List<Problem> problems = manager.checkUserFiles(); // each one is reported by the listener
        ops.settingsWindow().reloadSnippetsPage();
        ops.broadcast();
        if (problems.isEmpty()) {
            host.setStatus(tr("status.snippetsReloaded"));
        }
    }

    /** Another window changed a user snippet file: this window's cache and Settings page follow, quietly. */
    void changedElsewhere() {
        manager.reload();
        ops.settingsWindow().reloadSnippetsPage();
    }

    /** A file was saved in the editor; when it is a user snippet file its snippets are live at once. */
    void fileSaved(Path file) {
        String language = manager.userFileLanguage(file);
        if (language == null) {
            return;
        }
        manager.reload();
        boolean broken = manager.wholeFileProblem(language) != null; // loads the file; problems are reported
        ops.settingsWindow().reloadSnippetsPage();
        ops.broadcast();
        if (!broken && manager.checkUserFiles().stream().noneMatch(p -> p.file().equals(manager.userFile(language)))) {
            host.setStatus(tr("status.snippetFileReloaded", file.getFileName()));
        }
    }

    /** Reads the user snippet files off the FX thread once the window is up, so a broken one is known now. */
    void checkUserFilesAtStartup() {
        Thread.ofVirtual().name("snippet-file-check").start(manager::checkUserFiles);
    }

    /** One line for the error channel: the file, what is wrong, and where. No parser internals. */
    static String describe(Problem p) {
        return switch (p.kind()) {
            case BAD_ENTRY -> tr("status.snippetEntryError", p.fileName(), p.entry(), p.line());
            case NOT_AN_OBJECT -> tr("status.snippetFileShapeError", p.fileName());
            case UNREADABLE -> tr("status.snippetFileReadError", p.fileName(), p.detail());
            case SYNTAX ->
                p.line() > 0
                        ? tr("status.snippetFileSyntaxError", p.fileName(), p.detail(), p.line())
                        : tr("status.snippetFileReadError", p.fileName(), p.detail());
        };
    }
}
