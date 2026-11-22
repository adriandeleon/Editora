package screenshots;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lib.Json;
import lib.Repo;
import lib.TempDir;

/**
 * Takes the website's screenshots (DOCS-18, Pauta 6.2.0): runs the headless harness ({@code com.editora.ui.Shots},
 * in {@code src/test/java}) on command scripts, each in light and dark, and writes 2x PNGs.
 *
 * <pre>
 *   ./mvnw -o -q test-compile dependency:build-classpath -Dmdep.outputFile=target/shots-classpath.txt \
 *       -Dmdep.includeScope=test                                  # once, and after a change to the app
 *   java scripts/screenshots/Shoot.java docs/settings.txt         # one script, light and dark
 *   java scripts/screenshots/Shoot.java --theme dark features     # every script in a folder, dark only
 *   java scripts/screenshots/Shoot.java --out ../editora-website/public/screenshots docs features
 * </pre>
 *
 * Options: {@code --theme light|dark|both} (both), {@code --out DIR} (target/screenshots/out), {@code --scratch DIR}
 * (target/screenshots), {@code --workspace DIR} (a clone of this repo under the scratch home), {@code --verbose}.
 *
 * <p>What makes two runs alike: every script starts on an empty config folder that trusts the workspace and the
 * samples; the app's home is a scratch folder named {@code ada} (user name {@code ada}, and the harness shows its
 * path as {@code /home/ada}); the workspace is reset to its last commit before each script and the debug sample's
 * sources get a fixed time; the version reads without {@code -SNAPSHOT}. A script's {@code #@} lines set up what
 * it needs on top (see {@link Directive}). docs/screenshots.md has the procedure and where the pictures go.
 */
public final class Shoot {

    /** The time the debug sample's sources carry, so File Information and Local History read the same. */
    static final LocalDateTime SAMPLE_TIME = LocalDateTime.of(2026, 11, 4, 16, 20);

    /** The home folder the shots show: the harness writes this for the scratch home's real path. */
    static final String HOME_ALIAS = "/home/ada";

    static final String BUILD_COMMAND = "./mvnw -o -q test-compile dependency:build-classpath"
            + " -Dmdep.outputFile=target/shots-classpath.txt -Dmdep.includeScope=test";

    /** A fixture line, {@code #@ name rest}: what the runner does before (or around) the script. */
    record Directive(int line, String name, String rest) {}

    private static final Pattern INSERT = Pattern.compile("(\\S+) (\\d+) (.*)");
    private static final Pattern REPLACE = Pattern.compile("(\\S+) (.+?) => (.*)");
    private static final Pattern PAIR = Pattern.compile("(\\S+) (.*)");

