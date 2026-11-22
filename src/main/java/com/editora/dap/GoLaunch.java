package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What Delve is asked to debug for a Go file, as VS Code's Go extension asks it: the file's package when it belongs to
 * a module ({@code mode: debug} on the folder, as {@code go run .}), the file alone when it does not ({@code go run
 * file.go}), and in a {@code _test.go} file the test at the caret, or the package's tests ({@code mode: test}). Also
 * the {@code attach} request for a running Go program. Pure but for {@link #moduleRoot}, which looks for
 * {@code go.mod}.
 */
public final class GoLaunch {

    private GoLaunch() {}

    /** A test function's declaration: {@code func TestParse(t *testing.T) {}}, a benchmark, a fuzz test, an example. */
    private static final Pattern TEST_FUNC = Pattern.compile("^func\\s+((Test|Benchmark|Fuzz|Example)\\w*)\\s*\\(");

    /** A top-level function or method declaration. */
    private static final Pattern FUNC = Pattern.compile("^func[\\s(]");

    /** Whether {@code file} is a Go test file ({@code parse_test.go}). */
    public static boolean isTestFile(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        return name != null && name.toString().endsWith("_test.go");
    }

    /**
     * The test function the 0-based {@code line} of {@code text} is in — its declaration line or its body — or null
     * outside any. Read from the text: a test is a top-level {@code func Test…}, {@code Benchmark…}, {@code Fuzz…} or
     * {@code Example…}, and gofmt starts both it and its closing brace in the first column.
     */
    public static @Nullable String testAt(String text, int line) {
        if (text == null || line < 0) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        if (line >= lines.length) {
            return null;
        }
        boolean[] quoted = startsQuoted(lines, line);
        for (int i = line; i >= 0; i--) {
            String l = lines[i];
            if (quoted[i]) {
                continue; // inside a raw string or a block comment: neither a declaration nor a closing brace
            }
            if (i < line && l.startsWith("}")) {
                return null; // the caret is below a function's end, not in one
            }
            Matcher m = TEST_FUNC.matcher(l);
            if (m.find()) {
                return m.group(1);
            }
            if (FUNC.matcher(l).find()) {
                return null; // in another function
            }
        }
        return null;
    }

    /**
     * For each of {@code lines} up to {@code last}, whether it starts inside a raw string ({@code `…`}) or a block
     * comment, read from the top of the file: such a line's first column is text, not code.
     */
    static boolean[] startsQuoted(String[] lines, int last) {
        boolean[] quoted = new boolean[lines.length];
        boolean raw = false;
        boolean comment = false;
        for (int i = 0; i <= last && i < lines.length; i++) {
            quoted[i] = raw || comment;
            String l = lines[i];
            char quote = 0; // inside an interpreted string or a rune, which end with their line
            for (int c = 0; c < l.length(); c++) {
                char ch = l.charAt(c);
                char next = c + 1 < l.length() ? l.charAt(c + 1) : 0;
                if (raw) {
                    raw = ch != '`';
                } else if (comment) {
                    if (ch == '*' && next == '/') {
                        comment = false;
                        c++;
                    }
                } else if (quote != 0) {
                    if (ch == '\\') {
                        c++;
                    } else if (ch == quote) {
                        quote = 0;
                    }
                } else if (ch == '`') {
                    raw = true;
                } else if (ch == '"' || ch == '\'') {
                    quote = ch;
                } else if (ch == '/' && next == '/') {
                    break;
                } else if (ch == '/' && next == '*') {
                    comment = true;
                    c++;
                }
            }
        }
        return quoted;
    }

    /**
     * The test binary's arguments that run test function {@code name} alone: a benchmark is run without the tests
     * ({@code -test.run ^$ -test.bench ^BenchmarkX$}), a test, fuzz test (its seed corpus) or example by name.
     */
    public static List<String> testArgs(String name) {
        String exact = "^" + name + "$";
        if (name.startsWith("Benchmark")) {
            return List.of("-test.run", "^$", "-test.bench", exact);
        }
        return List.of("-test.run", exact);
    }

    /** The folder holding {@code file}'s {@code go.mod}, the file's own or an enclosing one's; null outside a module. */
    public static @Nullable Path moduleRoot(Path file) {
        Path abs = file.toAbsolutePath();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("go.mod"))) {
                return dir;
            }
        }
        return null;
    }

    /**
     * The {@code launch} request for Go file {@code file} (text {@code text}, caret on 0-based {@code caretLine}):
     * in a test file its test at the caret (all its package's tests outside one) with {@code args} after the test
     * flags; otherwise its package ({@code inModule}) or the file alone, with {@code args} as the program's. The
     * program runs in the file's folder.
     */
    public static Map<String, Object> launch(
            Path file,
            String text,
            int caretLine,
            boolean inModule,
            List<String> args,
            Map<String, String> env,
            boolean stopOnEntry) {
        return launch(file, text, caretLine, inModule, args, env, stopOnEntry, null);
    }

    /**
     * As {@link #launch(Path, String, int, boolean, List, Map, boolean)}, the program running in {@code cwd} (a run
     * configuration's working directory) rather than the file's folder when it is not null. Delve builds in the
     * file's folder either way ({@code dlvCwd}): a package is found from inside its module.
     */
    public static Map<String, Object> launch(
            Path file,
            String text,
            int caretLine,
            boolean inModule,
            List<String> args,
            Map<String, String> env,
            boolean stopOnEntry,
            @Nullable Path cwd) {
        Path dir = file.toAbsolutePath().getParent();
        String folder = dir == null ? "" : dir.toString();
        String where = cwd == null ? folder : cwd.toString();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("output", debugBinary());
        if (cwd != null && !folder.isEmpty()) {
            extra.put("dlvCwd", folder);
        }
        if (isTestFile(file)) {
            String test = testAt(text, caretLine);
            List<String> testArgs = new ArrayList<>(test == null ? List.of() : testArgs(test));
            testArgs.addAll(args);
            extra.put("mode", "test");
            return ExternalAdapter.launchRequest("go", folder, testArgs, where, env, stopOnEntry, extra);
        }
        String program = inModule ? folder : file.toAbsolutePath().toString();
        extra.put("mode", "debug");
        return ExternalAdapter.launchRequest("go", program, args, where, env, stopOnEntry, extra);
    }

    /** A file's package clause: {@code package main}. */
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+(\\w+)");

    /**
     * The package Go source {@code text} declares ({@code main} for a program), read from its package clause: the
     * first line that is not blank, a comment or a build constraint. Null when there is none to read.
     */
    public static @Nullable String packageName(@Nullable String text) {
        if (text == null) {
            return null;
        }
        // Comments may come first (a licence, the package's documentation, //go:build lines).
        String code = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        for (String line : code.split("\n", -1)) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("//")) {
                continue;
            }
            Matcher m = PACKAGE.matcher(l);
            return m.find() ? m.group(1) : null;
        }
        return null;
    }

    /**
     * What {@code go} (its argv) is asked to build for running Go file {@code file} without a debugger, in the
     * file's folder, into {@code output}: its package when it is in a module ({@code go build -o out .}), the file
     * alone outside one, and for a {@code _test.go} file its package's test binary ({@code go test -c}) — the same
     * three things {@link #launch} has Delve build.
     */
    public static List<String> buildArgv(List<String> go, Path file, boolean inModule, Path output) {
        List<String> argv = new ArrayList<>(go);
        if (isTestFile(file)) {
            argv.addAll(List.of("test", "-c", "-o", output.toString(), "."));
            return argv;
        }
        argv.addAll(List.of("build", "-o", output.toString()));
        argv.add(inModule ? "." : String.valueOf(file.getFileName()));
        return argv;
    }

    /**
     * Where Delve writes the program it builds: a file of its own in the temporary folder. Left to Delve, it is
     * {@code __debug_bin…} in the package — which Delve deletes when it ends normally, but not when Stop ends it, and
     * which a file tree, a watcher and {@code git status} all see meanwhile.
     */
    public static String debugBinary() {
        String name = DEBUG_BINARY_PREFIX
                + Long.toUnsignedString(
                        java.util.concurrent.ThreadLocalRandom.current().nextLong(), 36)
                + (com.editora.Os.isWindows() ? ".exe" : "");
        IN_USE.add(name);
        return Path.of(System.getProperty("java.io.tmpdir"), name).toString();
    }

    /**
     * The file names of the programs {@link #debugBinary} named in this process that a session may still run: {@link
     * #deleteStaleDebugBinaries} leaves them, however old (another window's session, a long one). Until {@link
     * #releaseDebugBinary}.
     */
    private static final Set<String> IN_USE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Marks the program a launch request's {@code output} names as in use again (a session started with it, or
     * restarted with it after its release). Any other value is left alone.
     */
    public static void holdDebugBinary(@Nullable Object output) {
        String name = debugBinaryName(output);
        if (name != null) {
            IN_USE.add(name);
        }
    }

    /**
     * The session that ran the program a launch request's {@code output} names has ended: it is no longer in use, and
     * is deleted on {@code deleter} ({@link #deleteDebugBinary}; Delve leaves it when Stop ends the session) unless a
     * session holds it again by then (a restart builds it anew).
     */
    public static void releaseDebugBinary(@Nullable Object output, java.util.concurrent.Executor deleter) {
        String name = debugBinaryName(output);
        if (name != null && output instanceof String path) {
            IN_USE.remove(name);
            deleter.execute(() -> {
                if (!IN_USE.contains(name)) {
                    deleteDebugBinary(path);
                }
            });
        }
    }

    /** The file name of {@code output} when it is a program {@link #debugBinary} names, else null. */
    private static @Nullable String debugBinaryName(@Nullable Object output) {
        if (!(output instanceof String path)) {
            return null;
        }
        try {
            Path name = Path.of(path).getFileName();
            return name != null && name.toString().startsWith(DEBUG_BINARY_PREFIX) ? name.toString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The prefix of {@link #debugBinary}'s file names. */
    private static final String DEBUG_BINARY_PREFIX = "editora-dlv-";

    /**
     * Deletes {@code path} when it is a program {@link #debugBinary} named — the session that built it has ended. Any
     * other path, or an error (Windows holding a file still running), is left alone.
     */
    public static void deleteDebugBinary(@Nullable String path) {
        if (path == null) {
            return;
        }
        try {
            Path p = Path.of(path);
            Path name = p.getFileName();
            if (name != null && name.toString().startsWith(DEBUG_BINARY_PREFIX)) {
                Files.deleteIfExists(p);
            }
        } catch (java.io.IOException | RuntimeException e) {
            // left for deleteStaleDebugBinaries
        }
    }

    /**
     * How old a program {@link #debugBinary} named must be before {@link #deleteStaleDebugBinaries} deletes it: a day,
     * since another Editora's session may still run one (an hour deleted the program of a session that long).
     */
    static final java.time.Duration STALE_DEBUG_BINARY_AGE = java.time.Duration.ofHours(24);

    /**
     * Deletes the programs {@link #debugBinary} named in the temporary folder that are over {@link
     * #STALE_DEBUG_BINARY_AGE} old and that no session of this process holds: those of sessions that ended without
     * deleting theirs (Editora quit, or crashed). For start-up; never throws.
     */
    public static int deleteStaleDebugBinaries() {
        return deleteStaleDebugBinaries(
                Path.of(System.getProperty("java.io.tmpdir")),
                java.time.Instant.now().minus(STALE_DEBUG_BINARY_AGE));
    }

    /**
     * Deletes {@link #debugBinary}'s programs in {@code dir} last changed before {@code before}, but for those a session
     * of this process holds; how many it deleted.
     */
    static int deleteStaleDebugBinaries(Path dir, java.time.Instant before) {
        int deleted = 0;
        try (java.nio.file.DirectoryStream<Path> files = Files.newDirectoryStream(dir, DEBUG_BINARY_PREFIX + "*")) {
            for (Path f : files) {
                try {
                    if (Files.isRegularFile(f)
                            && !IN_USE.contains(String.valueOf(f.getFileName()))
                            && Files.getLastModifiedTime(f).toInstant().isBefore(before)
                            && Files.deleteIfExists(f)) {
                        deleted++;
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    // in use, or gone: the next start-up tries again
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // no temporary folder to read
        }
        return deleted;
    }

    /**
     * What the session of {@code file} is called: the test at the caret ({@code TestParse}), a test file's package
     * tests ({@code parse_test.go}), or the file.
     */
    public static String sessionName(Path file, String text, int caretLine) {
        String name = String.valueOf(file.getFileName());
        if (isTestFile(file)) {
            String test = testAt(text, caretLine);
            return test == null ? name : test;
        }
        return name;
    }

    /** The {@code attach} request for Go process {@code pid} of this machine ({@code dlv attach}). */
    public static Map<String, Object> attach(long pid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "go");
        m.put("request", "attach");
        m.put("mode", "local");
        m.put("processId", pid);
        return m;
    }
}
