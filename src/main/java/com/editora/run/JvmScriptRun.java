package com.editora.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How Run and Debug start one file of a JVM language other than Java: which tool runs it, the command line,
 * and where the debugger's JDWP agent goes. Groovy ({@code groovy Script.groovy args…}) takes JVM options in an
 * environment variable; Scala CLI ({@code scala-cli run --java-opt … Hello.scala -- args…}) on its command line.
 * Kotlin scripts and the Clojure CLI fit the same shape.
 *
 * <p>The program runs on the JDK chosen in Settings (the one Maven, Gradle and Java's Run use): its
 * {@code JAVA_HOME} goes into the environment, which the tools' launcher scripts honour.
 *
 * <p>Pure.
 */
public final class JvmScriptRun {

    private JvmScriptRun() {}

    /** The languages this runs, each with the tool that runs its files. */
    private static final Map<String, String> TOOLS = Map.of("groovy", "groovy", "scala", "scala-cli");

    /**
     * The argument a debug command line carries where the JDWP agent option goes, for a tool that takes JVM
     * options on its command line ({@code --java-opt <agent>}); the debug adapter puts the agent there.
     */
    public static final String AGENT_ARGUMENT = "${editora.jdwpAgent}";

    /** A command line and the environment it runs with. */
    public record Launch(List<String> argv, Map<String, String> env) {}

    /**
     * How {@code language}'s {@code tool} runs {@code target} with {@code args}, on a JVM given
     * {@code jvmOptions}, in {@code env} ({@code inherited} is the user's own environment, whose
     * {@code JAVA_OPTS} a Groovy run keeps). Groovy: {@code groovy <target> args…} with the options in
     * {@code JAVA_OPTS}. Scala CLI: {@code scala-cli run --java-opt <o>… <target> -- args…}.
     */
    public static Launch launch(
            String language,
            String tool,
            String target,
            List<String> jvmOptions,
            List<String> args,
            Map<String, String> env,
            Map<String, String> inherited) {
        List<String> options = jvmOptions == null ? List.of() : jvmOptions;
        if (takesJvmOptionsInArgv(language)) {
            return new Launch(
                    scalaCli(tool, List.of(target), options, args), new LinkedHashMap<>(env == null ? Map.of() : env));
        }
        return new Launch(
                argv(tool, target, args),
                withJvmOption(env, inherited, jvmOptionsVariable(language), String.join(" ", options)));
    }

    /**
     * The command line a debug session starts {@code target} with: as {@link #launch}, with
     * {@link #AGENT_ARGUMENT} where the agent goes for a tool that takes JVM options on its command line (the
     * adapter puts it in {@link #jvmOptionsVariable} for the others).
     */
    public static List<String> debugArgv(
            String language, String tool, String target, List<String> jvmOptions, List<String> args) {
        if (!takesJvmOptionsInArgv(language)) {
            return argv(tool, target, args);
        }
        return scalaCliDebug(tool, List.of(target), jvmOptions, args);
    }

    /** As {@link #scalaCli}, with {@link #AGENT_ARGUMENT} as the first JVM option: a debug session's command. */
    public static List<String> scalaCliDebug(
            String tool, List<String> inputs, List<String> jvmOptions, List<String> args) {
        List<String> options = new ArrayList<>();
        options.add(AGENT_ARGUMENT);
        if (jvmOptions != null) {
            options.addAll(jvmOptions);
        }
        return scalaCli(tool, inputs, options, args);
    }

    /** Whether {@code language}'s tool is handed JVM options as arguments rather than an environment variable. */
    public static boolean takesJvmOptionsInArgv(String language) {
        return "scala".equals(language);
    }

    /**
     * {@code scala-cli run --java-opt <o>… <inputs…> -- args…}: {@code inputs} is a file, or a project folder
     * with its {@code --main-class} ({@link ScalaRun#cliInputs}). {@code run} is explicit: a bare
     * {@code scala-cli <file>} also runs it, but {@code scala}, the Scala 3.5+ launcher, documents
     * {@code scala run}. {@code --} keeps a program argument that looks like an option ({@code -v}) the
     * program's.
     */
    public static List<String> scalaCli(String tool, List<String> inputs, List<String> jvmOptions, List<String> args) {
        List<String> argv = new ArrayList<>();
        argv.add(tool);
        argv.add("run");
        for (String option : jvmOptions == null ? List.<String>of() : jvmOptions) {
            if (option != null && !option.isBlank()) {
                argv.add("--java-opt");
                argv.add(option);
            }
        }
        argv.addAll(inputs);
        if (args != null && !args.isEmpty()) {
            argv.add("--");
            argv.addAll(args);
        }
        return List.copyOf(argv);
    }

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
     * the one JVM it starts. ({@code JAVA_TOOL_OPTIONS} would reach every JVM the command forks as well.) None
     * for Scala CLI, which takes {@code --java-opt} arguments ({@link #takesJvmOptionsInArgv}): its JVM is one
     * of several the command starts (the Bloop compile server is another).
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
     * shell's "groovy: command not found". {@code status.run.toolMissing.groovy}, {@code ….scala-cli}.
     */
    public static String missingToolKey(String language) {
        return "status.run.toolMissing." + tool(language);
    }
}
