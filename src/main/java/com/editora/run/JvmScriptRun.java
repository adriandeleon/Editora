package com.editora.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How Run and Debug start one file of a JVM language other than Java: which tool runs it, the command line,
 * and where the debugger's JDWP agent goes. Groovy today ({@code groovy Script.groovy args…}); Kotlin scripts,
 * Scala CLI and the Clojure CLI fit the same shape.
 *
 * <p>The program runs on the JDK chosen in Settings (the one Maven, Gradle and Java's Run use): its
 * {@code JAVA_HOME} goes into the environment, which the tools' launcher scripts honour.
 *
 * <p>Pure.
 */
public final class JvmScriptRun {

    private JvmScriptRun() {}

    /** The languages this runs, each with the tool that runs its files. */
    private static final Map<String, String> TOOLS = Map.of("groovy", "groovy");

    /** Languages whose files this runs. */
    public static Set<String> languages() {
        return TOOLS.keySet();
    }

    /** Whether a file in {@code language} is run (and debugged) through this. */
    public static boolean supports(String language) {
        return language != null && TOOLS.containsKey(language);
    }

    /** The tool that runs {@code language}'s files ({@code groovy}), or null. */
    public static String tool(String language) {
        return language == null ? null : TOOLS.get(language);
    }

    /** The command that runs {@code file} (as its host names it) with {@code args}: {@code <tool> <file> args…}. */
    public static List<String> argv(String toolExecutable, String file, List<String> args) {
        List<String> argv = new ArrayList<>();
        argv.add(toolExecutable);
        argv.add(file);
        if (args != null) {
            argv.addAll(args);
        }
        return List.copyOf(argv);
    }

    /**
     * The environment variable through which {@code language}'s launcher passes options to its JVM — where a
     * debug session puts the JDWP agent. {@code JAVA_OPTS} for Groovy's {@code startGroovy}, which reads it for
     * the one JVM it starts. ({@code JAVA_TOOL_OPTIONS} would reach every JVM the command forks as well.)
     */
    public static String jvmOptionsVariable(String language) {
        return "groovy".equals(language) ? "JAVA_OPTS" : null;
    }

    /**
     * {@code base} with {@code option} appended to {@code variable} — after what the user's own environment
     * ({@code inherited}) already put there, so their {@code JAVA_OPTS} still apply.
     */
    public static Map<String, String> withJvmOption(
            Map<String, String> base, Map<String, String> inherited, String variable, String option) {
        Map<String, String> env = new LinkedHashMap<>(base == null ? Map.of() : base);
        if (variable == null || option == null || option.isBlank()) {
            return env;
        }
        String existing =
                env.containsKey(variable) ? env.get(variable) : inherited == null ? null : inherited.get(variable);
        env.put(variable, existing == null || existing.isBlank() ? option : existing.strip() + " " + option);
        return env;
    }

    /**
     * The message key for a missing tool, said instead of running: the Run console would otherwise show the
     * shell's "groovy: command not found".
     */
    public static String missingToolKey(String language) {
        return "status.run.toolMissing." + tool(language);
    }
}
