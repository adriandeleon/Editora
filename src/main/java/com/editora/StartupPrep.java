package com.editora;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.config.SharedConfig;

/**
 * The launch's disk work besides the settings, done on the config-prefetch thread with them (PERF-01, Pauta
 * 6.0.1: before the first window is shown the FX thread may wait once for that thread, and do no file work of
 * its own but read the bundled resources the window is built from). Two steps, called by
 * {@link App#loadForFirstWindow} in this order:
 *
 * <ol>
 *   <li>{@link #beforeAnyServer}, right after the config is claimed and loaded: the session log is opened (what
 *       was logged before it is in memory, and is written to it first), the process ledgers are read and a
 *       crashed run's servers reaped, and a stale MCP endpoint is removed. All before any window exists, which
 *       is what starts servers.
 *   <li>{@link #forFirstWindow}, last: what the first window reads as it is built ({@link
 *       com.editora.ui.FirstWindowPrep}), and the build information it shows.
 * </ol>
 *
 * <p>Each piece is best-effort, as it was in {@code start()}: one that fails is logged and the launch goes on.
 */
final class StartupPrep {

    private static final Logger LOG = Logger.getLogger(StartupPrep.class.getName());

    private StartupPrep() {}

    /** The work that has to be done before anything can start a server. Blocking. */
    static void beforeAnyServer(SharedConfig shared) {
        Path configDir = shared.getConfigDir();
        // Where Editora installs java-debug, for the adapter search (here: forFirstWindow looks for it).
        com.editora.dap.DebugAdapterLocator.setEditoraConfigDir(configDir);
        // Mirror captured logs to a session file in the config dir so a packaged build's log survives a crash
        // and can be attached to a bug report (the in-memory capture was installed in main()).
        step("session log", () -> com.editora.ui.DebugLog.attachFile(configDir));
        // Point the spawned-server ledger at the config dir and reap any LSP/DAP server leaked by a previous run
        // that died too hard for the shutdown hook to fire (SIGKILL, power loss). Before any window is built
        // (which can start servers), so a fresh server's start is not raced. Only a dead owner's children are
        // reaped: a live second instance's servers are left alone.
        step("orphaned servers", () -> {
            com.editora.process.ProcessRegistry.setLedgerFile(configDir.resolve("spawned-servers.txt"));
            // Programs a crashed run left in a container or a WSL distribution: ending the launcher here did not
            // end them there. Each row says how to reach them; the target checks the pid is still the one recorded.
            com.editora.process.ProcessRegistry.registerOrphanReaper(
                    com.editora.container.ContainerExecTarget.ORPHAN_KIND,
                    com.editora.container.ContainerExecTarget::reapOrphan);
            com.editora.process.ProcessRegistry.registerOrphanReaper(
                    com.editora.wsl.WslExecTarget.ORPHAN_KIND, com.editora.wsl.WslExecTarget::reapOrphan);
            com.editora.process.ProcessRegistry.reapOrphans();
        });
        // Delete a mcp-endpoint.json left by a crashed run — it advertises a dead port with a live-looking token.
        // Reaped by the stamped pid + start instant, before any MCP server starts this session (#464).
        step("stale MCP endpoint", () -> com.editora.mcp.McpServer.reapStaleEndpoint(configDir));
        // The login shell's PATH, on a thread of its own: Run, Build and Debug find it built (PERF-01, Pauta 6.0.1).
        step("PATH warm-up", com.editora.process.ProcessRunner::warmAugmentedPathAsync);
        step(
                "read prompt shim",
                () -> Thread.ofVirtual().name("read-prompt-shim").start(com.editora.run.ReadPromptShim::prepare));
    }

    /** What the first window is built from, read ahead. {@code uiFontSheet} is the UI font's stylesheet. Blocking. */
    static void forFirstWindow(SharedConfig shared, String uiFontSheet) {
        step("build information", AppInfo::load);
        com.editora.ui.FirstWindowPrep.prepare(shared, List.of(uiFontSheet));
    }

    static void step(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Start-up step failed: " + what, e);
        }
    }
}