    public static void main(String[] args) throws Exception {
        Path root = Repo.root();
        Path scripts = root.resolve("scripts/screenshots");
        List<String> themes = List.of("light", "dark");
        Path scratch = root.resolve("target/screenshots");
        Path out = null;
        Path workspace = null;
        boolean verbose = false;
        List<Path> chosen = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--theme" -> themes = args[++i].equals("both") ? List.of("light", "dark") : List.of(args[i]);
                case "--out" -> out = Path.of(args[++i]).toAbsolutePath();
                case "--scratch" -> scratch = Path.of(args[++i]).toAbsolutePath();
                case "--workspace" -> workspace = Path.of(args[++i]).toAbsolutePath();
                case "--verbose" -> verbose = true;
                default -> chosen.addAll(scriptsIn(resolve(scripts, args[i])));
            }
        }
        if (chosen.isEmpty()) {
            System.err.println("usage: java scripts/screenshots/Shoot.java [--theme light|dark|both] [--out DIR]"
                    + " [--scratch DIR] [--workspace DIR] [--verbose] SCRIPT-OR-FOLDER...");
            System.exit(2);
        }
        Path classpathFile = root.resolve("target/shots-classpath.txt");
        if (!Files.isRegularFile(classpathFile)
                || !Files.isRegularFile(root.resolve("target/test-classes/com/editora/ui/Shots.class"))) {
            System.err.println("Build the app and its tests first, from " + root + ":\n  " + BUILD_COMMAND);
            System.exit(2);
        }
        Path home = scratch.resolve("home/ada");
        if (out == null) {
            out = scratch.resolve("out");
        }
        if (workspace == null) {
            workspace = home.resolve("workspace/Editora");
            cloneWorkspace(root, workspace);
        }
        Path override = scratch.resolve("override");
        writeReleaseBuildInfo(root.resolve("target/classes/com/editora/build-info.properties"), override);
        String classpath = String.join(
                java.io.File.pathSeparator,
                override.toString(),
                root.resolve("target/test-classes").toString(),
                root.resolve("target/classes").toString(),
                Files.readString(classpathFile).strip());

        int failed = 0;
        for (Path script : chosen) {
            for (String theme : themes) {
                String name = scripts.relativize(script).toString().replace('\\', '/').replace(".txt", "")
                        .replace('/', '-') + "-" + theme;
                System.out.println("== " + scripts.relativize(script) + " " + theme);
                Run run = new Run(script, theme, name, scratch, home, workspace, out, classpath, verbose);
                if (!run.go()) {
                    failed++;
                }
            }
        }
        System.out.println(failed == 0 ? "== done: every script ran" : "== done: " + failed + " run(s) failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** One script in one theme. */
    private record Run(
            Path script,
            String theme,
            String name,
            Path scratch,
            Path home,
            Path workspace,
            Path out,
            String classpath,
            boolean verbose) {

        boolean go() throws Exception {
            List<String> lines = Files.readAllLines(script);
            List<Directive> directives = directives(lines);
            Path config = scratch.resolve("cfg").resolve(name);
            TempDir.delete(config);
            TempDir.delete(home.resolve(".editora"));
            Files.createDirectories(config);
            Files.createDirectories(out);
            resetWorkspace(workspace);
            for (Directive d : directives) {
                if (d.name().equals("needs")) {
                    System.out.println("   needs " + d.rest());
                }
                applyFixture(d, script.getParent(), workspace);
            }
            stampSampleTimes(workspace);
            Files.writeString(config.resolve("trusted-folders.json"), trustedFolders(workspace, directives));
            Map<String, String> values = new LinkedHashMap<>();
            values.put("workspace", workspace.toString());
            values.put("out", out.toString());
            values.put("suffix", theme.equals("dark") ? ".dark" : "");
            values.put("theme", theme.equals("dark") ? "Editora Dark" : "Editora Light");
            values.put("config", config.toString());
            values.put("home", HOME_ALIAS);
            values.put("jdk", System.getProperty("java.home"));
            values.put("fixtures", script.getParent().toString());
            Path plugins = Path.of(System.getProperty("user.home"), ".editora", "plugins");
            values.put("plugins", plugins.toString());
            values.put("javaDebugJar", javaDebugJar(plugins, config));

            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-Dglass.platform=Headless",
                    "-Dprism.order=sw",
                    "-Djava.awt.headless=true",
                    "-Duser.home=" + home,
                    "-Duser.name=ada",
                    "-Deditora.shots.homeAlias=" + HOME_ALIAS,
                    "-cp",
                    classpath,
                    "com.editora.ui.Shots",
                    config.toString(),
                    script.toString()));
            values.forEach((k, v) -> command.add(k + "=" + v));
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
            pb.environment().put("HOME", home.toString());
            pb.environment().put("USERPROFILE", home.toString());
            Path log = scratch.resolve("logs").resolve(name + ".err");
            Files.createDirectories(log.getParent());
            pb.redirectError(log.toFile());

            HttpServer http = startHttpFixture(directives);
            try {
                Process p = pb.start();
                int[] failures = {0};
                Thread reader = Thread.ofVirtual().start(() -> echo(p, failures));
                long seconds = timeout(directives);
                if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    System.out.println("   timed out after " + seconds + " s (log: " + log + ")");
                    return false;
                }
                reader.join();
                if (p.exitValue() != 0) {
                    System.out.println("   " + failures[0] + " command(s) failed, exit " + p.exitValue()
                            + " (log: " + log + ")");
                }
                return p.exitValue() == 0;
            } finally {
                if (http != null) {
                    http.stop(0);
                }
            }
        }

        private void echo(Process p, int[] failures) {
            try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("FAIL")) {
                        failures[0]++;
                    }
                    if (verbose || !(line.startsWith("ok ") || line.isBlank() || line.startsWith("  split"))) {
                        System.out.println("   " + line);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** {@code arg} as a script or folder under scripts/screenshots ({@code .txt} optional), else as a path. */
    static Path resolve(Path scripts, String arg) {
        Path p = scripts.resolve(arg);
        if (!Files.exists(p)) {
            p = Files.exists(scripts.resolve(arg + ".txt")) ? scripts.resolve(arg + ".txt") : Path.of(arg);
        }
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("no script or folder " + arg + " (looked in " + scripts + ")");
        }
        return p.toAbsolutePath().normalize();
    }

    /** {@code p} itself, or every command script under it, in order. */
    static List<Path> scriptsIn(Path p) throws IOException {
        if (!Files.isDirectory(p)) {
            return List.of(p);
        }
        try (Stream<Path> files = Files.walk(p)) {
            return files.filter(f -> f.toString().endsWith(".txt")).sorted().toList();
        }
    }

    /**
     * The {@code #@} lines of a script. Known: {@code trust none} or {@code trust <folder>} (the trusted roots),
     * {@code timeout <seconds>} (240), {@code needs <what>} (printed: a tool the script relies on), {@code http
     * <port>} (the order server the HTTP Client shot calls), and the workspace fixtures {@code write <file>
     * <text>}, {@code insert <file> <line> <text>}, {@code replace <file> <regex> => <replacement>}, {@code readonly
     * <file>}, {@code copy <fixture> <file>}. In text and replacements {@code \n}, {@code \r} and {@code \t} are
     * escapes. Paths are relative to the workspace; a fixture is relative to the script.
     *
     * @throws IllegalArgumentException naming the first line that is not one of these
     */
    static List<Directive> directives(List<String> lines) {
        List<Directive> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.startsWith("#@")) {
                continue;
            }
            String body = line.substring(2).stripLeading();
            int space = body.indexOf(' ');
            String name = space < 0 ? body : body.substring(0, space);
            String rest = space < 0 ? "" : body.substring(space + 1);
            boolean ok = switch (name) {
                case "trust", "needs", "readonly" -> !rest.isBlank();
                case "timeout", "http" -> rest.strip().matches("\\d+");
                case "write", "copy" -> PAIR.matcher(rest).matches();
                case "insert" -> INSERT.matcher(rest).matches();
                case "replace" -> REPLACE.matcher(rest).matches() && compiles(REPLACE.matcher(rest));
                default -> false;
            };
            if (!ok) {
                throw new IllegalArgumentException("line " + (i + 1) + ": not a fixture line: " + line);
            }
            found.add(new Directive(i + 1, name, rest));
        }
        return found;
    }

    private static boolean compiles(Matcher m) {
        m.matches();
        try {
            Pattern.compile(m.group(2));
            return true;
        } catch (java.util.regex.PatternSyntaxException e) {
            return false;
        }
    }

    /** Applies one workspace fixture; the other directives do nothing here. */
    static void applyFixture(Directive d, Path scriptDir, Path workspace) throws IOException {
        switch (d.name()) {
            case "write" -> {
                Matcher m = matched(PAIR, d);
                Path file = workspace.resolve(m.group(1));
                Files.createDirectories(file.getParent());
                Files.writeString(file, unescape(m.group(2)));
            }
            case "insert" -> {
                Matcher m = matched(INSERT, d);
                Path file = workspace.resolve(m.group(1));
                List<String> lines = new ArrayList<>(Files.readAllLines(file));
                lines.add(Math.min(Integer.parseInt(m.group(2)) - 1, lines.size()), unescape(m.group(3)));
                Files.writeString(file, String.join("\n", lines) + "\n");
            }
            case "replace" -> {
                Matcher m = matched(REPLACE, d);
                Path file = workspace.resolve(m.group(1));
                String text = Files.readString(file);
                Matcher hit = Pattern.compile(m.group(2)).matcher(text);
                if (!hit.find()) {
                    throw new IllegalStateException("line " + d.line() + ": nothing matches in " + file);
                }
                Files.writeString(file, hit.replaceFirst(unescape(m.group(3))));
            }
            case "readonly" -> {
                Path file = workspace.resolve(d.rest().strip());
                if (!file.toFile().setWritable(false)) {
                    throw new IllegalStateException("line " + d.line() + ": cannot make " + file + " read-only");
                }
            }
            case "copy" -> {
                Matcher m = matched(PAIR, d);
                Path file = workspace.resolve(m.group(2).strip());
                Files.createDirectories(file.getParent());
                Files.copy(scriptDir.resolve(m.group(1)), file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            default -> {
                // not a workspace fixture
            }
        }
    }

    private static Matcher matched(Pattern p, Directive d) {
        Matcher m = p.matcher(d.rest());
        if (!m.matches()) {
            throw new IllegalArgumentException("line " + d.line() + ": " + d.rest());
        }
        return m;
    }

    /** {@code \n}, {@code \r}, {@code \t} and {@code \\} as the characters they name. */
    static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case '\\' -> b.append('\\');
                    default -> b.append(c).append(n);
                }
                i++;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * The trusted-folders file a script starts with: the workspace, each debug and build-tools sample, and any
     * {@code trust <folder>}; nothing at all with {@code trust none} (for the trust question's own shot).
     */
    static String trustedFolders(Path workspace, List<Directive> directives) throws IOException {
        Map<String, Object> roots = new LinkedHashMap<>();
        boolean none = directives.stream().anyMatch(d -> d.name().equals("trust") && d.rest().strip().equals("none"));
        if (!none) {
            roots.put(workspace.toString(), true);
            for (String group : List.of("samples/debug", "samples/build-tools")) {
                Path dir = workspace.resolve(group);
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> kids = Files.list(dir)) {
                        kids.filter(Files::isDirectory).sorted().forEach(k -> roots.put(k.toString(), true));
                    }
                }
            }
            directives.stream()
                    .filter(d -> d.name().equals("trust"))
                    .forEach(d -> roots.put(workspace.resolve(d.rest().strip()).toString(), true));
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("schemaVersion", 1);
        file.put("roots", roots);
        return Json.pretty(file) + "\n";
    }

    private static long timeout(List<Directive> directives) {
        return directives.stream()
                .filter(d -> d.name().equals("timeout"))
                .mapToLong(d -> Long.parseLong(d.rest().strip()))
                .findFirst()
                .orElse(240);
    }

    /**
     * The newest java-debug plugin jar Editora installed for this user, copied into the run's config folder where
     * the app looks for it; empty when there is none (the Java debugging shots then say so).
     */
    private static String javaDebugJar(Path plugins, Path config) throws IOException {
        Path dir = plugins.resolve("dap/java");
        if (!Files.isDirectory(dir)) {
            return "";
        }
        Path newest;
        try (Stream<Path> jars = Files.list(dir)) {
            newest = jars.filter(p -> p.getFileName().toString().startsWith("com.microsoft.java.debug.plugin-"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        }
        if (newest == null) {
            return "";
        }
        Path copy = config.resolve("plugins/dap/java").resolve(newest.getFileName());
        Files.createDirectories(copy.getParent());
        Files.copy(newest, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return copy.toString();
    }

    /** {@code http <port>}: a small server with the order the HTTP Client docs shot fetches. */
    private static HttpServer startHttpFixture(List<Directive> directives) throws IOException {
        Directive d = directives.stream().filter(x -> x.name().equals("http")).findFirst().orElse(null);
        if (d == null) {
            return null;
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(d.rest().strip())), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            boolean post = exchange.getRequestMethod().equals("POST");
            int code = post ? 201 : path.startsWith("/orders/42") ? 200 : 404;
            String body = post ? "{\"id\": 43, \"status\": \"created\"}" : code == 200 ? ORDER : "{\"error\": \"not found\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    static final String ORDER = "{\"id\": 42, \"status\": \"shipped\", \"customer\": {\"name\": \"Ada Lovelace\","
            + " \"email\": \"ada@example.com\"}, \"items\": [{\"sku\": \"A-1\", \"name\": \"Pencil\", \"qty\": 12,"
            + " \"price\": 0.40}, {\"sku\": \"B-2\", \"name\": \"Tape\", \"qty\": 4, \"price\": 1.20}],"
            + " \"total\": 9.60, \"currency\": \"EUR\"}";

    /**
     * The workspace the scripts open: a clone of this repository (so Git Log has its history), its origin pointed
     * at GitHub as a contributor's would be. Made once; delete it to take a newer commit.
     */
    static void cloneWorkspace(Path root, Path workspace) throws IOException, InterruptedException {
        if (Files.isDirectory(workspace.resolve(".git"))) {
            return;
        }
        Files.createDirectories(workspace.getParent());
        git(null, "clone", "--quiet", root.toString(), workspace.toString());
        git(workspace, "remote", "set-url", "origin", "https://github.com/adriandeleon/Editora.git");
        git(workspace, "checkout", "--quiet", "-B", "master"); // the status bar shows the branch
    }

    /** Back to the workspace's last commit: no file a previous script edited or added is left. */
    static void resetWorkspace(Path workspace) throws IOException, InterruptedException {
        if (!Files.isDirectory(workspace.resolve(".git"))) {
            return; // a --workspace that is not a clone is used as it is
        }
        try (Stream<Path> all = Files.walk(workspace.resolve("samples"))) {
            all.forEach(p -> p.toFile().setWritable(true)); // a readonly fixture from the last script
        }
        git(workspace, "checkout", "--quiet", "--", ".");
        git(workspace, "clean", "-fdq");
    }

    /** The debug sample's sources carry {@link #SAMPLE_TIME}, whatever the clone or a fixture did to them. */
    static void stampSampleTimes(Path workspace) throws IOException {
        Path dir = workspace.resolve("samples/debug/java/src/main/java/com/example/shelf");
        if (!Files.isDirectory(dir)) {
            return;
        }
        FileTime time = FileTime.from(SAMPLE_TIME.atZone(ZoneId.systemDefault()).toInstant());
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Files.setLastModifiedTime(f, time);
            }
        }
    }

    /**
     * A copy of the build's {@code build-info.properties} with {@code -SNAPSHOT} taken off the version, put first
     * on the class path: a development build shows a "snapshot" badge in the toolbar that a release does not.
     */
    static void writeReleaseBuildInfo(Path buildInfo, Path overrideRoot) throws IOException {
        Path copy = overrideRoot.resolve("com/editora/build-info.properties");
        Files.createDirectories(copy.getParent());
        Files.writeString(copy, Files.readString(buildInfo).replace("-SNAPSHOT", ""));
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        if (dir != null) {
            command.addAll(List.of("-C", dir.toString()));
        }
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).inheritIO().start();
        if (p.waitFor() != 0) {
            throw new IOException("failed: " + String.join(" ", command));
        }
    }
}
