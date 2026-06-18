package com.editora.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.process.ProcessRunner;

/**
 * Low-level facade for the ripgrep ({@code rg}) command, mirroring {@code mermaid/Mermaid}: an optional,
 * auto-detected external tool run through {@link ProcessRunner} (augmented PATH). The pure {@link RipgrepArgs}
 * (flag mapping) and {@link RipgrepOutput} ({@code --json} parsing) are unit-tested; this class only resolves
 * the command and probes for presence.
 */
public final class Ripgrep {

    /** The command when the user leaves the Settings path blank. */
    public static final String DEFAULT_COMMAND = "rg";

    private static final Duration DETECT_TIMEOUT = Duration.ofSeconds(10);

    private Ripgrep() {}

    /**
     * The command to invoke: the user-configured value if set, else {@code rg}; either may be a bare
     * executable or a multi-token command, split on whitespace. Pure; unit-tested.
     */
    public static List<String> command(String configured) {
        String raw = configured == null || configured.isBlank() ? DEFAULT_COMMAND : configured.strip();
        return List.of(raw.split("\\s+"));
    }

    /**
     * Whether {@code base} is launchable (rg present). Blocking; call off the FX thread. Any clean launch
     * (the runner's {@code exit != -1} failed-to-start sentinel) counts as present.
     */
    public static boolean detect(List<String> base) {
        List<String> cmd = new ArrayList<>(base);
        cmd.add("--version");
        return ProcessRunner.run(null, DETECT_TIMEOUT, cmd).exit() != -1;
    }
}
