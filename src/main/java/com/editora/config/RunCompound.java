package com.editora.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Run configurations debugged together, each in a session of its own, in the order listed — a client and its server,
 * say. {@code configurations} names saved {@link RunConfiguration}s; with {@code stopAll}, stopping one session of the
 * compound stops them all. Editora's own counterpart of a launch.json compound.
 *
 * <p>Persisted per project in {@code WorkspaceState.runCompounds}, beside the run configurations. Jackson round-trips
 * this record; the compact constructor defaults every field so an older or partial entry loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunCompound(String name, List<String> configurations, boolean stopAll) {

    public RunCompound {
        name = name == null ? "" : name;
        configurations = configurations == null
                ? List.of()
                : configurations.stream().filter(c -> c != null && !c.isBlank()).toList();
    }
}
