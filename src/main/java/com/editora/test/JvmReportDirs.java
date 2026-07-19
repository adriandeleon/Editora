package com.editora.test;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;

/**
 * The JUnit-XML report directories the coordinator polls for a JVM test run: Maven's
 * {@code target/surefire-reports} (+ Failsafe's {@code target/failsafe-reports}) and Gradle's
 * {@code build/test-results/test}. Pure — the standard single-module locations, no filesystem I/O (the
 * coordinator additionally does a bounded walk for reactor-module dirs). {@code []} for stream-based tools.
 */
public final class JvmReportDirs {

    private JvmReportDirs() {}

    public static List<Path> reportDirs(BuildTool tool, Path root) {
        return switch (tool) {
            case MAVEN ->
                List.of(
                        root.resolve("target").resolve("surefire-reports"),
                        root.resolve("target").resolve("failsafe-reports"));
            case GRADLE -> List.of(root.resolve("build").resolve("test-results").resolve("test"));
            default -> List.of();
        };
    }

    /** The directory-name markers that hold JUnit {@code TEST-*.xml} files, for the coordinator's module walk. */
    public static boolean isReportDirName(BuildTool tool, String dirName) {
        return switch (tool) {
            case MAVEN -> dirName.equals("surefire-reports") || dirName.equals("failsafe-reports");
            case GRADLE -> dirName.equals("test-results");
            default -> false;
        };
    }
}
