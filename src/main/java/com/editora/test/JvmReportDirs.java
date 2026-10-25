package com.editora.test;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;

/**
 * The JUnit-XML report directories the coordinator polls for a JVM test run: Maven's
 * {@code target/surefire-reports} (+ Failsafe's {@code target/failsafe-reports}) and Gradle's
 * {@code build/test-results/test}, plus the folders a pom configures instead. Pure — no filesystem I/O (the
 * coordinator additionally does a bounded walk for reactor-module dirs). {@code []} for stream-based tools.
 */
public final class JvmReportDirs {

    private JvmReportDirs() {}

    public static List<Path> reportDirs(BuildTool tool, Path root) {
        return reportDirs(tool, root, com.editora.maven.PomBuildConfig.DEFAULTS);
    }

    /**
     * As {@link #reportDirs(BuildTool, Path)}, with a Maven module's own {@code <build><directory>} and the
     * Surefire/Failsafe {@code reportsDirectory} from {@code pom} (review D17): a pom that writes its reports
     * elsewhere still fills the tree. The standard folders stay in the list, so a module of the reactor that
     * keeps the defaults is found too.
     */
    public static List<Path> reportDirs(BuildTool tool, Path root, com.editora.maven.PomBuildConfig pom) {
        return switch (tool) {
            case MAVEN -> {
                java.util.LinkedHashSet<Path> dirs = new java.util.LinkedHashSet<>();
                com.editora.maven.PomBuildConfig cfg = pom == null ? com.editora.maven.PomBuildConfig.DEFAULTS : pom;
                dirs.add(cfg.surefireReports(root));
                dirs.add(cfg.failsafeReports(root));
                dirs.add(root.resolve("target").resolve("surefire-reports"));
                dirs.add(root.resolve("target").resolve("failsafe-reports"));
                yield List.copyOf(dirs);
            }
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
