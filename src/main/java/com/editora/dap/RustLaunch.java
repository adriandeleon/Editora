package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * What CodeLLDB is asked to debug for a Rust file, as VS Code's CodeLLDB and rust-analyzer ask it: Cargo builds the
 * package first ({@code cargo build}, or {@code cargo test --no-run} for a test), its JSON messages say where each
 * executable went, and the one for the file is launched — the program, or the test binary running the test at the
 * caret alone ({@code path::to::test --exact}). Pure but for {@link #packageRoot}, which looks for
 * {@code Cargo.toml}.
 */
public final class RustLaunch {

    private RustLaunch() {}

    /** What is debugged for a Rust file. */
    public enum Mode {
        /** The package's program: {@code cargo build}, then its binary. */
        PROGRAM,
        /** Tests: {@code cargo test --no-run}, then the test binary with a filter. */
        TEST
    }

    /**
     * The plan for a Rust file: what Cargo builds ({@code buildArgs} after {@code cargo}), the test filter for a test
     * binary ({@code testFilter}, empty for a program), whether it is matched exactly, and what the session is called.
     */
    public record Plan(Mode mode, List<String> buildArgs, String testFilter, boolean exact, String sessionName) {

        /** The arguments the test binary gets before the user's: the filter and {@code --exact}. */
        public List<String> testArgs() {
            if (mode != Mode.TEST || testFilter.isEmpty()) {
                return List.of();
            }
            return exact ? List.of(testFilter, "--exact") : List.of(testFilter);
        }

        /** {@code buildArgs} with Cargo's JSON messages, to read the executables from. */
        public List<String> jsonArgs() {
            List<String> out = new ArrayList<>(buildArgs);
            out.add("--message-format=json");
            return out;
        }
    }

    /** One executable Cargo built: its target's name, kinds and root file, whether it is a test binary. */
    public record Artifact(String target, List<String> kinds, Path srcPath, Path executable, boolean test) {}

    /** The folder holding {@code file}'s {@code Cargo.toml}, the nearest above it; null outside a package. */
    public static @Nullable Path packageRoot(Path file) {
        Path abs = file.toAbsolutePath();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("Cargo.toml"))) {
                return dir;
            }
        }
        return null;
    }

    /**
     * The plan for Rust file {@code file} of the package in {@code root} (text {@code text}, caret on 0-based
     * {@code caretLine}): the test at the caret; else in an integration test ({@code tests/x.rs}) all its tests; else
     * the program; or, in a library with tests in the file, the file's tests.
     */
    public static Plan plan(Path root, Path file, String text, int caretLine, boolean hasProgram) {
        Path rel = root.toAbsolutePath().relativize(file.toAbsolutePath());
        String name = String.valueOf(file.getFileName());
        boolean integration =
                rel.getNameCount() == 2 && rel.getName(0).toString().equals("tests");
        String stem = name.endsWith(".rs") ? name.substring(0, name.length() - 3) : name;
        List<String> testTarget =
                integration ? List.of("test", "--no-run", "--test", stem) : List.of("test", "--no-run");
        String test = testAt(text, caretLine);
        String module = integration ? "" : modulePath(rel);
        if (test != null) {
            String filter = join(module, test);
            return new Plan(Mode.TEST, testTarget, filter, true, test.substring(test.lastIndexOf(':') + 1));
        }
        if (integration) {
            return new Plan(Mode.TEST, testTarget, "", false, name);
        }
        boolean example = rel.getNameCount() >= 2 && rel.getName(0).toString().equals("examples");
        if (example) {
            return new Plan(Mode.PROGRAM, List.of("build", "--example", stem), "", false, name);
        }
        if (!hasProgram && hasTests(text)) {
            return new Plan(Mode.TEST, testTarget, module.isEmpty() ? "" : module + "::", false, name);
        }
        return new Plan(Mode.PROGRAM, List.of("build"), "", false, name);
    }

    /** {@code a::b} of two module paths, either of which may be empty. */
    private static String join(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() ? a : a + "::" + b;
    }

    /**
     * The module path of crate file {@code rel} (relative to the package root): {@code src/lib.rs}, {@code src/main.rs}
     * and {@code src/bin/x.rs} are their crates' roots ({@code ""}), {@code src/net/mod.rs} is {@code net} and
     * {@code src/net/http.rs} {@code net::http}.
     */
    static String modulePath(Path rel) {
        int n = rel.getNameCount();
        if (n < 2 || !rel.getName(0).toString().equals("src")) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            parts.add(rel.getName(i).toString());
        }
        if (parts.getFirst().equals("bin")) {
            return ""; // a binary of its own: its file is its root (src/bin/x/ main.rs alike)
        }
        String last = parts.removeLast();
        String stem = last.endsWith(".rs") ? last.substring(0, last.length() - 3) : last;
        if (parts.isEmpty() && (stem.equals("lib") || stem.equals("main"))) {
            return "";
        }
        if (!stem.equals("mod")) {
            parts.add(stem);
        }
        return String.join("::", parts);
    }

    /** A test attribute: {@code #[test]}, or an async runtime's ({@code #[tokio::test]}, {@code #[async_std::test]}). */
    private static final Pattern TEST_ATTRIBUTE = Pattern.compile("^#\\[(?:\\w+::)*test(?:\\(.*\\))?]");

    /** A function declaration and its name. */
    private static final Pattern FN = Pattern.compile(
            "^(?:pub(?:\\([^)]*\\))?\\s+)?(?:const\\s+)?(?:async\\s+)?(?:unsafe\\s+)?(?:extern\\s+\"[^\"]*\"\\s+)?fn\\s+(\\w+)");

    /** An inline module: {@code mod tests {}. */
    private static final Pattern MOD = Pattern.compile("^(?:pub(?:\\([^)]*\\))?\\s+)?mod\\s+(\\w+)\\s*\\{");

    /** Whether {@code text} has a test function. */
    static boolean hasTests(String text) {
        for (String line : text.split("\n", -1)) {
            if (TEST_ATTRIBUTE.matcher(line.strip()).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The test function the 0-based {@code line} of {@code text} is in — its attribute, its declaration or its body —
     * as its path within the file ({@code tests::adds}, the inline modules around it), or null outside any.
     */
    public static @Nullable String testAt(String text, int line) {
        if (text == null || line < 0) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        if (line >= lines.length) {
            return null;
        }
        Deque<Map.Entry<String, Integer>> mods = new ArrayDeque<>();
        int depth = 0;
        boolean testNext = false;
        String test = null;
        int testDepth = -1;
        int testStart = -1;
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String code = codeOf(lines[i], inBlockComment);
            inBlockComment = endsInBlockComment(lines[i], inBlockComment);
            String stripped = code.strip();
            if (test == null && (i <= line || testNext)) {
                if (TEST_ATTRIBUTE.matcher(stripped).find()) {
                    testNext = true;
                    testStart = testStart < 0 ? i : testStart;
                } else if (stripped.startsWith("#[")) {
                    // another attribute between #[test] and the fn (#[should_panic]): it stays a test
                } else {
                    Matcher fn = FN.matcher(stripped);
                    if (fn.find() && testNext) {
                        StringBuilder path = new StringBuilder();
                        for (var it = mods.descendingIterator(); it.hasNext(); ) {
                            path.append(it.next().getKey()).append("::");
                        }
                        test = path + fn.group(1);
                        testDepth = depth;
                    } else if (!stripped.isEmpty()) {
                        testStart = -1;
                    }
                    if (!stripped.isEmpty()) {
                        testNext = false;
                    }
                    Matcher mod = MOD.matcher(stripped);
                    if (mod.find()) {
                        mods.push(Map.entry(mod.group(1), depth));
                    }
                }
            }
            for (int c = 0; c < code.length(); c++) {
                char ch = code.charAt(c);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    while (!mods.isEmpty() && depth <= mods.peek().getValue()) {
                        mods.pop();
                    }
                    if (test != null && depth == testDepth) {
                        if (line >= testStart && line <= i) {
                            return test;
                        }
                        test = null; // the caret is past this test
                        testStart = -1;
                    }
                }
            }
            if (test == null && i >= line && !testNext) {
                return null; // no test is open at the caret, nor about to start (the caret on its attribute)
            }
        }
        return test != null && line >= testStart ? test : null;
    }

    /** {@code line} without its comments and string literals (their braces are not code), from the state it starts in. */
    private static String codeOf(String line, boolean inBlockComment) {
        StringBuilder out = new StringBuilder();
        boolean block = inBlockComment;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (block) {
                if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                    block = false;
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                block = true;
                i++;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < line.length() && line.charAt(j) != '"') {
                    j += line.charAt(j) == '\\' ? 2 : 1;
                }
                out.append("\"\"");
                i = j;
                continue;
            }
            if (c == '\'' && i + 2 < line.length() && (line.charAt(i + 2) == '\'' || line.charAt(i + 1) == '\\')) {
                int j = line.indexOf('\'', i + 2);
                if (j > 0) {
                    out.append("' '");
                    i = j;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean endsInBlockComment(String line, boolean inBlockComment) {
        boolean block = inBlockComment;
        for (int i = 0; i + 1 < line.length(); i++) {
            if (block && line.charAt(i) == '*' && line.charAt(i + 1) == '/') {
                block = false;
                i++;
            } else if (!block && line.charAt(i) == '/' && line.charAt(i + 1) == '/') {
                return false;
            } else if (!block && line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
                block = true;
                i++;
            }
        }
        return block;
    }

    /**
     * Runs {@code cargoJson} (Cargo with {@code --message-format=json}) in {@code root} for the messages
     * {@link #artifacts} reads — output Editora parses, so in the C locale. Blocking: off the FX thread.
     */
    public static com.editora.process.ProcessRunner.Result listArtifacts(
            Path root, List<String> cargoJson, java.time.Duration timeout) {
        return com.editora.process.ProcessRunner.run(root, timeout, cargoJson);
    }

    /** The executables Cargo's JSON {@code messages} (one per line) report built. */
    public static List<Artifact> artifacts(String messages) {
        List<Artifact> out = new ArrayList<>();
        for (String line : messages.split("\n")) {
            String l = line.strip();
            if (!l.startsWith("{")) {
                continue;
            }
            try {
                JsonObject m = JsonParser.parseString(l).getAsJsonObject();
                if (!"compiler-artifact".equals(text(m.get("reason")))) {
                    continue;
                }
                String exe = text(m.get("executable"));
                JsonObject target = m.getAsJsonObject("target");
                if (exe == null || target == null) {
                    continue;
                }
                List<String> kinds = new ArrayList<>();
                target.getAsJsonArray("kind").forEach(k -> kinds.add(k.getAsString()));
                JsonObject profile = m.getAsJsonObject("profile");
                boolean test = profile != null
                        && profile.get("test") != null
                        && profile.get("test").getAsBoolean();
                out.add(new Artifact(
                        String.valueOf(text(target.get("name"))),
                        kinds,
                        Path.of(String.valueOf(text(target.get("src_path")))),
                        Path.of(exe),
                        test));
            } catch (RuntimeException e) {
                // not a message of Cargo's: skipped
            }
        }
        return out;
    }

    private static @Nullable String text(@Nullable JsonElement e) {
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    /**
     * The executable to debug for {@code file} among {@code built}: the target whose root is the file; for a test,
     * else the library's test binary, else the program's; for a program, the binary whose root is
     * {@code src/main.rs}, else the only binary. Null when none fits.
     */
    public static @Nullable Artifact pick(List<Artifact> built, Path file, Mode mode) {
        boolean test = mode == Mode.TEST;
        List<Artifact> mine = built.stream().filter(a -> a.test() == test).toList();
        Path abs = file.toAbsolutePath().normalize();
        for (Artifact a : mine) {
            if (a.srcPath().toAbsolutePath().normalize().equals(abs)) {
                return a;
            }
        }
        if (test) {
            for (String kind : List.of("lib", "bin")) {
                for (Artifact a : mine) {
                    if (a.kinds().contains(kind)) {
                        return a;
                    }
                }
            }
            return null;
        }
        List<Artifact> bins =
                mine.stream().filter(a -> a.kinds().contains("bin")).toList();
        for (Artifact a : bins) {
            if (a.srcPath().endsWith(Path.of("src", "main.rs"))) {
                return a;
            }
        }
        return bins.size() == 1 ? bins.getFirst() : null;
    }

    /**
     * CodeLLDB's {@code launch} request for {@code program} with {@code args}, in {@code cwd}: Rust's formatters and
     * its panic stop ({@code sourceLanguages}), and the program's input and output in the Debug console
     * ({@code terminal: console}), or in a terminal Editora starts ({@code integrated}) when {@code terminal}.
     */
    public static Map<String, Object> launch(
            Path program, List<String> args, Path cwd, Map<String, String> env, boolean stopOnEntry, boolean terminal) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("sourceLanguages", List.of("rust"));
        extra.put("terminal", terminal ? "integrated" : "console");
        return ExternalAdapter.launchRequest("lldb", program.toString(), args, cwd.toString(), env, stopOnEntry, extra);
    }
}
