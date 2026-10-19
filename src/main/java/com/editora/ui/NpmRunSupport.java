package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.build.BuildTool;
import com.editora.build.NpmProject;
import com.editora.run.MissingProgram;

import static com.editora.i18n.Messages.tr;

/** What an npm-script run adds around the launch: its environment, a missing-program message, an install hint. */
final class NpmRunSupport {

    private NpmRunSupport() {}

    /** {@code FORCE_COLOR=1} — the consoles draw colours — unless {@code NO_COLOR} is set. */
    static Map<String, String> environment(String noColor) {
        return noColor != null && !noColor.isEmpty() ? Map.of() : Map.of("FORCE_COLOR", "1");
    }

    /**
     * What to say when {@code executable} could not start because it is not installed — with why a package
     * manager was chosen ({@code reason}, e.g. {@code pnpm-lock.yaml}) — or null for any other failure.
     */
    static String missingMessage(String executable, String error, String reason) {
        if (!MissingProgram.notFound(error)) {
            return null;
        }
        MissingProgram.Message m = MissingProgram.forProgram(executable, reason);
        return m == null ? null : tr(m.key(), m.args());
    }

    /**
     * Calls {@code onMissing} (on the FX thread) with the package manager's name when the package in {@code root}
     * declares dependencies but no {@code node_modules} exists in it or above it up to {@code projectRoot}.
     * Local folders only; the checks run off the FX thread.
     */
    static void whenDependenciesMissing(Path root, Path projectRoot, Consumer<String> onMissing) {
        if (root == null || root.getFileSystem() != java.nio.file.FileSystems.getDefault()) {
            return;
        }
        Thread.ofVirtual().name("npm-install-hint").start(() -> {
            if (dependenciesMissing(root, projectRoot)) {
                String manager = BuildTool.npmManager(root, projectRoot).manager();
                Platform.runLater(() -> onMissing.accept(manager));
            }
        });
    }

    /** Whether {@code root}'s package declares dependencies none of which can be installed (no node_modules). */
    static boolean dependenciesMissing(Path root, Path projectRoot) {
        try {
            NpmProject p = NpmProject.parse(Files.readString(root.resolve("package.json")));
            if (!p.hasDependencies()) {
                return false;
            }
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
        Path top = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        Path dir = root.toAbsolutePath().normalize();
        for (int i = 0; dir != null && i < 8; i++) {
            if (Files.isDirectory(dir.resolve("node_modules"))) {
                return false;
            }
            if (top == null || dir.equals(top) || !dir.startsWith(top)) {
                break;
            }
            dir = dir.getParent();
        }
        return true;
    }
}
