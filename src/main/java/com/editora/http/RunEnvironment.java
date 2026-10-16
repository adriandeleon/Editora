package com.editora.http;

import java.util.List;
import java.util.Map;

/**
 * The environment a run resolves against, read on the run's worker as the run starts — environment files are files,
 * so they are never read on the FX thread, and what is sent uses them as they are at that moment.
 */
@FunctionalInterface
public interface RunEnvironment {

    /** The variables of the selected environment, and why an env file contributes nothing (one line per file). */
    record Snapshot(Map<String, String> variables, List<String> problems) {

        public Snapshot {
            variables = variables == null ? Map.of() : variables;
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    /** Reads the environment now (on the run's worker). */
    Snapshot read();

    /** An environment already read: {@code variables}, no problems. */
    static RunEnvironment of(Map<String, String> variables) {
        Snapshot fixed = new Snapshot(variables, List.of());
        return () -> fixed;
    }
}
