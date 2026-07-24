package com.editora.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Locates the Maven <b>reactor root</b> for a module so the Run classpath fallback resolves sibling-module
 * dependencies in a multi-module build. {@code mvn dependency:build-classpath} run in a submodule misses
 * sibling modules that aren't installed in the local repo; running it from the reactor root with
 * {@code -pl <module> -am} ("also make") builds the upstream modules and includes their output.
 *
 * <p>Heuristic (no pom parse): the reactor root is the topmost ancestor in a contiguous chain of directories
 * that each hold a {@code pom.xml} — the standard multi-module layout. Pure: the {@code pom.xml} presence
 * check is injected, so it's unit-tested without a filesystem.
 */
public final class MavenReactor {

    private MavenReactor() {}

    /** The topmost ancestor of {@code moduleDir} in a contiguous chain of pom-bearing dirs, or {@code moduleDir}
     *  itself when its parent has no {@code pom.xml}. {@code hasPom} tests whether a directory holds a pom. */
    public static Path reactorRoot(Path moduleDir, Predicate<Path> hasPom) {
        if (moduleDir == null) {
            return null;
        }
        Path root = moduleDir;
        Path parent = moduleDir.getParent();
        while (parent != null && hasPom.test(parent)) {
            root = parent;
            parent = parent.getParent();
        }
        return root;
    }

    /** The {@code -pl} project selector: {@code moduleDir} relative to {@code reactorRoot}, forward-slashed. */
    public static String moduleSelector(Path reactorRoot, Path moduleDir) {
        String rel = reactorRoot.relativize(moduleDir).toString();
        return rel.replace(File.separatorChar, '/');
    }
}
