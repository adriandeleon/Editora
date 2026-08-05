package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A saved launch configuration: a {@code name}, the {@code mainClass} (fully-qualified) + its {@code
 * projectName} (multi-module), program {@code args}, JVM {@code vmArgs}, an optional {@code workingDir}
 * (blank ⇒ the project root), and {@code env} — environment variables as quote-aware {@code KEY=VALUE} pairs
 * (see {@code run/EnvVars}).
 *
 * <p>{@code type} says <em>what</em> is launched — {@code java} (the {@code mainClass}, resolved through
 * jdtls) or a script type handled by {@code run/ScriptRunCommand}, whose {@code target} is the script path or
 * make target. Absent from an older entry it defaults to {@code java}, so every configuration saved before
 * types existed keeps working.
 *
 * <p>A configuration does <b>not</b> say whether to run or debug — that is the caller's choice, as in
 * IntelliJ and VS Code. One entry backs the toolbar's Run and Debug buttons and both synthetic commands
 * ({@link #commandIdFor} / {@link #debugCommandIdFor}). It used to carry a {@code kind} of
 * {@code "run"}/{@code "debug"} that only decided what the <em>Run</em> button did with it, which made the
 * Run button a duplicate of Debug and left no way to plain-run such an entry at all; a stored
 * {@code "kind"} is now ignored on load (see {@code JsonIgnoreProperties}) and dropped on the next write.
 *
 * <p>{@code beforeLaunch} is an optional command line run first — a build, a codegen step — in the same
 * working directory; a non-zero exit aborts the launch, so a stale binary is never run by accident. Both the
 * run and the debug path honour it.
 *
 * <p>Persisted per window in {@code WorkspaceState.runConfigurations}. Jackson
 * round-trips this record; the compact constructor defaults every field so an older/partial entry loads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunConfiguration(
        String name,
        String type,
        String target,
        String mainClass,
        String projectName,
        String args,
        String vmArgs,
        String workingDir,
        String env,
        String beforeLaunch) {

    /** Prefix of the synthetic per-configuration run commands, so stale ones can be found and dropped. */
    public static final String COMMAND_PREFIX = "run.config.";

    /**
     * Prefix of the synthetic per-configuration <em>debug</em> commands.
     *
     * <p>Under {@code debug.} on purpose: {@code Chrome}'s feature rules gate that whole prefix, so these
     * disappear from the palette when debugging is off or Simple UI mode is on, with no rule of their own.
     */
    public static final String DEBUG_COMMAND_PREFIX = "debug.config.";

    public RunConfiguration {
        name = name == null ? "" : name;
        // Defaults to java so every configuration saved before types existed keeps working untouched.
        type = type == null || type.isBlank() ? "java" : type;
        target = target == null ? "" : target;
        mainClass = mainClass == null ? "" : mainClass;
        projectName = projectName == null ? "" : projectName;
        args = args == null ? "" : args;
        vmArgs = vmArgs == null ? "" : vmArgs;
        workingDir = workingDir == null ? "" : workingDir;
        env = env == null ? "" : env;
        beforeLaunch = beforeLaunch == null ? "" : beforeLaunch;
    }

    /**
     * Convenience constructor for a plain Java main-class configuration with no environment variables and no
     * before-launch step.
     *
     * <p>Six arguments, deliberately: the pre-{@code kind} shapes took seven and eight, so an unconverted
     * call site fails to compile rather than quietly re-binding {@code "run"} to {@code mainClass} and
     * shifting every argument after it by one. Positional {@code String} constructors give no other warning.
     */
    public RunConfiguration(
            String name, String mainClass, String projectName, String args, String vmArgs, String workingDir) {
        this(name, "java", "", mainClass, projectName, args, vmArgs, workingDir, "", "");
    }

    /** The id of the synthetic command that runs this configuration ({@code run.config.<slug>}). */
    public static String commandIdFor(String name) {
        return COMMAND_PREFIX + slug(name);
    }

    /**
     * The id of the synthetic command that debugs this configuration ({@code debug.config.<slug>}).
     *
     * <p>Its own command rather than a flag on the run one, because a command id is what a keybinding binds
     * to: without it there is no way to bind a key — or find a palette entry — that debugs a <em>named</em>
     * configuration, which is the one thing the old {@code kind} field genuinely provided.
     */
    public static String debugCommandIdFor(String name) {
        return DEBUG_COMMAND_PREFIX + slug(name);
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

    /**
     * Whether this is a Java configuration with no main class filled in — not launchable, and above all not
     * a question worth asking jdtls.
     *
     * <p>jdtls turns the main class into an Eclipse {@code SearchPattern}, and {@code createPattern("")}
     * returns null, so an empty one comes back as an internal NPE from deep inside its search engine
     * ({@code Cannot invoke "SearchPattern.findIndexMatches(…)" because "pattern" is null}) rather than
     * anything a user could act on. Settings → Run Configurations → <b>Add</b> creates exactly this shape —
     * a Java configuration whose fields are all blank — so it is one click away, not a corner case.
     *
     * <p>The script types already refuse a missing target with a clear message ({@code
     * ScriptRunCommand.needsTarget}); this is the Java half of the same check.
     */
    @JsonIgnore
    public boolean missingMainClass() {
        return isJava() && mainClass.isBlank();
    }

    /**
     * Whether {@link #mainClass} is a <em>file name</em> rather than a fully-qualified class name — the
     * mistake the Edit Configurations form invites, since the file in front of you is called
     * {@code App.java} but jdtls wants {@code com.example.App}.
     *
     * <p>Worth its own check because the failure is otherwise deeply misleading: jdtls happily looks up a
     * type literally named {@code App.java}, finds nothing, and returns an <b>empty classpath with no
     * error</b> — which the launch then reports as "make sure the Java project has finished importing",
     * sending the user to debug their perfectly healthy language server.
     */
    @JsonIgnore
    public boolean mainClassLooksLikeAFile() {
        return isJava() && (mainClass.endsWith(".java") || mainClass.endsWith(".class"));
    }
}
