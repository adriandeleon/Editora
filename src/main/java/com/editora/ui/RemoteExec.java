package com.editora.ui;

import java.nio.file.Path;

import com.editora.process.ExecTargets;
import com.editora.vfs.Vfs;

/**
 * The gate for a feature that runs a tool where a file is: open for every local file, and for a remote one
 * while its host can run commands (connected, with the user's {@code remoteExecution} setting on). A feature
 * that only works on this machine keeps gating on {@link Vfs#isLocal}.
 */
final class RemoteExec {

    private RemoteExec() {}

    /** Whether a tool can be run for {@code path} right now. No I/O: safe on the FX thread. */
    static boolean runsFor(Path path) {
        return Vfs.isLocal(path) || ExecTargets.forPath(path).isAvailable();
    }
}
