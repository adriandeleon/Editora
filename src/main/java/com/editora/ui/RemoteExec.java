package com.editora.ui;

import java.nio.file.Path;

import com.editora.process.ExecTargets;
import org.jspecify.annotations.Nullable;

/**
 * The gate for a feature that runs a tool where a file is: open for a file whose tools run on this machine,
 * and for one whose tools run elsewhere (a remote host, a container, WSL) while that target can run commands.
 * A feature that only works on this machine gates on {@link ExecTargets#runsHere}.
 */
final class RemoteExec {

    private RemoteExec() {}

    /** Whether a tool can be run for {@code path} right now. No I/O: safe on the FX thread. */
    static boolean runsFor(@Nullable Path path) {
        return ExecTargets.forPath(path).isAvailable();
    }
}
