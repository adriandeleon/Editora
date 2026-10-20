package com.editora.maven;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
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

    /**
     * A module's place in its reactor, for the tasks tree's "build from the reactor root" toggle.
     *
     * @param root the reactor root directory
     * @param rootPom the root {@code pom.xml}
     * @param moduleSelector the {@code -pl} selector of the module, relative to {@code root}
     */
    public record Reactor(Path root, Path rootPom, String moduleSelector) {
        /** The root folder's name, for a label. */
        public String rootName() {
            Path name = root.getFileName();
            return name == null ? root.toString() : name.toString();
        }
    }

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

    /**
     * The reactor {@code moduleDir} belongs to, or {@code null} when it is a top-level project.
     *
     * <p>Two checks keep this honest, because the tasks tree offers to <em>build</em> from the answer. The chain
     * walk stops at {@code boundary} (the open project's root; {@code null} for no bound), so a folder the user
     * never opened is not built from. And the root found must actually <b>list</b> the module — its
     * {@code <modules>} (read through {@code pomText}, which returns a pom's text or {@code null}) must name
     * the selector's first segment — because the contiguous-pom heuristic also matches a project that merely
     * sits under an unrelated pom (a {@code samples/} folder of another repository): Maven would then answer
     * "Could not find the selected project in the reactor".
     */
    public static Reactor describe(
            Path moduleDir, Path boundary, Predicate<Path> hasPom, Function<Path, String> pomText) {
        if (moduleDir == null) {
            return null;
        }
        Path module = moduleDir.toAbsolutePath().normalize();
        Path top = boundary == null ? null : boundary.toAbsolutePath().normalize();
        Predicate<Path> inBounds = dir -> (top == null || dir.startsWith(top)) && hasPom.test(dir);
        Path root = reactorRoot(module, inBounds);
        if (root == null || root.equals(module)) {
            return null;
        }
        String selector = moduleSelector(root, module);
        Path rootPom = root.resolve("pom.xml");
        String text = pomText.apply(rootPom);
        if (text == null || !listsModule(PomSummary.parse(text).modules(), selector)) {
            return null;
        }
        return new Reactor(root, rootPom, selector);
    }

    /** Whether a root pom's {@code <modules>} covers {@code selector}: the selector itself, or its first
     *  segment (an aggregator one level down lists the rest). */
    static boolean listsModule(List<String> modules, String selector) {
        String first = selector.contains("/") ? selector.substring(0, selector.indexOf('/')) : selector;
        for (String module : modules) {
            String m = module.replace('\\', '/');
            while (m.endsWith("/")) {
                m = m.substring(0, m.length() - 1);
            }
            if (m.startsWith("./")) {
                m = m.substring(2);
            }
            if (m.equals(selector) || m.equals(first) || selector.startsWith(m + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The argv that runs a module's phase from its reactor root with its upstream modules: {@code -f
     * <rootPom> -pl <module> -am}. {@code rootPom} is named as the machine Maven runs on names it.
     */
    public static List<String> reactorArgs(String rootPom, String moduleSelector) {
        return List.of("-f", rootPom, "-pl", moduleSelector, "-am");
    }
}
