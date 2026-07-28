package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A saved run/debug configuration: a {@code name}, whether it runs or debugs
 * ({@code kind} = {@code "run"}/{@code "debug"}), the {@code mainClass} (fully-qualified) + its {@code
 * projectName} (multi-module), program {@code args}, JVM {@code vmArgs}, an optional {@code workingDir}
 * (blank ⇒ the project root), and {@code env} — environment variables as quote-aware {@code KEY=VALUE} pairs
 * (see {@code run/EnvVars}).
 *
 * <p>{@code type} says <em>what</em> is launched — {@code java} (the {@code mainClass}, resolved through
 * jdtls) or a script type handled by {@code run/ScriptRunCommand}, whose {@code target} is the script path or
 * make target. It is orthogonal to {@code kind}, which says whether to run or debug. Absent from an older
 * entry it defaults to {@code java}, so every configuration saved before types existed keeps working.
 *
 * <p>Persisted per window in {@code WorkspaceState.runConfigurations}. Jackson
 * round-trips this record; the compact constructor defaults every field so an older/partial entry loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunConfiguration(
        String name,
        String kind,
        String type,
        String target,
        String mainClass,
        String projectName,
        String args,
        String vmArgs,
        String workingDir,
        String env) {

    /** Prefix of the synthetic per-configuration commands, so stale ones can be found and dropped. */
    public static final String COMMAND_PREFIX = "run.config.";

    public RunConfiguration {
        name = name == null ? "" : name;
        kind = kind == null ? "run" : kind;
        // Defaults to java so every configuration saved before types existed keeps working untouched.
        type = type == null || type.isBlank() ? "java" : type;
        target = target == null ? "" : target;
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

    /** Back-compat constructor for the pre-type shape: a Java main-class configuration. */
    public RunConfiguration(
            String name,
            String kind,
            String mainClass,
            String projectName,
            String args,
            String vmArgs,
            String workingDir,
            String env) {
        this(name, kind, "java", "", mainClass, projectName, args, vmArgs, workingDir, env);
    }

    /** The id of the synthetic command that launches this configuration ({@code run.config.<slug>}). */
    public static String commandIdFor(String name) {
        return COMMAND_PREFIX + slug(name);
    }

    /**
     * A command-id-safe slug of a configuration name.
     *
     * <p>Its own rather than shared with {@code MacroService.slug}, whose empty-name fallback is the literal
     * {@code "macro"} — reusing it would give a configuration named only of punctuation the id
     * {@code run.config.macro}. Same shape as {@code ExternalTool.commandIdFor}, which carries its own for the
     * same reason.
     */
    public static String slug(String name) {
        String s = (name == null ? "" : name)
                .trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "unnamed" : s;
    }

    /** Whether this launches a Java main class (the jdtls path) rather than a script or make target. */
    @JsonIgnore
    public boolean isJava() {
        return "java".equals(type);
    }

    @JsonIgnore
    public boolean isDebug() {
        return "debug".equalsIgnoreCase(kind);
    }
}
