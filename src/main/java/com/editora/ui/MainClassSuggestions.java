package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * The main class a new run configuration starts from (Run Configurations → Add), worked out off the FX thread (PERF-01,
 * Pauta 6.2.0): the project's Gradle {@code application} block is read and the file scanned for a {@code main} on a
 * virtual thread. The FX thread answers the last answer for the active file as it was (none while it is not known: the
 * field starts blank, as when nothing is found) and asks again behind it. Opening the Run Configurations window asks
 * first, so an Add finds it known.
 */
final class MainClassSuggestions {

    /** What was worked out for a file. */
    private record Known(Path file, @Nullable String fqn) {}

    private @Nullable Known known;
    private long asked;

    /** The suggestion for {@code b} as last worked out (null while unknown), and a fresh look behind it. */
    @Nullable
    String suggest(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null || !"java".equals(b.getLanguage())) {
            return null;
        }
        String text = b.getContent();
        long mine = ++asked;
        com.editora.perf.OffFx.then("MainClassSuggestions.suggest", () -> new Known(file, of(file, text)), a -> {
            Known landed = a.value();
            if (landed != null && mine == asked) {
                known = landed;
            }
        });
        Known now = known; // the last answer; an in-place runner (a test) has this one's already
        return now != null && now.file().equals(file) ? now.fqn() : null;
    }

    /** Whether the last answer kept is {@code file}'s. */
    boolean knows(Path file) {
        Known now = known;
        return now != null && now.file().equals(file);
    }

    /** Off the FX thread: the Gradle application's main class, else the first {@code main} of {@code text}. */
    static @Nullable String of(Path file, String text) {
        String declared = com.editora.build.GradleApplication.mainClass(
                com.editora.build.SpringBoot.readGradleBuildFile(JavaProjectRoot.find(file)));
        if (declared != null) {
            return declared;
        }
        List<com.editora.run.MainMethodScanner.MainMethod> mains = com.editora.run.MainMethodScanner.scan(text);
        return mains.isEmpty() ? null : mains.get(0).fqn();
    }
}
