package com.editora.build;

/**
 * Detects a Spring Boot Gradle project from its build script, so the no-jdtls Gradle Run fallback can run
 * {@code bootRun} (the Spring Boot plugin's task) instead of {@code run} (the {@code application} plugin's,
 * which a Spring Boot project often doesn't apply). Pure and unit-tested — a lightweight text sniff of the
 * Spring Boot plugin id, not a real Gradle parse.
 *
 * <p>Maven needs no equivalent: the Maven fallback launches {@code java -cp <full classpath> <mainClass>},
 * and a Spring Boot {@code @SpringBootApplication} {@code main} runs fine that way.
 */
public final class SpringBoot {

    private SpringBoot() {}

    /** The Gradle task to run for a project whose build script is {@code buildFileText}: {@code bootRun}
     *  when the Spring Boot plugin is applied, else {@code run}. */
    public static String gradleRunTask(String buildFileText) {
        return isSpringBootGradle(buildFileText) ? "bootRun" : "run";
    }

    /** Whether {@code buildFileText} applies the Spring Boot Gradle plugin. */
    public static boolean isSpringBootGradle(String buildFileText) {
        if (buildFileText == null) {
            return false;
        }
        return buildFileText.contains("org.springframework.boot")
                || buildFileText.contains("spring-boot-gradle-plugin");
    }
}
