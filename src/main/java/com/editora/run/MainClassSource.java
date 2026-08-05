package com.editora.run;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a run configuration's main class to the source file that declares it.
 *
 * <p>Needed because a Java launch resolves its classpath through jdtls <b>routed via an open Java file</b>
 * ({@link RunConfigRouting}). A project whose session holds only {@code pom.xml} therefore cannot run its own
 * saved configuration — the window opens a Java file so the launch has something to route through.
 *
 * <p>Pure: returns the paths to <em>try</em>, relative to the project root, in preference order. The caller
 * checks which exists. Standard Maven/Gradle layouts first, then a flat {@code <pkg>/<Class>.java} for a
 * project that keeps sources at the root.
 */
public final class MainClassSource {

    /** Source roots worth trying, in order. Main before test: Run means the application, not its tests. */
    private static final List<String> SOURCE_ROOTS =
            List.of("src/main/java", "src/main/kotlin", "src", "src/test/java", "");

    private MainClassSource() {}

    /**
     * Relative candidate paths for {@code fqn}, most likely first; empty when it isn't a usable class name.
     *
     * <p>A nested class ({@code com.example.App$Inner}) maps to its top-level file, and a file name that
     * slipped into the field ({@code App.java}) yields nothing rather than {@code App/java.java} — that case
     * is caught earlier with a precise message, and guessing here would only re-hide it.
     */
    public static List<String> candidates(String fqn) {
        if (fqn == null || fqn.isBlank() || fqn.endsWith(".java") || fqn.endsWith(".class")) {
            return List.of();
        }
        String name = fqn.strip();
        int dollar = name.indexOf('$');
        if (dollar >= 0) {
            name = name.substring(0, dollar); // a nested class lives in its outer class's file
        }
        if (name.isBlank() || name.endsWith(".")) {
            return List.of();
        }
        String relative = name.replace('.', '/') + ".java";
        List<String> out = new ArrayList<>(SOURCE_ROOTS.size());
        for (String root : SOURCE_ROOTS) {
            out.add(root.isEmpty() ? relative : root + "/" + relative);
        }
        return List.copyOf(out);
    }
}
