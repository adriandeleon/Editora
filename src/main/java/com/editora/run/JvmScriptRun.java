package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * How Run and Debug start one file of a JVM language other than Java: which tool runs it, the command line,
 * and where the debugger's JDWP agent goes. Groovy ({@code groovy Script.groovy args…}) and Kotlin scripts
 * ({@code kotlinc -script tool.main.kts args…}) take JVM options in an environment variable (a Kotlin program,
 * {@code .kt}, is compiled first, which {@link KotlinRun} plans); Scala CLI
 * ({@code scala-cli run --java-opt … Hello.scala -- args…}) on its command line; Clojure's file runs under the
 * Clojure CLI, Leiningen or Babashka as {@link ClojureRun} decides.
 *
 * <p>The program runs on the JDK chosen in Settings (the one Maven, Gradle and Java's Run use): its
 * {@code JAVA_HOME} goes into the environment, which the tools' launcher scripts honour.
 *
 * <p>Pure.
 */
public final class JvmScriptRun {

    private JvmScriptRun() {}

    /** The languages this runs, each with the tool that runs its files. */
    private static final Map<String, String> TOOLS =
            Map.of("groovy", "groovy", "scala", "scala-cli", "kotlin", KotlinRun.TOOL, "clojure", "clojure");

    /**
     * The argument a debug command line carries where the JDWP agent option goes, for a tool that takes JVM
     * options on its command line ({@code --java-opt <agent>}); the debug adapter puts the agent there.
     */
    public static final String AGENT_ARGUMENT = "${editora.jdwpAgent}";

    /** Languages whose files this runs. */
    public static Set<String> languages() {
        return TOOLS.keySet();
    }

    /** Whether a file in {@code language} is run (and debugged) through this. */
    public static boolean supports(String language) {
        return language != null && TOOLS.containsKey(language);
    }

    /**
     * The tool that runs {@code language}'s files ({@code groovy}, {@code scala-cli}, {@code kotlinc},
     * {@code clojure}), or null.
     */
    public static @Nullable String tool(String language) {
        return language == null ? null : TOOLS.get(language);
    }

    /**
     * The tool that runs {@code file}: {@link #tool(String)}, except that a Clojure file in a Leiningen project
     * runs under {@code lein} and a Babashka script under {@code bb}. Reads the file's head and looks for its
     * project's files.
     */
    public static @Nullable String tool(String language, Path file) {
        if (!"clojure".equals(language) || file == null) {
            return tool(language);
        }
        return ClojureRun.plan(file, head(file), Files::isRegularFile).tool().command();
    }

    /**
     * How a JVM-language file is started.
     *
     * @param argv the command line
     * @param directory the folder it runs in, or null for the caller's own choice (the file's folder, a run
     *     configuration's)
     * @param env the environment additions, the JVM options included where the tool reads them
     */
    public record Launch(List<String> argv, @Nullable Path directory, Map<String, String> env) {

        /** A launch in the caller's own folder. */
        public Launch(List<String> argv, Map<String, String> env) {
            this(argv, null, env);
        }
    }

    /**
     * How {@code file} (named {@code fileArg} where it runs) is started by {@code tool} (its executable is
     * {@code executable}) with {@code args}, the configuration's {@code vmArgs} and, for a debug session, the
     * options that keep a program inspectable. {@code env} is the launch's environment so far, and
     * {@code inherited} the user's (whose own {@code JAVA_OPTS} still apply). Reads the file's head.
     */
    public static Launch launch(
            String language,
            @Nullable String tool,
            String executable,
            @Nullable Path file,
            String fileArg,
            List<String> args,
            String vmArgs,
            boolean debug,
            Map<String, String> env,
            Map<String, String> inherited) {
        String vm = vmArgs == null ? "" : vmArgs.strip();
        if (takesJvmOptionsInArgv(language)) {
            List<String> options = ProgramArgs.tokenize(vm);
            List<String> inputs = List.of(fileArg);
            return new Launch(
                    debug
                            ? scalaCliDebug(executable, inputs, options, args)
                            : scalaCli(executable, inputs, options, args),
                    new LinkedHashMap<>(env == null ? Map.of() : env));
        }
        ClojureRun.Tool clojure = "clojure".equals(language) ? ClojureRun.Tool.of(tool) : null;
        if (clojure == null) {
            return new Launch(
                    argv(language, executable, fileArg, args),
                    null,
                    withJvmOption(env, inherited, jvmOptionsVariable(language), vm));
        }
        ClojureRun.Plan plan = ClojureRun.plan(file, head(file), Files::isRegularFile);
        plan = new ClojureRun.Plan(clojure, plan.directory(), plan.mainNamespace(), plan.inProject(), plan.extraPath());
        List<String> options = new ArrayList<>(List.of(ClojureRun.REPORT_TO_STDERR));
        if (debug) {
            options.add(ClojureRun.KEEP_LOCALS);
        }
        Map<String, String> launchEnv = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
        String variable = ClojureRun.jvmOptionsVariable(clojure);
        if (variable != null) {
            if (!vm.isEmpty()) {
                options.add(vm);
            }
            launchEnv = withJvmOption(launchEnv, inherited, variable, String.join(" ", options));
            options.clear();
        } else if (ClojureRun.jvmOptionPrefix(clojure) != null && !vm.isEmpty()) {
            options.addAll(ProgramArgs.tokenize(vm));
        }
        return new Launch(ClojureRun.argv(plan, executable, fileArg, options, args), plan.directory(), launchEnv);
    }

    /**
     * The argument prefix through which {@code tool} passes an option to its JVM — where a debug session puts
     * the JDWP agent when the tool reads no variable: {@code -J} for the Clojure CLI, else null.
     */
    public static @Nullable String jvmOptionPrefix(@Nullable String tool) {
        ClojureRun.Tool t = ClojureRun.Tool.of(tool);
        return t == null ? null : ClojureRun.jvmOptionPrefix(t);
    }

    /**
     * The variable through which the program {@code tool} starts for {@code language} reads JVM options
     * ({@link #jvmOptionsVariable(String)}; Leiningen's {@code JVM_OPTS}), or null when it reads none.
     */
    public static @Nullable String jvmOptionsVariable(String language, @Nullable String tool) {
        ClojureRun.Tool t = "clojure".equals(language) ? ClojureRun.Tool.of(tool) : null;
        return t == null ? jvmOptionsVariable(language) : ClojureRun.jvmOptionsVariable(t);
    }

    /** Whether the program {@code tool} starts runs on a JVM the debugger can attach to (not Babashka's). */
    public static boolean debuggable(@Nullable String tool) {
        ClojureRun.Tool t = ClojureRun.Tool.of(tool);
        return t == null || ClojureRun.debuggable(t);
    }

    /** The first 64 KB of {@code file}, where its shebang, namespace and {@code -main} are; "" when unreadable. */
    private static String head(@Nullable Path file) {
        try (var in = Files.newInputStream(file)) {
            return new String(in.readNBytes(64 * 1024), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * How {@code language}'s {@code tool} runs {@code target} with {@code args}, on a JVM given
     * {@code jvmOptions}, in {@code env} ({@code inherited} is the user's own environment, whose
     * {@code JAVA_OPTS} a Groovy run keeps). Groovy: {@code groovy <target> args…} with the options in
     * {@code JAVA_OPTS}. Scala CLI: {@code scala-cli run --java-opt <o>… <target> -- args…}.
     */
    public static Launch launch(
            String language,
            @Nullable String tool,
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
    public static List<String> scalaCli(
            @Nullable String tool, List<String> inputs, List<String> jvmOptions, List<String> args) {
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

    /** The command that runs {@code file} (as its host names it) with {@code args}: {@code <tool> <file> args…}. */
    public static List<String> argv(@Nullable String toolExecutable, String file, List<String> args) {
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
     * Kotlin program is started by {@code java} itself, so its options go on the command line instead. None
     * for Scala CLI, which takes {@code --java-opt} arguments ({@link #takesJvmOptionsInArgv}): its JVM is one
     * of several the command starts (the Bloop compile server is another).
     */
    public static @Nullable String jvmOptionsVariable(String language) {
        return "groovy".equals(language) || "kotlin".equals(language) ? "JAVA_OPTS" : null;
    }

    /**
     * {@code base} with {@code option} appended to {@code variable} — after what the user's own environment
     * ({@code inherited}) already put there, so their {@code JAVA_OPTS} still apply.
     */
    public static Map<String, String> withJvmOption(
            Map<String, String> base, Map<String, String> inherited, @Nullable String variable, String option) {
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
        return missingToolKeyFor(tool(language));
    }

    /** {@link #missingToolKey} for the tool itself ({@code lein}, {@code bb}). */
    public static String missingToolKeyFor(@Nullable String tool) {
        return "status.run.toolMissing." + tool;
    }
}
