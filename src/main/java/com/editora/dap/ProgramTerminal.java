package com.editora.dap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import com.editora.terminal.ProgramRun;
import org.jspecify.annotations.Nullable;

/**
 * Where a debugged program runs when it should have a real terminal (Settings → Debug): a Terminal tab, which the
 * window supplies. The program is the adapter's {@code runInTerminal} command, untouched.
 */
@FunctionalInterface
public interface ProgramTerminal {

    /**
     * Starts {@code argv} in {@code dir} (a folder of the adapter's machine: this one, or a remote file's host)
     * with {@code env} over its environment, a null value unsetting. {@code exited} gets the exit code on the FX
     * thread, -1 when there was none.
     */
    ProgramRun start(String title, @Nullable Path dir, List<String> argv, Map<String, String> env, IntConsumer exited)
            throws IOException;
}
