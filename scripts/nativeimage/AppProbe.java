package nativeimage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lib.Json;
import lib.Proc;
import lib.Repo;

/**
 * Exercise the unchanged application via its existing loopback MCP interface.
 *
 * <p>Each run creates its own config and disposable Git project. No user files or settings are edited.
 * Commands come from config['modes'][name]['application'] (argv arrays, without file arguments).
 * The shipped AOT launcher can be measured without changing its main class or module graph.
 * HTTP/FX dispatch latency is end-to-end automation latency, not a keyboard/frame microbenchmark.
 *
 * <pre>
 *   java scripts/nativeimage/AppProbe.java /path/to/application-commands.json \
 *       --output /new/application-results --runs 5 --warmups 1
 * </pre>
 */
public final class AppProbe {

    private static final String USAGE =
            "AppProbe.java [-h] --output OUTPUT [--runs RUNS] [--warmups WARMUPS] [--cycles CYCLES] config";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private AppProbe() {}

    /** A body that may throw what the workflow catches as a failed run. */
    @FunctionalInterface
    private interface Action<T> {
        T get() throws IOException, InterruptedException;
    }

    static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    /** Discovery files can exist briefly before their writer has finished publishing JSON. */
    static Object publishedJson(Path path) throws IOException {
        try {
            return Json.parse(TextFiles.read(path));
        } catch (NoSuchFileException | Json.ParseException e) {
            return null;
        }
    }

    /** One application launch and its scripted workflow. */
    private static final class Run {
        private final Process proc;
        private final Path logPath;
        private final Path file;
        private final long started;
        private final long deadline;
        private final Map<String, List<Double>> samples = new LinkedHashMap<>();
        private final Map<String, Long> memory = new LinkedHashMap<>();
        private final Map<String, Double> milestones = new LinkedHashMap<>();
        private Map<String, Object> endpoint;
        private int requestId;

        Run(Process proc, Path logPath, Path file, long started, int cycles) {
            this.proc = proc;
            this.logPath = logPath;
            this.file = file;
            this.started = started;
            this.deadline = started + (120L + cycles) * 1_000_000_000L;
        }

        void alive() {
            require(proc.isAlive(), "application exited " + exitCode() + "; see " + logPath);
            require(System.nanoTime() < deadline, "application workflow deadline exceeded");
        }

        private String exitCode() {
            return proc.isAlive() ? "None" : String.valueOf(proc.exitValue());
        }

        Object rpc(String method, Map<String, Object> params) throws IOException, InterruptedException {
            alive();
            requestId++;
            var body = new LinkedHashMap<String, Object>();
            body.put("jsonrpc", "2.0");
            body.put("id", requestId);
            body.put("method", method);
            body.put("params", params);
            HttpRequest request = HttpRequest.newBuilder(URI.create((String) endpoint.get("url")))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + endpoint.get("token"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP Error " + response.statusCode() + ": " + response.body().strip());
            }
            Map<String, Object> result = Json.object(Json.parse(response.body()));
            require(!result.containsKey("error"), "JSON-RPC error: " + Json.write(result.get("error")));
            return result.get("result");
        }

        Object call(String name, Object... arguments) throws IOException, InterruptedException {
            var args = new LinkedHashMap<String, Object>();
            for (int i = 0; i < arguments.length; i += 2) {
                args.put((String) arguments[i], arguments[i + 1]);
            }
            var params = new LinkedHashMap<String, Object>();
            params.put("name", name);
            params.put("arguments", args);
            Map<String, Object> result = Json.object(rpc("tools/call", params));
            require(!Benchmark.truthy(result.get("isError")),
                    "MCP tool failed: " + name + ": " + Json.write(result.get("content")));
            var content = Json.object(Json.array(result.get("content")).getFirst());
            return Json.parse((String) content.get("text"));
        }

        String text() throws IOException, InterruptedException {
            return (String) Json.object(call("read_buffer", "path", file.toString())).get("text");
        }

        <T> T timed(String name, Action<T> action) throws IOException, InterruptedException {
            long start = System.nanoTime();
            T result = action.get();
            samples.computeIfAbsent(name, k -> new ArrayList<>()).add((System.nanoTime() - start) / 1e6);
            return result;
        }

        void mark(String name) {
            milestones.put(name, (System.nanoTime() - started) / 1e6);
            memory.put(name, Benchmark.rssBytes(proc.pid()));
        }
    }

