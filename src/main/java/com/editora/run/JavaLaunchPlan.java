package com.editora.run;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.editora.config.RunConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * How a saved Java run configuration launches — one plan, read by Run and by Debug alike (review D9). Each
 * used to work these out on its own and they drifted apart: on a remote host Run used the host's configured
 * JDK and Debug whatever jdtls resolved there; Run split the VM arguments the way a shell does and Debug
 * handed the raw text to java-debug's own parser; the environment of a remote launch differed. Pure.
 *
 * @param cwd the working directory: the configuration's, else the project root
 * @param jdkHome the JDK the program runs on ({@code ""}: the one jdtls resolves, or the host's PATH)
 * @param javaExecutable that JDK's {@code java} as the program's machine names it ({@code ""}: none chosen)
 * @param jdkEnvironment the environment of the JDK alone — what a before-launch build runs with
 * @param environment the program's environment: the JDK's, then the configuration's variables
 * @param vmArgs the VM arguments, split as a shell splits them
 * @param args the program arguments, split as a shell splits them
 */
public record JavaLaunchPlan(
        Path cwd,
        String jdkHome,
        String javaExecutable,
        Map<String, String> jdkEnvironment,
        Map<String, String> environment,
        List<String> vmArgs,
        List<String> args) {

    /** Which field of the configuration cannot be read, and the quote it leaves open. */
    public record Problem(Field field, char quote) {}

    /** The configuration fields a plan reads that can be mistyped. */
    public enum Field {
        VM_ARGS,
        ARGS,
        ENV
    }

    /** A plan, or why there is none. */
    public record Result(
            @Nullable JavaLaunchPlan plan, @Nullable Problem problem) {
        public boolean ok() {
            return plan != null;
        }
    }

    /**
     * What a plan needs to know beyond the configuration.
     *
     * @param root the project root (the configuration's working directory is relative to it)
     * @param remote whether the program runs on another machine (a remote host, a container, WSL)
     * @param mavenProject whether {@code root} is a Maven project (the JDK setting applies to those)
     * @param mavenJdkSetting the JDK chosen in Settings for Maven projects ({@code ""}: none)
     * @param remoteJdkHome the JDK configured for the remote host ({@code ""}: its own java)
     * @param inherited what {@code $NAME} in the environment expands to
     * @param path this machine's PATH as a program gets it (the augmented one)
     */
    public record Inputs(
            Path root,
            boolean remote,
            boolean mavenProject,
            String mavenJdkSetting,
            String remoteJdkHome,
            Function<String, String> inherited,
            String path) {

        /**
         * The inputs for {@code root}'s project as this machine sees it: where it runs, whether it is a Maven
         * project, the host's configured JDK, this machine's environment for {@code $NAME}.
         */
        public static Inputs of(Path root, String mavenJdkSetting) {
            boolean maven;
            try {
                maven = root != null && java.nio.file.Files.isRegularFile(root.resolve("pom.xml"));
            } catch (RuntimeException e) {
                maven = false;
            }
            return of(root, mavenJdkSetting, maven);
        }

        /** As {@link #of(Path, String)}, told whether {@code root} is a Maven project. */
        public static Inputs of(Path root, String mavenJdkSetting, boolean maven) {
            boolean remote = root != null && !com.editora.process.ExecTargets.runsHere(root);
            String hostJdk = remote ? com.editora.process.ExecTargets.remoteTool(root, "jdk.home") : "";
            return new Inputs(
                    root,
                    remote,
                    maven,
                    mavenJdkSetting == null ? "" : mavenJdkSetting,
                    hostJdk == null ? "" : hostJdk,
                    JavaLaunchPlan.inherited(remote),
                    com.editora.process.ProcessRunner.augmentedPath());
        }
    }

    /**
     * What {@code $NAME} in a configuration's environment expands to: this machine's environment, with the
     * PATH a run actually gets (the augmented one). A remote host's environment is not known here, so there a
     * reference is left as written.
     */
    public static Function<String, String> inherited(boolean remote) {
        if (remote) {
            return name -> "$" + name;
        }
        return name ->
                "PATH".equalsIgnoreCase(name) ? com.editora.process.ProcessRunner.augmentedPath() : System.getenv(name);
    }

    /** The plan for {@code cfg} in {@code in}'s project. */
    public static Result forConfiguration(RunConfiguration cfg, Inputs in) {
        Path configured = RunConfigWorkingDirectory.resolve(cfg, in.root());
        Path cwd = configured != null ? configured : in.root();
        String jdkHome = jdkHome(cfg, in);
        String javaExec =
                in.remote() ? (jdkHome.isBlank() ? "" : hostJava(jdkHome)) : JdkToolchain.javaExecutable(jdkHome);
        Map<String, String> jdkEnv =
                in.remote() ? remoteJdkEnvironment(jdkHome) : JdkToolchain.environment(jdkHome, in.path());
        List<String> vm;
        List<String> args;
        Map<String, String> configuredEnv;
        try {
            vm = ProgramArgs.parse(cfg.vmArgs());
        } catch (ProgramArgs.UnclosedQuoteException e) {
            return new Result(null, new Problem(Field.VM_ARGS, e.quote()));
        }
        try {
            args = ProgramArgs.parse(cfg.args());
        } catch (ProgramArgs.UnclosedQuoteException e) {
            return new Result(null, new Problem(Field.ARGS, e.quote()));
        }
        try {
            configuredEnv = EnvVars.resolve(cfg.env(), in.inherited());
        } catch (ProgramArgs.UnclosedQuoteException e) {
            return new Result(null, new Problem(Field.ENV, e.quote()));
        }
        Map<String, String> env = new LinkedHashMap<>(jdkEnv);
        env.putAll(configuredEnv);
        return new Result(
                new JavaLaunchPlan(
                        cwd,
                        jdkHome,
                        javaExec,
                        Map.copyOf(jdkEnv),
                        java.util.Collections.unmodifiableMap(env),
                        vm,
                        args),
                null);
    }

    /**
     * The JDK of a configuration: on a remote host the host's configured one, never a folder of this
     * machine; here the configuration's own, else the Maven JDK setting — for a Maven project only (a Gradle
     * build chooses its JDK itself).
     */
    public static String jdkHome(RunConfiguration cfg, Inputs in) {
        if (in.remote()) {
            return in.remoteJdkHome() == null ? "" : in.remoteJdkHome().strip();
        }
        if (!in.mavenProject()) {
            return "";
        }
        return JdkToolchain.effectiveHome(cfg == null ? "" : cfg.jdkHome(), in.mavenJdkSetting());
    }

    /** {@code java} under a host's JDK, as the host names it. */
    public static String hostJava(String jdkHome) {
        return jdkHome.strip().replaceAll("/+$", "") + "/bin/java";
    }

    /** {@code JAVA_HOME} for a host's configured JDK; nothing at all for its own java. */
    public static Map<String, String> remoteJdkEnvironment(String jdkHome) {
        return jdkHome == null || jdkHome.isBlank() ? Map.of() : Map.of("JAVA_HOME", jdkHome);
    }

    /**
     * The saved configuration that launches {@code mainClass}, or null: what Debug ▶ and Debug on a project
     * file use, as Run ▶ does, so the configuration's before-launch build, arguments, environment and
     * working directory apply. Only a configuration naming that very class — never one for another class.
     */
    public static @Nullable RunConfiguration configurationFor(String mainClass, List<RunConfiguration> all) {
        if (mainClass == null || mainClass.isBlank() || all == null) {
            return null;
        }
        for (RunConfiguration c : all) {
            if (c.isJava() && mainClass.equals(c.mainClass())) {
                return c;
            }
        }
        return null;
    }
}
