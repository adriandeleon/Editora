package com.editora.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Locates the Maven <b>reactor root</b> for a module so the Run classpath fallback resolves sibling-module
 * dependencies in a multi-module build. {@code mvn dependency:build-classpath} run in a submodule misses
 * sibling modules that aren't installed in the local repo; running it from the reactor root with
 * {@code -pl <module> -am} ("also make") builds the upstream modules and includes their output.
 *
 * <p>Two walks. {@link #reactorRoot} is the cheap heuristic (no pom parse): the topmost ancestor in a
 * contiguous chain of directories that each hold a {@code pom.xml}. {@link #describe} is the honest one: it
 * reads the {@code <modules>} of the ancestors' poms and names the topmost ancestor whose module chain really
 * reaches the folder — which also covers a {@code modules/} layout whose intermediate folder holds no pom
 * (review D5). Pure: the {@code pom.xml} presence check and the pom text are injected, so both are
 * unit-tested without a filesystem.
 */
public final class MavenReactor {

    private MavenReactor() {}

    /** How many folders above a module {@link #describe} looks when no boundary bounds the walk. */
    static final int MAX_ANCESTORS = 8;

    /** How deep an aggregator chain is followed ({@code root → modules → a}), and how many poms it may read. */
    static final int MAX_MODULE_DEPTH = 6;

    static final int MAX_MODULES_READ = 200;

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
    public static @Nullable Path reactorRoot(Path moduleDir, Predicate<Path> hasPom) {
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
     * <p>Two checks keep this honest, because the tasks tree offers to <em>build</em> from the answer and the
     * Run fallback builds from it. The walk up stops at {@code boundary} (the open project's root; {@code null}
     * for no bound, then {@link #MAX_ANCESTORS} folders), so a folder the user never opened is not built from.
     * And the root found must actually <b>reach</b> the module through its {@code <modules>} (read through
     * {@code pomText}, which returns a pom's text or {@code null}): directly ({@code <module>modules/a</module>},
     * whether or not {@code modules/} holds a pom of its own), or through an aggregator it lists that in turn
     * lists the module. The contiguous-pom heuristic alone also matched a project that merely sits under an
     * unrelated pom (a {@code samples/} folder of another repository): Maven would then answer "Could not find
     * the selected project in the reactor". The topmost such ancestor wins, so a nested aggregator's module is
     * built from the whole reactor.
     */
    public static @Nullable Reactor describe(
            Path moduleDir, @Nullable Path boundary, Predicate<Path> hasPom, Function<Path, @Nullable String> pomText) {

        if (moduleDir == null) {
            return null;
        }
        Path module = moduleDir.toAbsolutePath().normalize();
        Path top = boundary == null ? null : boundary.toAbsolutePath().normalize();
        if (top != null && !module.startsWith(top)) {
            return null;
        }
        Path found = null;
        Path ancestor = module.getParent();
        for (int hops = 0; ancestor != null && (top != null || hops < MAX_ANCESTORS); hops++) {
            if (top != null && !ancestor.startsWith(top)) {
                break;
            }
            if (hasPom.test(ancestor) && reaches(ancestor, module, hasPom, pomText, 0, new int[] {0})) {
                found = ancestor;
            }
            ancestor = ancestor.getParent();
        }
        if (found == null) {
            return null;
        }
        return new Reactor(found, found.resolve("pom.xml"), moduleSelector(found, module));
    }

    /** {@link #describe} against the files on disk (or a host's, through its file system). */
    public static @Nullable Reactor describeOnDisk(Path moduleDir, @Nullable Path boundary) {
        return describe(moduleDir, boundary, MavenReactor::hasPomOnDisk, MavenReactor::readOrNull);
    }

    /** {@link #locateModule} against the files on disk. */
    public static @Nullable Path locateModuleOnDisk(Path root, String projectName, String mainClass) {
        return locateModule(
                root,
                projectName,
                mainClass,
                MavenReactor::hasPomOnDisk,
                MavenReactor::readOrNull,
                Files::isRegularFile);
    }

    private static boolean hasPomOnDisk(Path dir) {
        try {
            return Files.isRegularFile(dir.resolve("pom.xml"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static @Nullable String readOrNull(Path pom) {
        try {
            return Files.isRegularFile(pom) ? Files.readString(pom) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Whether {@code dir}'s pom lists {@code module} directly or through aggregators it lists. */
    private static boolean reaches(
            Path dir,
            Path module,
            Predicate<Path> hasPom,
            Function<Path, @Nullable String> pomText,
            int depth,
            int[] read) {
        if (depth > MAX_MODULE_DEPTH || read[0]++ >= MAX_MODULES_READ) {
            return false;
        }
        String text = pomText.apply(dir.resolve("pom.xml"));
        if (text == null) {
            return false;
        }
        for (String entry : PomSummary.parse(text).modules()) {
            Path child = childModule(dir, entry);
            if (child == null) {
                continue;
            }
            if (child.equals(module)) {
                return true;
            }
            if (module.startsWith(child) && !child.equals(dir) && hasPom.test(child)) {
                if (reaches(child, module, hasPom, pomText, depth + 1, read)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A {@code <module>} entry of {@code dir}'s pom as a folder ({@code pom.xml} itself may be named too). */
    private static @Nullable Path childModule(Path dir, String entry) {
        try {
            Path child = dir.resolve(entry.replace('\\', '/')).normalize();
            Path name = child.getFileName();
            return name != null && "pom.xml".equals(name.toString()) ? child.getParent() : child;
        } catch (RuntimeException badName) {
            return null;
        }
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
    public static List<String> reactorArgs(@Nullable String rootPom, String moduleSelector) {
        return List.of("-f", rootPom, "-pl", moduleSelector, "-am");
    }

    /** The line Maven prints when {@code -pl} names a folder its reactor does not hold. */
    public static final String NOT_IN_REACTOR = "Could not find the selected project in the reactor";

    /** Whether {@code line} is Maven refusing the {@code -pl} selector: the single-module run is then the retry. */
    public static boolean isNotInReactor(String line) {
        return line != null && line.contains(NOT_IN_REACTOR);
    }

    /**
     * Every module folder the reactor at {@code root} reaches through its {@code <modules>} (aggregators
     * included), {@code root} first, in document order; bounded as {@link #describe}'s walk is.
     */
    public static List<Path> modules(Path root, Predicate<Path> hasPom, Function<Path, @Nullable String> pomText) {
        List<Path> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        collect(root.toAbsolutePath().normalize(), hasPom, pomText, 0, new int[] {0}, out);
        return out;
    }

    private static void collect(
            Path dir,
            Predicate<Path> hasPom,
            Function<Path, @Nullable String> pomText,
            int depth,
            int[] read,
            List<Path> out) {
        if (depth > MAX_MODULE_DEPTH || read[0]++ >= MAX_MODULES_READ || out.contains(dir)) {
            return;
        }
        out.add(dir);
        String text = pomText.apply(dir.resolve("pom.xml"));
        if (text == null) {
            return;
        }
        for (String entry : PomSummary.parse(text).modules()) {
            Path child = childModule(dir, entry);
            if (child != null && !child.equals(dir) && hasPom.test(child)) {
                collect(child, hasPom, pomText, depth + 1, read, out);
            }
        }
    }

    /**
     * The module of a saved configuration (review D12): among {@link #modules the reactor's modules} from
     * {@code root}, the one whose pom's {@code artifactId} is {@code projectName}, else the one whose
     * {@code src/main/java} (or {@code src/test/java}) holds {@code mainClass}'s source file ({@code exists}
     * answers for a path); {@code null} when neither matches, so the caller keeps its own guess.
     */
    public static @Nullable Path locateModule(
            Path root,
            String projectName,
            String mainClass,
            Predicate<Path> hasPom,
            Function<Path, @Nullable String> pomText,
            Predicate<Path> exists) {
        List<Path> modules = modules(root, hasPom, pomText);
        if (projectName != null && !projectName.isBlank()) {
            for (Path module : modules) {
                String text = pomText.apply(module.resolve("pom.xml"));
                if (text != null
                        && projectName
                                .strip()
                                .equals(PomSummary.parse(text).coordinates().artifactId())) {
                    return module;
                }
            }
        }
        if (mainClass != null && !mainClass.isBlank()) {
            String relative = mainClass.strip().replace('.', '/') + ".java";
            for (Path module : modules) {
                for (String tree : List.of("src/main/java", "src/test/java")) {
                    if (exists.test(module.resolve(tree).resolve(relative))) {
                        return module;
                    }
                }
            }
        }
        return null;
    }
}