    static Map<String, Object> run(List<String> application, Map<String, String> modeEnv, Path folder, int cycles)
            throws IOException, InterruptedException {
        Files.createDirectory(folder);
        Path config = folder.resolve("config");
        Path project = folder.resolve("project");
        Files.createDirectory(config);
        Files.createDirectory(project);
        Matcher schemaMatch = Pattern.compile("SCHEMA_VERSION = (\\d+)")
                .matcher(TextFiles.read(Repo.root().resolve("src/main/java/com/editora/config/Settings.java")));
        require(schemaMatch.find(), "Settings.SCHEMA_VERSION not found");
        var settings = new LinkedHashMap<String, Object>();
        settings.put("schemaVersion", Integer.parseInt(schemaMatch.group(1)));
        settings.put("mcpSupport", true);
        settings.put("projectSupport", true);
        settings.put("updateCheck", false);
        settings.put("lspSupport", false);
        TextFiles.write(config.resolve("settings.json"), Json.write(settings));
        String initial = ("public class Example { int nativeProbeValue = 1; }\n" + "// filler\n".repeat(10240))
                .substring(0, 102400);
        Path file = project.resolve("Example.java");
        TextFiles.write(file, initial);
        TextFiles.write(project.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>probe</groupId>"
                        + "<artifactId>probe</artifactId><version>1</version></project>\n");
        for (List<String> args : List.of(
                List.of("init", "-q", "--initial-branch=probe"),
                List.of("add", "."),
                List.of("-c", "user.name=Editora Probe", "-c", "user.email=probe@example.invalid",
                        "-c", "commit.gpgsign=false", "commit", "-qm", "fixture"))) {
            var git = new ArrayList<>(List.of("git", "-C", project.toString()));
            git.addAll(args);
            Proc.check(git);
        }
        var command = new ArrayList<>(application);
        command.addAll(List.of("--config-dir", config.toString(), "--no-session", "--project", project.toString(),
                file.toString()));
        Path logPath = folder.resolve("app.log");
        long started = System.nanoTime();
        var builder = new ProcessBuilder(command)
                .redirectInput(Redirect.INHERIT)
                .redirectOutput(logPath.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(modeEnv);
        builder.environment().put("EDITORA_CONFIG_DIR", config.toString());
        builder.environment().put("EDITORA_PERF", "1");
        builder.environment().put("EDITORA_PERF_EXIT", "0");
        builder.environment().put("EDITORA_PERF_T0", String.valueOf(System.currentTimeMillis()));
        var r = new Run(builder.start(), logPath, file, started, cycles);
        boolean passed = false;
        String failure = null;
        try {
            while (r.endpoint == null) {
                r.alive();
                Object published = publishedJson(config.resolve("mcp-endpoint.json"));
                r.endpoint = published == null ? null : Json.object(published);
                if (r.endpoint == null) {
                    Thread.sleep(10);
                }
            }
            var clientInfo = new LinkedHashMap<String, Object>();
            clientInfo.put("name", "editora-native-probe");
            clientInfo.put("version", "1");
            var init = new LinkedHashMap<String, Object>();
            init.put("protocolVersion", "2024-11-05");
            init.put("capabilities", Map.of());
            init.put("clientInfo", clientInfo);
            r.rpc("initialize", init);
            // Wait for both actual file paint and a populated buffer. Stage.show alone is insufficient.
            while (!TextFiles.read(logPath).contains("[perf] first-paint")) {
                r.alive();
                Thread.sleep(10);
            }
            require(r.text().equals(initial), "initial file oracle");
            r.mark("painted-file-and-project");
            r.call("edit_buffer", "path", file.toString(), "old_text", "nativeProbeValue = 1",
                    "new_text", "nativeProbeValue = 2");
            String changed = initial.replace("nativeProbeValue = 1", "nativeProbeValue = 2");
            require(r.text().equals(changed), "first editable-file oracle");
            r.mark("first-file-editable");
            r.call("execute_command", "id", "edit.undo");
            require(r.text().equals(initial), "first undo");
            r.call("execute_command", "id", "edit.redo");
            require(r.text().equals(changed), "first redo");
            // MCP preserves ordinary adjacent-edit merging. Undo between cycles gives an explicit
            // boundary without changing production behavior or assuming one history step per request.
            String cycle = initial.replace("nativeProbeValue = 1", "nativeProbeValue = 3");
            for (int i = 0; i < cycles; i++) {
                r.timed("edit-via-mcp", () -> r.call("edit_buffer", "path", file.toString(),
                        "old_text", "nativeProbeValue = 2", "new_text", "nativeProbeValue = 3"));
                require(r.text().equals(cycle), "cycle edit oracle");
                r.timed("undo-via-mcp", () -> r.call("execute_command", "id", "edit.undo"));
                require(r.text().equals(changed), "cycle undo oracle");
                r.timed("redo-via-mcp", () -> r.call("execute_command", "id", "edit.redo"));
                require(r.text().equals(cycle), "cycle redo oracle");
                r.call("execute_command", "id", "edit.undo");
            }
            r.call("edit_buffer", "path", file.toString(), "old_text", "nativeProbeValue = 2",
                    "new_text", "nativeProbeValue = 202");
            String fin = initial.replace("nativeProbeValue = 1", "nativeProbeValue = 202");
            require(r.text().equals(fin), "final edit oracle");
            r.timed("save-settled-via-mcp", () -> {
                r.call("save_buffer", "path", file.toString());
                while (!TextFiles.read(file).equals(fin)) {
                    r.alive();
                    Thread.sleep(5);
                }
                return null;
            });
            r.mark("after-editing-and-save");
            Object hits = r.timed("project-search-via-mcp", () -> r.call("find_in_files", "query", "nativeProbeValue"));
            require(Json.array(hits).stream().anyMatch(h -> file.toString().equals(Json.object(h).get("file"))),
                    "project search contains fixture");
            r.mark("project-search-responsive");
            Map<String, Object> status = Json.object(r.call("git_status"));
            require(Benchmark.truthy(status.get("repo"))
                            && Json.array(status.get("files")).stream()
                                    .anyMatch(f -> "Example.java".equals(Json.object(f).get("path"))),
                    "Git sees the saved modification");
            require(r.text().equals(fin), "final buffer oracle");
            Path history = config.resolve("history/index.json");
            String expectedHash = Benchmark.sha256(fin.getBytes(StandardCharsets.UTF_8));
            Object stored = null;
            while (stored == null || !Json.write(stored).contains(expectedHash)) {
                r.alive();
                stored = publishedJson(history);
                Thread.sleep(5);
            }
            boolean persisted = false;
            for (Object files : Json.object(Json.object(stored).get("byProject")).values()) {
                for (Object revisions : Json.object(files).values()) {
                    for (Object revision : Json.array(revisions)) {
                        persisted |= expectedHash.equals(Json.object(revision).get("sha256"));
                    }
                }
            }
            require(persisted, "local history persisted");
            r.alive();
            passed = true;
        } catch (AssertionError | IOException | UncheckedIOException | IllegalArgumentException
                | IllegalStateException | ClassCastException | NullPointerException error) {
            failure = error.getMessage() != null ? error.getMessage() : error.toString();
        } finally {
            // A harness-owned disposable process; no app quit dialog can leave it running after failure.
            if (!r.proc.isAlive() && r.proc.exitValue() != 0) {
                passed = false;
                failure = "application exited " + r.proc.exitValue() + "; see " + logPath;
            }
            if (r.proc.isAlive()) {
                ProcessTree.terminate(r.proc, 5);
            }
        }
        var marks = new LinkedHashMap<String, Object>();
        Matcher mark = Benchmark.PERF_MARK.matcher(TextFiles.read(logPath));
        while (mark.find()) {
            marks.put(mark.group(1), Long.parseLong(mark.group(2)));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("ok", passed);
        result.put("failure", failure);
        result.put("command", command);
        result.put("milestones_ms", r.milestones);
        result.put("rss_bytes", r.memory);
        result.put("startup_marks_ms", marks);
        result.put("samples_ms", r.samples);
        result.put("final_sha256", Benchmark.sha256(Files.readAllBytes(file)));
        result.put("log", logPath.toString());
        return result;
    }

    public static void main(String[] argv) throws IOException, InterruptedException {
        var p = new Args(
                USAGE,
                "Exercise the unchanged application via its existing loopback MCP interface.",
                argv,
                List.of("--output", "--runs", "--warmups", "--cycles"),
                List.of(),
                List.of("config"));
        Path configPath = Path.of(p.positional(0));
        Path output = Path.of(p.required("--output")).toAbsolutePath().normalize();
        int runs = p.integer("--runs", 5);
        int warmups = p.integer("--warmups", 1);
        int cycles = p.integer("--cycles", 30);
        require(runs > 0 && warmups >= 0 && 1 <= cycles && cycles <= 2000, "invalid run/cycle count");
        Map<String, Object> config = Json.object(Json.parse(TextFiles.read(configPath)));
        Benchmark.mkdirsNew(output);
        TextFiles.write(output.resolve("config.json"), Json.pretty(config) + "\n");
        var modes = new LinkedHashMap<String, Map<String, Object>>();
        Json.object(config.get("modes")).forEach((name, mode) -> {
            if (Json.object(mode).containsKey("application")) {
                modes.put(name, Json.object(mode));
            }
        });
        require(!modes.isEmpty(), "no application commands configured");
        List<String> names = new ArrayList<>(modes.keySet());
        var results = new ArrayList<Map<String, Object>>();
        for (int iteration = -warmups; iteration < runs; iteration++) {
            for (String name : Benchmark.rotated(names, iteration)) {
                Map<String, Object> mode = modes.get(name);
                List<String> application = Json.array(mode.get("application")).stream()
                        .map(String.class::cast)
                        .toList();
                var env = new LinkedHashMap<String, String>();
                Object modeEnv = mode.get("env");
                if (modeEnv != null) {
                    Json.object(modeEnv).forEach((k, v) -> env.put(k, (String) v));
                }
                Map<String, Object> result = run(application, env, output.resolve(name + "-" + iteration), cycles);
                result.put("mode", name);
                result.put("iteration", iteration);
                results.add(result);
                TextFiles.write(output.resolve("raw.json"), Json.pretty(results) + "\n");
                Object failure = result.get("failure");
                System.out.println(name + "-" + iteration + ": " + (Boolean.TRUE.equals(result.get("ok"))
                        ? "PASS"
                        : "FAIL: " + (failure == null ? "None" : failure)));
                System.out.flush();
            }
        }
        var buckets = new TreeMap<String, List<Number>>();
        for (Map<String, Object> result : results) {
            if (!Boolean.TRUE.equals(result.get("ok")) || (int) result.get("iteration") < 0) {
                continue;
            }
            for (String kind : List.of("milestones_ms", "rss_bytes", "startup_marks_ms", "samples_ms")) {
                for (var entry : ((Map<?, ?>) result.get(kind)).entrySet()) {
                    Object value = entry.getValue();
                    if (value == null) {
                        continue;
                    }
                    List<Number> bucket = buckets.computeIfAbsent(
                            result.get("mode") + "/" + kind + "/" + entry.getKey(), k -> new ArrayList<>());
                    if (value instanceof List<?> list) {
                        list.forEach(v -> bucket.add((Number) v));
                    } else {
                        bucket.add((Number) value);
                    }
                }
            }
        }
        var failures = new ArrayList<Map<String, Object>>();
        Set<Object> hashes = new HashSet<>();
        for (Map<String, Object> result : results) {
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                failures.add(Benchmark.subset(result, "mode", "iteration", "failure", "log"));
            } else {
                hashes.add(result.get("final_sha256"));
            }
        }
        var metrics = new LinkedHashMap<String, Object>();
        buckets.forEach((k, v) -> metrics.put(k, Benchmark.summarize(v)));
        var report = new LinkedHashMap<String, Object>();
        report.put("runs", runs);
        report.put("warmups", warmups);
        report.put("cycles", cycles);
        report.put("failures", failures);
        report.put("final_files_equal", hashes.size() == 1);
        report.put("metrics", metrics);
        report.put("limitations", List.of(
                "MCP requests add HTTP serialization, polling and FX queue delay.",
                "Project search response is not proof that every background subsystem is ready.",
                "RSS is the application process, excluding Git/LSP children; no heap metric here.",
                "MCP enabled, update checks/LSP disabled in a fresh disposable config.",
                "Short edit/undo/redo cycles are not hours of steady-state use."));
        TextFiles.write(output.resolve("summary.json"), Json.pretty(report) + "\n");
        System.exit(!failures.isEmpty() || hashes.size() != 1 ? 1 : 0);
    }
}
