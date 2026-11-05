package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jspecify.annotations.Nullable;

/**
 * How Run (and a test file's ▶) runs a TypeScript file. Plain Node strips types but does not map an import of
 * {@code ./greet.js} to {@code greet.ts}, which is how every {@code "module": "NodeNext"} project writes its
 * imports — so plain Node fails on most real projects with {@code ERR_MODULE_NOT_FOUND} (review C102). In order:
 *
 * <ol>
 *   <li><b>The project's tsx</b> ({@code node_modules/tsx} from the file's folder up to the project root): Node
 *       runs with {@code --import tsx}, so it is still the chosen Node, with its flags and the test runner;</li>
 *   <li><b>the compiled file</b>: when the nearest {@code tsconfig.json} has an {@code outDir} and the file it
 *       compiles to exists ({@code src/index.ts} → {@code dist/index.js}), that runs, with source maps; the
 *       caller says so and warns when it is older than the source;</li>
 *   <li><b>plain Node</b>, with {@link #missingJsImport} to explain the {@code .js} import failure.</li>
 * </ol>
 *
 * <p>Pure: the caller supplies the file checks.
 *
 * @param kind which route
 * @param target the file to run: the source, or the compiled {@code .js}
 * @param stale for {@link Kind#BUILT}, whether the compiled file is older than the source
 */
public record TsRunRoute(Kind kind, Path target, boolean stale) {

    /** The three routes. */
    public enum Kind {
        TSX,
        BUILT,
        PLAIN
    }

    private static final ObjectMapper JSONC = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    /** How many folders above the file are searched with no project root. */
    static final int NO_PROJECT_LEVELS = 8;

    /**
     * Chooses the route for {@code file}.
     *
     * @param file a {@code .ts/.mts/.cts} file
     * @param projectRoot the window's project root, or null
     * @param isFile whether a path is an existing file
     * @param readFile a small file's text, empty when unreadable
     * @param modified a file's last-modified time in milliseconds (0 when unknown)
     */
    public static TsRunRoute choose(
            Path file,
            @Nullable Path projectRoot,
            Predicate<Path> isFile,
            Function<Path, Optional<String>> readFile,
            ToLongFunction<Path> modified) {
        Path abs = file.toAbsolutePath().normalize();
        if (tsxDir(abs, projectRoot, isFile).isPresent()) {
            return new TsRunRoute(Kind.TSX, abs, false);
        }
        Optional<Path> built = builtFile(abs, projectRoot, isFile, readFile);
        if (built.isPresent()) {
            long src = modified.applyAsLong(abs);
            long out = modified.applyAsLong(built.get());
            return new TsRunRoute(Kind.BUILT, built.get(), src > 0 && out > 0 && out < src);
        }
        return new TsRunRoute(Kind.PLAIN, abs, false);
    }

