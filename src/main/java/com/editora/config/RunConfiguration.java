package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A saved run/debug configuration for a Java project main class: a {@code name}, whether it runs or debugs
 * ({@code kind} = {@code "run"}/{@code "debug"}), the {@code mainClass} (fully-qualified) + its {@code
 * projectName} (multi-module), program {@code args}, JVM {@code vmArgs}, and an optional {@code workingDir}
 * (blank ⇒ the project root). Persisted per window in {@code WorkspaceState.runConfigurations}. Jackson
 * round-trips this record; the compact constructor defaults every field so an older/partial entry loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunConfiguration(
        String name, String kind, String mainClass, String projectName, String args, String vmArgs, String workingDir) {

    public RunConfiguration {
        name = name == null ? "" : name;
        kind = kind == null ? "run" : kind;
        mainClass = mainClass == null ? "" : mainClass;
        projectName = projectName == null ? "" : projectName;
        args = args == null ? "" : args;
        vmArgs = vmArgs == null ? "" : vmArgs;
        workingDir = workingDir == null ? "" : workingDir;
    }

    @JsonIgnore
    public boolean isDebug() {
        return "debug".equalsIgnoreCase(kind);
    }
}
