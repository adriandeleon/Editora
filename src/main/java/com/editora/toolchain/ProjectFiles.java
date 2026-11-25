package com.editora.toolchain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The files a project names its JDK, Maven, Gradle, sbt and GraalVM in, as jkit reads them: {@code jkit.toml}
 * (or {@code .jkit.toml}), else {@code .sdkmanrc}, {@code .tool-versions} (asdf, mise) and {@code .java-version}
 * together. With jkit installed, jkit itself reads them ({@code jkit status --json}), so Editora agrees with
 * every shell and CI job that uses jkit; without it, Editora reads the {@code .sdkmanrc}. Runs a process and reads
 * the disk: off the FX thread.
 */
public final class ProjectFiles {

    /** jkit's own project file. */
    public static final String JKIT_TOML = "jkit.toml";

    /** Every file jkit reads a project's versions from, in the order it prefers them. */
    public static final List<String> NAMES =
            List.of(JKIT_TOML, ".jkit.toml", SdkmanRc.FILE, ".tool-versions", ".java-version");

    private ProjectFiles() {}

    /** Whether {@code name} is one of the files a project names its tools in ({@link #NAMES}). */
    public static boolean isProjectFile(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        return name != null && NAMES.contains(name.toString());
    }

    /** Whether {@code root} holds any of {@link #NAMES}: a project without one asks jkit nothing. */
    public static boolean any(Path root) {
        for (String name : NAMES) {
            if (Files.isRegularFile(root.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The requests {@code root}'s project files make: jkit's answer when jkit is installed and can say
     * ({@code jkit status --json} in {@code root}), else the {@code .sdkmanrc}'s. Empty when the folder has none.
     */
    public static List<ProjectToolchain.FilePin> pins(Path root) {
        if (!any(root)) {
            return List.of();
        }
        String jkit = JkitCli.locate();
        if (jkit != null) {
            JkitCli.Outcome status = JkitCli.status(jkit, root);
            if (status.ok()) {
                return ProjectToolchain.jkitPins(JkitCli.parseStatus(status.doc()));
            }
        }
        return ProjectToolchain.sdkmanrcPins(SdkmanRc.read(root));
    }
}