    /** The folder holding {@code node_modules/tsx}, from the file's folder up to the project root. */
    static Optional<Path> tsxDir(Path file, @Nullable Path projectRoot, Predicate<Path> isFile) {
        for (Path dir : upward(file, projectRoot)) {
            if (isFile.test(dir.resolve("node_modules").resolve("tsx").resolve("package.json"))) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    /**
     * The compiled file the nearest {@code tsconfig.json} (from the file's folder up) makes of {@code file}: its
     * {@code outDir} plus the file's path below {@code rootDir} (or, with no {@code rootDir}, below the tsconfig's
     * folder, else below its first folder — {@code include: ["src"]} compiles {@code src/a.ts} to
     * {@code dist/a.js}). Empty when there is no tsconfig, no {@code outDir}, or no such file yet.
     */
    static Optional<Path> builtFile(
            Path file, @Nullable Path projectRoot, Predicate<Path> isFile, Function<Path, Optional<String>> readFile) {
        for (Path dir : upward(file, projectRoot)) {
            Path tsconfig = dir.resolve("tsconfig.json");
            if (!isFile.test(tsconfig)) {
                continue;
            }
            JsonNode options;
            try {
                options = JSONC.readTree(readFile.apply(tsconfig).orElse("{}")).path("compilerOptions");
            } catch (java.io.IOException | RuntimeException e) {
                return Optional.empty();
            }
            String outDir = options.path("outDir").asText("");
            if (outDir.isBlank()) {
                return Optional.empty();
            }
            String rootDir = options.path("rootDir").asText("");
            Path out = dir.resolve(outDir).normalize();
            List<Path> candidates = new ArrayList<>();
            if (!rootDir.isBlank()) {
                Path root = dir.resolve(rootDir).normalize();
                if (file.startsWith(root)) {
                    candidates.add(out.resolve(root.relativize(file)));
                }
            } else if (file.startsWith(dir)) {
                Path rel = dir.relativize(file);
                if (rel.getNameCount() > 1) {
                    candidates.add(out.resolve(rel.subpath(1, rel.getNameCount()))); // the common source folder
                }
                candidates.add(out.resolve(rel));
            }
            for (Path c : candidates) {
                Path js = c.resolveSibling(jsName(String.valueOf(c.getFileName())));
                if (!js.equals(file) && isFile.test(js)) {
                    return Optional.of(js);
                }
            }
            return Optional.empty(); // the nearest tsconfig decides
        }
        return Optional.empty();
    }

    /** {@code a.ts} → {@code a.js}, {@code a.mts} → {@code a.mjs}, {@code a.cts} → {@code a.cjs}. */
    static String jsName(String tsName) {
        String n = tsName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mts")) {
            return tsName.substring(0, tsName.length() - 4) + ".mjs";
        }
        if (n.endsWith(".cts")) {
            return tsName.substring(0, tsName.length() - 4) + ".cjs";
        }
        if (n.endsWith(".ts")) {
            return tsName.substring(0, tsName.length() - 3) + ".js";
        }
        return tsName;
    }

    /**
     * Node's flags that load tsx: {@code --import tsx} (Node 20.6+ and 18.19+), {@code --loader tsx} on an older
     * Node; an unknown version gets {@code --import}.
     */
    public static List<String> tsxFlags(@Nullable String nodeVersion) {
        Optional<int[]> v = nodeVersion == null ? Optional.empty() : NodeRuntimes.parseVersion(nodeVersion);
        if (v.isPresent()) {
            int major = v.get()[0];
            int minor = v.get()[1];
            boolean imports = major > 20 || (major == 20 && minor >= 6) || (major == 18 && minor >= 19);
            if (!imports) {
                return List.of("--loader", "tsx");
            }
        }
        return List.of("--import", "tsx");
    }

    private static final Pattern MISSING =
            Pattern.compile("Cannot find module '([^']+\\.[cm]?js)' imported from (.+?\\.[cm]?ts)\\b");

    /**
     * For an {@code ERR_MODULE_NOT_FOUND} line naming a {@code .js} import from a TypeScript file, the {@code .ts}
     * file that import means (which exists) — the case plain Node cannot run — else empty.
     */
    public static Optional<Path> missingJsImport(String line, Predicate<Path> isFile) {
        if (line == null || !line.contains("Cannot find module")) {
            return Optional.empty();
        }
        Matcher m = MISSING.matcher(line);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            Path js = Path.of(m.group(1));
            String name = String.valueOf(js.getFileName());
            String stem = name.substring(0, name.lastIndexOf('.'));
            String ext = name.substring(name.lastIndexOf('.') + 1);
            String tsExt = ext.equals("mjs") ? "mts" : ext.equals("cjs") ? "cts" : "ts";
            Path ts = js.resolveSibling(stem + "." + tsExt);
            return isFile.test(ts) ? Optional.of(ts) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The file's folder, then each parent, up to (and including) the project root or a fixed depth. */
    private static List<Path> upward(Path file, @Nullable Path projectRoot) {
        List<Path> out = new ArrayList<>();
        Path stop = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        Path dir = file.getParent();
        int levels = 0;
        while (dir != null && levels++ < 64) {
            if (stop != null && !dir.startsWith(stop)) {
                break;
            }
            if (NodeRuntimes.inNodeModules(dir)) {
                dir = dir.getParent();
                continue;
            }
            out.add(dir);
            if (dir.equals(stop) || (stop == null && levels >= NO_PROJECT_LEVELS)) {
                break;
            }
            dir = dir.getParent();
        }
        return out;
    }
}
