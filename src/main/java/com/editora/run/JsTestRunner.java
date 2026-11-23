package com.editora.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Which runner a JavaScript or TypeScript test file's package tests with — Jest or Vitest, else Node's own
 * {@code node --test} — and how Run File and Debug start it, as VS Code's recipes do: Node runs the runner's own entry
 * script ({@code jest/bin/jest.js}, {@code vitest/vitest.mjs}) on the file, narrowed to the test at the caret with
 * {@code -t} (Jest in one process, {@code --runInBand}, so js-debug has one program to attach to; Vitest's workers are
 * child processes js-debug attaches to by itself). Pure, except {@link #find(Path, Path)}.
 */
public final class JsTestRunner {

    private JsTestRunner() {}

    /** A test runner. */
    public enum Kind {
        JEST,
        VITEST
    }

    /**
     * A package's runner.
     *
     * @param kind which one
     * @param entry its entry script in {@code node_modules}, which Node runs
     * @param root the package's folder (where it runs)
     */
    public record Runner(Kind kind, Path entry, Path root) {}

    /**
     * Carries the test at the caret through a debug launch's Node arguments (which Node never sees: the Node routes
     * drop it, {@link DenoRun#withoutFilter}): {@code 1} for a suite or {@code 0} for a test, then its names joined by
     * {@link #SEPARATOR}.
     */
    public static final String NAMES_ARG = "--editora-test-names=";

    private static final char SEPARATOR = '\u001f';

    /** {@code target} as {@link #NAMES_ARG} carries it. */
    public static String namesArg(JsTestNames.Target target) {
        return NAMES_ARG + (target.group() ? "1" : "0") + String.join(String.valueOf(SEPARATOR), target.names());
    }

    /** The test {@link #NAMES_ARG} carries in {@code nodeArgs}, or null. */
    public static JsTestNames.@Nullable Target namesOf(@Nullable List<String> nodeArgs) {
        if (nodeArgs != null) {
            for (String a : nodeArgs) {
                if (a.startsWith(NAMES_ARG) && a.length() > NAMES_ARG.length()) {
                    String body = a.substring(NAMES_ARG.length() + 1);
                    return new JsTestNames.Target(
                            List.of(body.split(String.valueOf(SEPARATOR), -1)), a.charAt(NAMES_ARG.length()) == '1');
                }
            }
        }
        return null;
    }

    private static final List<String> VITEST_CONFIGS = configs("vitest.config");
    private static final List<String> JEST_CONFIGS = configs("jest.config");

    private static List<String> configs(String stem) {
        List<String> out = new ArrayList<>();
        for (String ext : List.of("ts", "mts", "cts", "js", "mjs", "cjs", "json")) {
            out.add(stem + "." + ext);
        }
        return List.copyOf(out);
    }

    /**
     * The runner {@code packageRoot}'s package tests with: Vitest when it depends on {@code vitest} or has a
     * {@code vitest.config.*}, else Jest when it depends on {@code jest} (or {@code jest-cli}), has a {@code jest}
     * section or a {@code jest.config.*}; empty for Node's own. {@code packageJson} is its {@code package.json} (or
     * null).
     */
    public static Optional<Kind> kindOf(Path packageRoot, @Nullable String packageJson, Predicate<Path> isFile) {
        JsonNode pkg = parse(packageJson);
        if (VITEST_CONFIGS.stream().anyMatch(c -> isFile.test(packageRoot.resolve(c))) || dependsOn(pkg, "vitest")) {
            return Optional.of(Kind.VITEST);
        }
        if (JEST_CONFIGS.stream().anyMatch(c -> isFile.test(packageRoot.resolve(c)))
                || dependsOn(pkg, "jest")
                || dependsOn(pkg, "jest-cli")
                || (pkg != null && pkg.has("jest"))) {
            return Optional.of(Kind.JEST);
        }
        return Optional.empty();
    }

    private static @Nullable JsonNode parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = new ObjectMapper().readTree(json);
            return n != null && n.isObject() ? n : null;
        } catch (Exception e) {
            return null; // a broken package.json names no runner
        }
    }

    private static boolean dependsOn(@Nullable JsonNode pkg, String name) {
        if (pkg == null) {
            return false;
        }
        for (String key : List.of("devDependencies", "dependencies", "optionalDependencies")) {
            if (pkg.path(key).has(name)) {
                return true;
            }
        }
        return false;
    }

    /** Where {@code kind}'s entry script is, under a {@code node_modules}. */
    public static String entryIn(Kind kind) {
        return kind == Kind.JEST ? "node_modules/jest/bin/jest.js" : "node_modules/vitest/vitest.mjs";
    }

    /**
     * The runner of test file {@code file}'s package ({@link NodeRuntimes#packageRoot}): its kind, and its entry script
     * in the nearest {@code node_modules} at or above the package (a workspace hoists it), not above
     * {@code projectRoot}. Empty for Node's own, or a runner that is not installed ({@code npm install} not run).
     */
    public static Optional<Runner> find(
            Path file,
            @Nullable Path projectRoot,
            @Nullable Path home,
            Predicate<Path> isFile,
            Function<Path, @Nullable String> read) {
        Optional<Path> pkgRoot = NodeRuntimes.packageRoot(file, projectRoot, home, isFile);
        if (pkgRoot.isEmpty()) {
            return Optional.empty();
        }
        Path root = pkgRoot.get();
        Optional<Kind> kind = kindOf(root, read.apply(root.resolve("package.json")), isFile);
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        Path stop = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        for (Path dir = root; dir != null; dir = dir.getParent()) {
            Path entry = dir.resolve(entryIn(kind.get()));
            if (isFile.test(entry)) {
                return Optional.of(new Runner(kind.get(), entry, root));
            }
            if (dir.equals(stop) || (stop != null && !dir.startsWith(stop))) {
                break;
            }
        }
        return Optional.empty();
    }

    /** {@link #find(Path, Path, Path, Predicate, Function)} on the real file system. Not on the FX thread. */
    public static Optional<Runner> find(Path file, @Nullable Path projectRoot) {
        String home = System.getProperty("user.home");
        return find(file, projectRoot, home == null ? null : Path.of(home), Files::isRegularFile, JsTestRunner::read);
    }

    private static @Nullable String read(Path p) {
        try {
            return Files.size(p) > 1024 * 1024 ? null : Files.readString(p);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What follows Node for {@code runner} on {@code target} (the test file as the runner is given it), narrowed to
     * {@code test} when there is one: Jest's {@code jest.js --runInBand [-t pattern] file}, Vitest's
     * {@code vitest.mjs run file [-t pattern]}.
     */
    public static List<String> args(Runner runner, String target, JsTestNames.@Nullable Target test) {
        List<String> out = new ArrayList<>();
        out.add(runner.entry().toString());
        if (runner.kind() == Kind.JEST) {
            out.add("--runInBand"); // one process: the tests run where js-debug is attached
            if (test != null) {
                out.add("-t");
                out.add(pattern(runner.kind(), test));
            }
            out.add(target);
        } else {
            out.add("run"); // once, not watching
            out.add(target);
            if (test != null) {
                out.add("-t");
                out.add(pattern(runner.kind(), test));
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code -t}'s pattern for {@code test}: its names, each literal, joined as the runner joins a test's full name
     * — Jest with a space, Vitest with {@code " > "} (a space before Vitest 3) — whole for a test, and followed by
     * the separator or the end for a suite (its tests' names start with it).
     */
    public static String pattern(Kind kind, JsTestNames.Target test) {
        String join = kind == Kind.JEST ? " " : "( | > )";
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < test.names().size(); i++) {
            if (i > 0) {
                sb.append(join);
            }
            sb.append(literal(test.names().get(i)));
        }
        sb.append(test.group() ? "(" + join + "|$)" : "$");
        return sb.toString();
    }

    private static String literal(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
