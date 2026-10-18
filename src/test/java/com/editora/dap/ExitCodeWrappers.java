package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;

/** Test help for launches whose {@code java} runs behind {@link JavaExitCodeWrapper}. */
public final class ExitCodeWrappers {

    private ExitCodeWrappers() {}

    /**
     * The {@code java} a launch's {@code javaExec} runs: itself, or the one its exit-code wrapper runs (read
     * from the script). For tests that check which JDK a launch uses.
     */
    public static String wrappedJava(Object javaExec) {
        String exec = String.valueOf(javaExec);
        Path script = Path.of(exec);
        if (!script.toString().contains("editora-jexit-") || !Files.isRegularFile(script)) {
            return exec;
        }
        try {
            for (String line : Files.readAllLines(script)) {
                if (line.startsWith("'") && line.contains("' \"$@\"")) {
                    return line.substring(1, line.indexOf("' \"$@\"")).replace("'\\''", "'");
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("no java in " + script);
    }
}
