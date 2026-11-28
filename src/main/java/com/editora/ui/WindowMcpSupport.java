package com.editora.ui;

import java.nio.file.Path;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.ClipboardContent;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A window's side of the app-wide MCP server (started by the first window with the feature on, handed to a
 * surviving window when its owner closes) and of the Claude Code IDE trial (this window's lock file and server),
 * with their commands: turn MCP on or off, copy its connect commands, send the selection to Claude Code. The
 * server and its owner stay static fields of {@link MainController}. It lived inside {@code MainController}; it is
 * a file of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowMcpSupport {

    private final MainController mc;

    WindowMcpSupport(MainController mc) {
        this.mc = mc;
    }

    // --- MCP server (loopback HTTP, exposes editor state + commands to an LLM agent) --------------

    boolean mcpEnabled() {
        // Simple UI mode disables the MCP server too (without changing the saved setting).
        return mc.config.getSettings().isMcpSupport() && !mc.chrome.simpleModeActive();
    }

    /**
     * Reconciles the MCP server with its setting (mirrors {@link HttpClientCoordinator#applySupport}). Starts the
     * single app-wide loopback server when first enabled (this window becomes its bridge), or stops it
     * when disabled. Runs at startup and on every settings apply.
     */
    void applyMcpSupport() {
        boolean on = mcpEnabled();
        if (on && MainController.mcpServer == null) {
            // The loopback socket is bound and the server started off the FX thread (PERF-01, Pauta 6.2.0); the
            // status bar says it runs once it does.
            com.editora.mcp.McpServer s = new com.editora.mcp.McpServer(mc, mc.config.getConfigDir());
            MainController.mcpServer = s;
            MainController.mcpOwner = mc;
            Thread.ofVirtual().name("mcp-start").start(() -> {
                Exception failed = null;
                try {
                    s.start();
                } catch (Exception e) {
                    failed = e;
                }
                Exception why = failed;
                Platform.runLater(() -> {
                    if (why != null) {
                        MainController.LOG.log(java.util.logging.Level.WARNING, "MCP server failed to start", why);
                        if (MainController.mcpServer == s) {
                            MainController.mcpServer = null;
                            MainController.mcpOwner = null;
                        }
                        mc.setStatus(tr("status.mcp.failed"));
                    } else if (MainController.mcpServer != s) {
                        Thread.ofVirtual().name("mcp-stop").start(s::stop); // turned off while it started
                    }
                    // The owner may have changed while it started (this window closed): tell the owner's bar.
                    MainController owner = MainController.mcpOwner != null ? MainController.mcpOwner : mc;
                    owner.statusBar.setMcpRunning(
                            MainController.mcpServer != null && MainController.mcpServer.isRunning());
                    // Settings read the status when the switch was flipped, before the server was up: read it again.
                    owner.settingsWindow.syncMcpCheck();
                    if (owner != mc) {
                        mc.settingsWindow.syncMcpCheck();
                    }
                });
            });
        } else if (!on && MainController.mcpServer != null && MainController.mcpOwner == mc) {
            com.editora.mcp.McpServer s = MainController.mcpServer;
            Thread.ofVirtual().name("mcp-stop").start(s::stop); // waits for a start under way, off the FX thread
            MainController.mcpServer = null;
            MainController.mcpOwner = null;
        }
        mc.statusBar.setMcpRunning(MainController.mcpServer != null && MainController.mcpServer.isRunning());
        applyClaudeIde();
    }

    /** This window's Claude Code IDE trial server, while its setting and MCP are on. */
    private com.editora.mcp.ide.@Nullable ClaudeIdeServer claudeIde;

    /**
     * Reconciles this window's Claude Code IDE trial with its setting (experimental, off by default; it needs the MCP
     * server's switch too): writes the lock file Claude Code finds and serves the IDE tools, or stops and removes it.
     * Started off the FX thread. Runs with {@link #applyMcpSupport}.
     */
    void applyClaudeIde() {
        boolean on = mcpEnabled() && mc.config.getSettings().isClaudeIdeTrial();
        if (on && claudeIde == null) {
            Path dir = com.editora.mcp.ide.ClaudeIdeServer.ideDir(
                    System.getenv("CLAUDE_CONFIG_DIR"), Path.of(System.getProperty("user.home")));
            Thread.ofVirtual().name("claude-ide-start").start(() -> {
                try {
                    var server = new com.editora.mcp.ide.ClaudeIdeServer(
                            mc, this::claudeIdeFolders, dir, com.editora.AppInfo.VERSION);
                    Platform.runLater(() -> {
                        if (claudeIde != null
                                || !(mcpEnabled() && mc.config.getSettings().isClaudeIdeTrial())) {
                            server.close(); // turned off, or another start won, meanwhile
                            return;
                        }
                        claudeIde = server;
                        refreshClaudeIde(); // the project may have been set while the server was starting
                        com.editora.terminal.LocalShell.setExtraEnvironment(java.util.Map.of(
                                "CLAUDE_CODE_SSE_PORT",
                                String.valueOf(server.port()),
                                "ENABLE_IDE_INTEGRATION",
                                "true"));
                        mc.setStatus(tr("status.claudeIde.started", server.port()));
                    });
                } catch (java.io.IOException | RuntimeException e) {
                    MainController.LOG.log(java.util.logging.Level.WARNING, "Claude Code IDE trial failed to start", e);
                    Platform.runLater(
                            () -> mc.setStatus(tr("status.claudeIde.failed", String.valueOf(e.getMessage()))));
                }
            });
        } else if (!on && claudeIde != null) {
            stopClaudeIde();
        }
    }

    /** The window's folders as Claude Code's lock file names them: its project's root, or none. */
    private java.util.List<Path> claudeIdeFolders() {
        Path root = mc.windowProjectRoot();
        return root == null ? java.util.List.of() : java.util.List.of(root);
    }

    /** The window's project changed: the lock file names the new folder (off the FX thread). */
    void refreshClaudeIde() {
        var server = claudeIde;
        if (server != null) {
            Thread.ofVirtual().name("claude-ide-lock").start(() -> {
                try {
                    server.refreshLockFile();
                } catch (java.io.IOException e) {
                    MainController.LOG.log(
                            java.util.logging.Level.FINE, "Could not rewrite the Claude Code lock file", e);
                }
            });
        }
    }

    private void stopClaudeIde() {
        var server = claudeIde;
        claudeIde = null;
        if (server == null) {
            return;
        }
        String port = String.valueOf(server.port());
        if (port.equals(com.editora.terminal.LocalShell.extraEnvironment().get("CLAUDE_CODE_SSE_PORT"))) {
            com.editora.terminal.LocalShell.setExtraEnvironment(java.util.Map.of());
        }
        Thread.ofVirtual().name("claude-ide-stop").start(server::close);
    }

    /**
     * {@code claudeIde.sendSelection}: the active file's selected lines (the caret's line when none) sent to a
     * connected Claude Code as an @-mention, as the editor plugins send one.
     */
    void sendSelectionToClaude() {
        var server = claudeIde;
        EditorBuffer b = mc.activeBuffer();
        if (server == null) {
            mc.setStatus(tr("status.claudeIde.off"));
            return;
        }
        if (b == null || b.getPath() == null) {
            mc.setStatus(tr("status.claudeIde.noFile"));
            return;
        }
        var area = b.getArea();
        var sel = area.getSelection();
        int first = area.offsetToPosition(sel.getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        int last = sel.getLength() == 0
                ? first
                : area.offsetToPosition(sel.getEnd() - 1, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                        .getMajor();
        int offset = mc.navigation.documentLine(b, 0); // a narrowed buffer's lines are counted in the whole file
        Path file = b.getPath();
        int from = first + 1 + offset;
        int to = last + 1 + offset;
        Thread.ofVirtual().name("claude-ide-mention").start(() -> {
            boolean sent = server.atMention(file, from, to);
            Platform.runLater(
                    () -> mc.setStatus(tr(sent ? "status.claudeIde.mentioned" : "status.claudeIde.notConnected")));
        });
    }

    /**
     * A security-notice confirmation shown before the MCP server is enabled (from the palette toggle and
     * the Settings checkbox). Returns true to proceed. Mirrors {@link #confirmEnablePlugin}.
     */
    boolean confirmEnableMcp() {
        ButtonType enable = Dialogs.action(tr("confirm.action.enable"));
        ButtonType cancel = Dialogs.cancel();
        Alert confirm = Dialogs.styled(new Alert(Alert.AlertType.WARNING, tr("dialog.mcp.enableBody"), enable, cancel));
        confirm.initOwner(mc.stage);
        confirm.setTitle(tr("dialog.mcp.enableTitle"));
        confirm.setHeaderText(tr("dialog.mcp.enableHeader"));
        UiZoom.minWidth(confirm.getDialogPane(), 480);
        return Dialogs.showAndWait(confirm).orElse(cancel) == enable;
    }

    /**
     * Handles the MCP server when this window (its owner) closes (called from {@link MainController#disposeWindow}). If
     * another window is still open, ownership <b>moves</b> to it — the server is re-bound to that window's
     * bridge (same port/token/endpoint), so a connected agent isn't dropped and the survivor's indicator is
     * refreshed. Only when this is the last window is the server actually stopped (#463).
     */
    void stopMcpIfOwner() {
        stopClaudeIde(); // this window's lock file goes with it, whoever owns the MCP server
        if (MainController.mcpServer == null || MainController.mcpOwner != mc) {
            return;
        }
        MainController survivor = mc.windowManager != null ? mc.windowManager.otherLiveController(mc) : null;
        if (survivor != null) {
            MainController.mcpServer.rebind(
                    survivor); // move ownership; keep the server running for the surviving window
            MainController.mcpOwner = survivor;
            survivor.statusBar.setMcpRunning(MainController.mcpServer.isRunning());
        } else {
            com.editora.mcp.McpServer last =
                    MainController.mcpServer; // last window — really stop + remove the endpoint file,
            Thread.ofVirtual().name("mcp-stop").start(last::stop); // off the FX thread (a file left is reaped)
            MainController.mcpServer = null;
            MainController.mcpOwner = null;
        }
    }

    /** Runs {@code action} only when the MCP server is enabled; otherwise reports it (no-op). */
    void ifMcp(Runnable action) {
        if (mcpEnabled()) {
            action.run();
        } else {
            mc.setStatus(tr("statusbar.tip.mcpDisabled"));
        }
    }

    /** {@code view.toggleMcp}: flip the feature (with a security notice before enabling), re-apply, re-sync. */
    void toggleMcpSupport() {
        Settings s = mc.config.getSettings();
        boolean turningOn = !s.isMcpSupport();
        if (turningOn && !confirmEnableMcp()) {
            return; // user declined the security notice; leave the server off
        }
        s.setMcpSupport(turningOn);
        mc.config.save();
        applyMcpSupport();
        if (mc.settingsWindow != null) {
            mc.settingsWindow.syncMcpCheck();
        }
        mc.setStatus(tr("status.toggle.mcp", tr(s.isMcpSupport() ? "common.on" : "common.off")));
    }

    /**
     * {@code mcp.copyEndpoint}: copies a {@code claude mcp add} command that starts {@code editora mcp}, which finds
     * the running server by itself — set up once, it outlives restarts and new tokens, and it holds no token. Works
     * while the server is off too (the status line says to turn it on).
     */
    void copyMcpEndpoint() {
        String cmd = com.editora.mcp.McpConnectCommand.stdio(mc.config.getConfigDir());
        ClipboardContent cc = new ClipboardContent();
        cc.putString(cmd);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        boolean running = MainController.mcpServer != null && MainController.mcpServer.isRunning();
        mc.setStatus(tr(running ? "status.mcp.endpointCopied" : "status.mcp.connectCopiedOff"));
    }

    /** {@code mcp.copyHttpEndpoint}: the direct HTTP endpoint command (URL + bearer token), valid for this run only. */
    void copyMcpHttpEndpoint() {
        if (MainController.mcpServer == null || !MainController.mcpServer.isRunning()) {
            mc.setStatus(tr("status.mcp.notRunning"));
            return;
        }
        String cmd = com.editora.mcp.McpConnectCommand.http(
                MainController.mcpServer.url(), MainController.mcpServer.token());
        ClipboardContent cc = new ClipboardContent();
        cc.putString(cmd);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        mc.setStatus(tr("status.mcp.endpointCopied"));
    }
}
