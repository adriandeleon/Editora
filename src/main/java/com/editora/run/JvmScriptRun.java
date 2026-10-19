package com.editora.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How Run and Debug start one file of a JVM language other than Java: which tool runs it, the command line,
 * and where the debugger's JDWP agent goes. Groovy ({@code groovy Script.groovy args…}) and Kotlin scripts
 * ({@code kotlinc -script tool.main.kts args…}); a Kotlin program ({@code .kt}) is compiled first, which
 * {@link KotlinRun} plans. Scala CLI and the Clojure CLI fit the same shape.
 *
 * <p>The program runs on the JDK chosen in Settings (the one Maven, Gradle and Java's Run use): its
 * {@code JAVA_HOME} goes into the environment, which the tools' launcher scripts honour.
 *
 * <p>Pure.
 */
public final class JvmScriptRun {

    private JvmScriptRun() {}

    /** The languages this runs, each with the tool that runs its files. */
    private static final Map<String, String> TOOLS = Map.of("groovy", "groovy", "kotlin", KotlinRun.TOOL);

    /** Languages whose files this runs. */
    public static Set<String> languages() {
        return TOOLS.keySet();
    }

    /** Whether a file in {@code language} is run (and debugged) through this. */
    public static boolean supports(String language) {
        return language != null && TOOLS.containsKey(language);
    }

    /** The tool that runs {@code language}'s files ({@code groovy}, {@code kotlinc}), or null. */
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
     * The command that runs script {@code file} of {@code language}: {@code kotlinc -script <file> args…} for
     * Kotlin, else {@link #argv(String, String, List) <tool> <file> args…}.
     */
    public static List<String> argv(String language, String toolExecutable, String file, List<String> args) {
        return "kotlin".equals(language)
                ? KotlinRun.scriptArgv(toolExecutable, file, args)
                : argv(toolExecutable, file, args);
    }

    /**
     * The environment variable through which {@code language}'s launcher passes options to its JVM — where a
     * debug session puts the JDWP agent. {@code JAVA_OPTS} for Groovy's {@code startGroovy}, which reads it for
     * the one JVM it starts, and for {@code kotlinc}, whose launcher does the same (a script runs inside the
     * compiler's JVM). ({@code JAVA_TOOL_OPTIONS} would reach every JVM the command forks as well.) A compiled
     * Kotlin program is started by {@code java} itself, so its options go on the command line instead.
     */
    public static String jvmOptionsVariable(String language) {
        return "groovy".equals(language) || "kotlin".equals(language) ? "JAVA_OPTS" : null;
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
