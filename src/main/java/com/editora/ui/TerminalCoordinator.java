package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.process.ProcessRegistry;
import com.editora.terminal.LinkActions;
import com.editora.terminal.LocalPtyConnector;
import com.editora.terminal.LocalShell;
import com.editora.terminal.RunningProgram;
import com.editora.terminal.TerminalConnector;
import com.editora.terminal.TerminalCursor;
import com.editora.terminal.TerminalPalettes;

import static com.editora.i18n.Messages.tr;

/**
 * The integrated terminal in a window: the Terminal tool window's panel, the {@code tool.terminal},
 * {@code terminal.new} and {@code terminal.close} commands, and the settings and theme it follows.
 * {@code MainController} keeps the {@code ToolWindow} (built with {@link #panel()}) and calls the apply hooks.
 *
 * <p>Shells run on this machine for now: in the project's folder when it is local, else beside the active
 * file, else in the home folder. A remote project's terminal is still a local one until its host gets a
 * terminal connector (ADR 0014).
 */
final class TerminalCoordinator {

    /** Window hooks beyond {@link CoordinatorHost}. */
    interface Ops {
        /** Where a new shell starts: a local folder, or null for the home folder. */
        Path workingDirectory();

        boolean isToolWindowOpen();

        /** Opens (or focuses) the Terminal tool window. */
        void showToolWindow();

        /** Closes the Terminal tool window. */
        void hideToolWindow();

        /**
         * Opens a link clicked in a terminal: a file at its 1-based {@code line}/{@code column} (0 = none), or a
         * folder, revealed in the Project tree.
         */
        void openLink(Path file, int line, int column);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private TerminalPanel panel;
    /** Whether the panel has opened its first shell: once, on first show, so a stripe click gets a prompt. */
    private boolean started;

    TerminalCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
    }

    /** The tool window's content, built on first use. */
    TerminalPanel panel() {
        if (panel == null) {
            panel = new TerminalPanel(this::localShell, this::confirmClose, ops::workingDirectory);
            panel.setOnError(host::setError);
            panel.setLinkActions(new LinkActions() {
                @Override
                public void openUrl(String url) {
                    host.openExternalUrl(url);
                }

                @Override
                public void openFile(Path file, int line, int column) {
                    ops.openLink(file, line, column);
                }
            });
            panel.apply(config(host.settings()));
            // The first time the panel reaches a scene the theme can be read, and a shell started: opening the
            // window from its stripe should give a prompt, not a "New Terminal" button.
            panel.sceneProperty().addListener((o, was, scene) -> {
                if (scene != null) {
                    applyTheme();
                    if (!started && !panel.hasTerminals()) {
                        started = true;
                        panel.newTerminal();
                    }
                }
            });
        }
        return panel;
    }

    void registerCommands(CommandRegistry registry) {
        registry.register(Command.of("tool.terminal", this::toggle));
        registry.register(Command.of("terminal.new", this::newTerminal));
        registry.register(Command.of("terminal.close", this::closeTerminal));
    }

    /** {@code tool.terminal}: hides an open terminal, or shows it with a shell ready and focused. */
    void toggle() {
        if (ops.isToolWindowOpen() && panel != null) {
            ops.hideToolWindow();
            return;
        }
        ops.showToolWindow();
        TerminalPanel p = panel();
        if (!p.hasTerminals()) {
            started = true;
            p.newTerminal();
        } else {
            p.focusActive();
        }
    }

    /** {@code terminal.new}: another shell, in a new tab. */
    void newTerminal() {
        boolean fresh = panel == null || !panel.hasTerminals();
        ops.showToolWindow();
        TerminalPanel p = panel();
        // Showing the window may already have started the first shell; a second one would be a surprise.
        if (!fresh || !p.hasTerminals()) {
            started = true;
            p.newTerminal();
        }
    }

    /**
     * "Open in Terminal": a new tab with a shell in {@code dir}. The first shell the tool window would start on
     * its own is skipped, so the window does not open with a second tab in the project folder.
     */
    void openAt(Path dir) {
        started = true;
        ops.showToolWindow();
        panel().newTerminal(dir);
    }

    /** {@code terminal.close}: closes the selected terminal, asking if its shell is running a program. */
    void closeTerminal() {
        if (panel != null) {
            panel.closeActive();
        }
    }

    /** Settings changed: font, cursor and bell now; scrollback and shell from the next tab. */
    void applySettings() {
        if (panel != null) {
            panel.apply(config(host.settings()));
        }
    }

    /** The editor or app theme changed: read the colours again. */
    void applyTheme() {
        if (panel != null) {
            panel.refreshPalette(host.appThemeDark() ? TerminalPalettes.EDITORA_DARK : TerminalPalettes.EDITORA_LIGHT);
        }
    }

    /** Ends every shell (window closing). */
    void shutdown() {
        if (panel != null) {
            panel.shutdown();
        }
    }

    /** The settings snapshot a panel works from. */
    static TerminalPanel.Config config(Settings s) {
        TerminalCursor cursor =
                switch (s.getTerminalCursor()) {
                    case "underline" -> TerminalCursor.UNDERLINE;
                    case "bar" -> TerminalCursor.BAR;
                    default -> TerminalCursor.BLOCK;
                };
        int size = s.getTerminalFontSize() > 0 ? s.getTerminalFontSize() : s.getFontSize();
        return new TerminalPanel.Config(
                s.getFontFamily(),
                size,
                s.getTerminalScrollback(),
                cursor,
                s.isTerminalBell(),
                s.getTerminalShell(),
                s.getTerminalIdeCommands(),
                s.isTerminalConfirmClose());
    }

    /** A shell on this machine, tracked so it ends with Editora even when Editora does not end cleanly. */
    private TerminalConnector.Factory localShell(Path directory, String shell) {
        return (columns, rows) -> {
            List<String> command = shell == null || shell.isBlank() ? List.of() : LocalShell.shellCommand(shell);
            LocalPtyConnector connector =
                    LocalShell.start(command, directory == null ? null : directory.toString(), columns, rows);
            try {
                ProcessRegistry.track(connector.trackableProcess());
            } catch (RuntimeException e) {
                // The shell is already running: losing the crash-time reap is better than losing the shell.
                java.util.logging.Logger.getLogger(TerminalCoordinator.class.getName())
                        .warning("terminal shell " + connector.pid() + " is not tracked: " + e);
            }
            return connector;
        };
    }

    /** Asks before ending a shell that is running something; Cancel is the default, so Enter keeps it. */
    private boolean confirmClose(String title, List<String> programs) {
        ButtonType close = new ButtonType(tr("terminal.close.confirm"), ButtonBar.ButtonData.OK_DONE);
        String running = programs.size() <= RunningProgram.MAX_NAMED
                ? String.join(", ", programs)
                : String.join(", ", programs.subList(0, RunningProgram.MAX_NAMED)) + ", …";
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION, tr("terminal.close.running", title, running), close, ButtonType.CANCEL));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("terminal.close.title"));
        confirm.setHeaderText(null);
        ((Button) confirm.getDialogPane().lookupButton(close)).setDefaultButton(false);
        ((Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == close;
    }
}
