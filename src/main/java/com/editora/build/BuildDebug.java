package com.editora.build;

import java.nio.file.Path;
import java.util.List;

/**
 * Pure helpers for launching a Maven/Gradle app under the debugger via the build tool (the "Debug via build
 * tool" flow): the JVM is started with a suspended JDWP agent, prints the standard "Listening for transport…"
 * banner, and Editora attaches. This complements the jdtls launch — it's the path for Gradle (where jdtls's
 * launch resolution is weaker) and Spring Boot (where you want the full {@code bootRun}/{@code spring-boot:run}).
 *
 * <p>Only clean cases are supported: Gradle {@code run}/{@code bootRun} with {@code --debug-jvm}, and Maven
 * Spring Boot via {@code spring-boot:run} + a JDWP {@code jvmArguments}. A plain Maven {@code main} has no
 * uniform build-tool debug mechanism — use jdtls (Debug Main Class) there.
 */
public final class BuildDebug {

    private BuildDebug() {}

    /**
     * The JDWP agent options for a suspended debuggee listening on the loopback interface only (as Gradle's
     * {@code --debug-jvm} does), on a port the system picks: JDWP prints it in its banner, which is what the
     * attach reads. A fixed 5005 failed with "address in use" beside another debug run (review A15). JDWP is
     * unauthenticated: {@code address=*:5005} offered code execution as the user to every host that could
     * reach the machine, and Editora only ever attaches to {@code localhost}.
     */
    public static final String JDWP_AGENT = jdwpAgent(0);

    /**
     * The same suspended loopback agent on a port chosen beforehand ({@code 0} lets the system pick). A Maven
     * test debug chooses its own free port (review D1): Surefire 3 holds the fork's stdout — the banner with
     * the system-picked port included — until the suspended JVM has run, so the port must be known up front.
     */
    public static String jdwpAgent(int port) {
        return "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:" + port;
    }

    /** Gradle Run under debug: {@code <runTask> --debug-jvm} (suspends on 5005, prints the JDWP banner). */
    public static List<String> gradleDebugArgs(String runTask) {
        return List.of(runTask, "--debug-jvm");
    }

    /**
     * Gradle Run under debug on a port the system picks: {@code <runTask> --init-script <initScript>} (see
     * {@link GradleDebugInit}); {@code --debug-jvm}'s fixed 5005 when there is no script ({@code null}).
     */
    public static List<String> gradleDebugArgs(String runTask, Path initScript) {
        if (initScript == null) {
            return gradleDebugArgs(runTask);
        }
        List<String> args = new java.util.ArrayList<>(List.of(runTask));
        args.addAll(GradleDebugInit.args(initScript));
        return List.copyOf(args);
    }

    /** Maven Spring Boot under debug: {@code spring-boot:run} with a suspended JDWP {@code jvmArguments}. */
    public static List<String> mavenSpringBootDebugArgs() {
        return List.of("spring-boot:run", "-Dspring-boot.run.jvmArguments=" + JDWP_AGENT);
    }

    /**
     * Whether {@code pomText} declares the Spring Boot Maven plugin — in {@code <build><plugins>}, its
     * {@code <pluginManagement>} or a profile's (a real parse through {@code PomBuildConfig}, so a mention in
     * a comment no longer counts — review D11). The file alone: {@link #springBootConfig} also reads the
     * parents on disk.
     */
    public static boolean isSpringBootMavenPom(String pomText) {
        return com.editora.maven.PomBuildConfig.parse(pomText, "").springBoot();
    }

    /** The module at {@code root}'s build configuration, parents on disk included (review D11). */
    public static com.editora.maven.PomBuildConfig springBootConfig(Path root) {
        return com.editora.maven.PomBuildConfig.read(root);
    }

    /**
     * How long the "Debug via build tool" flow waits for the JDWP banner before saying so: a Boot pom whose
     * {@code <jvmArguments>} beat the agent, or a build that never started the app, otherwise looks like a
     * launch that is still "starting" while the app runs normally in Output (review D11).
     */
    public static final int BANNER_TIMEOUT_SECONDS = 45;
}
