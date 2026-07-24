package com.editora.build;

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

    /** The JDWP agent options for a suspended debuggee on port 5005 (matches Gradle {@code --debug-jvm}). */
    public static final String JDWP_AGENT = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005";

    /** Gradle Run under debug: {@code <runTask> --debug-jvm} (suspends on 5005, prints the JDWP banner). */
    public static List<String> gradleDebugArgs(String runTask) {
        return List.of(runTask, "--debug-jvm");
    }

    /** Maven Spring Boot under debug: {@code spring-boot:run} with a suspended JDWP {@code jvmArguments}. */
    public static List<String> mavenSpringBootDebugArgs() {
        return List.of("spring-boot:run", "-Dspring-boot.run.jvmArguments=" + JDWP_AGENT);
    }

    /** Whether {@code pomText} declares the Spring Boot Maven plugin (a lightweight sniff, not a real parse). */
    public static boolean isSpringBootMavenPom(String pomText) {
        return pomText != null && pomText.contains("spring-boot-maven-plugin");
    }
}
