package com.editora.process;

import java.nio.file.Path;

/**
 * A shell for the integrated terminal, on a target: where it starts and the size of the pseudo-terminal it is
 * given. Kept apart from {@link ExecSpec}, which every tool command uses and which never asks for a PTY: a PTY
 * on a language server's or debug adapter's channel would corrupt its framing (ADR 0013, ADR 0014).
 *
 * @param workingDir where the shell starts, a path on the target's file system; null for the account's home
 */
public record TerminalSpec(Path workingDir, int columns, int rows) {

    public TerminalSpec {
        columns = Math.max(1, columns);
        rows = Math.max(1, rows);
    }
}
