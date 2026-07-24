package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A saved run/debug configuration for a Java project main class: a {@code name}, whether it runs or debugs
 * ({@code kind} = {@code "run"}/{@code "debug"}), the {@code mainClass} (fully-qualified) + its {@code
 * projectName} (multi-module), program {@code args}, JVM {@code vmArgs}, an optional {@code workingDir}
 * (blank ⇒ the project root), and {@code env} — environment variables as quote-aware {@code KEY=VALUE} pairs
 * (see {@code run/EnvVars}). Persisted per window in {@code WorkspaceState.runConfigurations}. Jackson
 * round-trips this record; the compact constructor defaults every field so an older/partial entry loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunConfiguration(
        String name,
        String kind,
        String mainClass,
        String projectName,
        String args,
        String vmArgs,
        String workingDir,
        String env) {

    public RunConfiguration {
        name = name == null ? "" : name;
        kind = kind == null ? "run" : kind;
        mainClass = mainClass == null ? "" : mainClass;
        projectName = projectName == null ? "" : projectName;
        args = args == null ? "" : args;
        vmArgs = vmArgs == null ? "" : vmArgs;
        workingDir = workingDir == null ? "" : workingDir;
        env = env == null ? "" : env;
    }

    /** Back-compat constructor for callers that don't set environment variables ({@code env} = {@code ""}). */
    public RunConfiguration(
            String name,
            String kind,
            String mainClass,
            String projectName,
            String args,
            String vmArgs,
            String workingDir) {
        this(name, kind, mainClass, projectName, args, vmArgs, workingDir, "");
    }

    @JsonIgnore
    public boolean isDebug() {
        return "debug".equalsIgnoreCase(kind);
    }
}
