package nativeimage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lib.Json;

/**
 * Compare explicit JVM/AOT/native command vectors; no shell, no generated timing claims.
 *
 * <p>Config format and limitations: docs/native-image-staticfx.md. JDK only.
 * Every run must pass its own protocol; failed runs are retained and excluded from percentiles.
 * Linux RSS samples cover the direct process, excluding LSP/DAP children; heap is reported separately.
 *
 * <pre>
 *   java scripts/nativeimage/Benchmark.java /path/to/commands.json --output /new/output/directory --runs 5 --warmups 1
 * </pre>
 */
public final class Benchmark {

    /** A startup mark printed by the application, {@code [perf] <name> <ms> (+<delta>)}. */
    static final Pattern PERF_MARK = Pattern.compile("\\[perf\\]\\s+([\\w-]+)\\s+(\\d+)\\s+\\(");

    private static final String USAGE =
            "Benchmark.java [-h] --output OUTPUT [--runs RUNS] [--warmups WARMUPS] [--timeout TIMEOUT] config";

    /** One line read from the child's standard output, and when it arrived. */
    private record Line(long stamp, String text) {}

    private static final Line END = new Line(0, null);

    private Benchmark() {}

    /** Nearest rank: keep sample count alongside tails, especially for short startup studies. */
    static Number percentile(List<? extends Number> values, double fraction) {
        List<Number> sorted = sorted(values);
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1));
    }

    /** Sample count, median (the mean of the middle two for an even count) and nearest-rank p95/p99. */
    static Map<String, Object> summarize(List<? extends Number> values) {
        List<Number> sorted = sorted(values);
        int n = sorted.size();
        Number median = n % 2 == 1
                ? sorted.get(n / 2)
                : (Number) ((sorted.get(n / 2 - 1).doubleValue() + sorted.get(n / 2).doubleValue()) / 2);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("n", n);
        summary.put("median", median);
        summary.put("p95", percentile(sorted, .95));
        summary.put("p99", percentile(sorted, .99));
        return summary;
    }

    private static List<Number> sorted(List<? extends Number> values) {
        var sorted = new ArrayList<Number>(values);
        sorted.sort((a, b) -> a instanceof Long x && b instanceof Long y
                ? Long.compare(x, y)
                : Double.compare(a.doubleValue(), b.doubleValue()));
        return sorted;
    }

    /** The process's resident set from {@code /proc} (Linux), or null where that is not available. */
    static Long rssBytes(long pid) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc", String.valueOf(pid), "status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.split("\\s+")[1]) * 1024;
                }
            }
        } catch (IOException | SecurityException e) {
            // Not Linux, or the process has already ended.
        }
        return null;
    }

    static Long treeSize(String path) throws IOException {
        Path p = Path.of(path);
        if (Files.isRegularFile(p)) {
            return Files.size(p);
        }
        if (!Files.isDirectory(p)) {
            return null;
        }
        long total = 0;
        try (Stream<Path> files = Files.walk(p)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    total += Files.size(f);
                }
            }
        }
        return total;
    }

    /** {@code path} with its last extension replaced, as Python's {@code Path.with_suffix}. */
    static Path withSuffix(Path path, String suffix) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 && dot < name.length() - 1 ? name.substring(0, dot) : name;
        return path.resolveSibling(stem + suffix);
    }

    private static double seconds(long since) {
        return (System.nanoTime() - since) / 1e9;
    }

    /** Runs one workload {@code kind} ("startup" or "editor") and returns its result record. */
    static Map<String, Object> run(List<String> command, Map<String, String> env, Path output, String kind,
            double timeout) throws IOException, InterruptedException {
        var events = new ArrayList<Map<String, Object>>();
        BlockingQueue<Line> lines = new LinkedBlockingQueue<>();
        long start = System.nanoTime();
        var builder = new ProcessBuilder(command).redirectInput(Redirect.INHERIT);
        builder.environment().putAll(env);
        builder.environment().put("EDITORA_PERF_T0", String.valueOf(System.currentTimeMillis()));
        // PrintStream.printf can make several writes; merging a grammar warning between those writes
        // corrupts a JSON line. Keep all warnings, but outside the editor's machine-readable stdout.
        Path errorPath = kind.equals("editor") ? withSuffix(output, ".stderr.log") : null;
        if (errorPath != null) {
            builder.redirectError(errorPath.toFile());
        } else {
            builder.redirectErrorStream(true);
        }
        Process proc = builder.start();
        Thread reader = Thread.ofPlatform().daemon().start(() -> read(proc.getInputStream(), lines));
        long peak = 0;
        boolean timedOut = false;
        String protocolError = null;
        boolean ended = false;
        try {
            try (var log = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                while (!ended) {
                    if (seconds(start) > timeout) {
                        timedOut = true;
                        break;
                    }
                    Long rss = rssBytes(proc.pid());
                    if (rss != null) {
                        peak = Math.max(peak, rss);
                    }
                    Line item = lines.poll(20, TimeUnit.MILLISECONDS);
                    if (item == null) {
                        continue;
                    }
                    if (item == END) {
                        ended = true;
                        continue;
                    }
                    String line = item.text();
                    log.write(line);
                    log.flush();
                    Map<String, Object> event = null;
                    if (line.startsWith("{\"kind\":")) {
                        try {
                            event = Json.object(Json.parse(line));
                        } catch (IllegalArgumentException error) {
                            protocolError = error.getMessage();
                            break;
                        }
                        event.put("received_ms", (item.stamp() - start) / 1e6);
                        event.put("rss_bytes", rssBytes(proc.pid()));
                    }
                    Matcher match = PERF_MARK.matcher(line);
                    if (match.lookingAt()) {
                        event = new LinkedHashMap<>();
                        event.put("kind", "startup-ms");
                        event.put("name", match.group(1));
                        event.put("bytes", 0L);
                        event.put("value", Long.parseLong(match.group(2)));
                    }
                    if (event != null) {
                        events.add(event);
                    }
                }
            }
            if (protocolError == null) {
                double remaining = Math.max(1, timeout - seconds(start));
                if (!proc.waitFor((long) (remaining * 1000), TimeUnit.MILLISECONDS)) {
                    timedOut = true;
                }
            }
        } finally {
            if (proc.isAlive()) {
                ProcessTree.kill(proc);
            }
            reader.join(1000);
            proc.getInputStream().close();
        }
        int exitCode = proc.exitValue();
        boolean passed = events.stream()
                .anyMatch(e -> "result".equals(e.get("kind")) && "PASS".equals(e.get("name")));
        if (kind.equals("startup")) {
            passed = events.stream()
                    .anyMatch(e -> "startup-ms".equals(e.get("kind")) && "first-paint".equals(e.get("name")));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("ok", passed && exitCode == 0 && !timedOut && protocolError == null);
        result.put("exit_code", exitCode);
        result.put("timeout", timedOut);
        result.put("events", events);
        result.put("protocol_error", protocolError);
        result.put("stderr_log", errorPath == null ? null : errorPath.toString());
        result.put("peak_rss_bytes", peak == 0 ? null : peak);
        result.put("wall_ms", seconds(start) * 1000);
        result.put("log", output.toString());
        return result;
    }

    /**
     * Queues each line of {@code stream} with its arrival time, then {@link #END}. Lines keep a {@code \n}
     * terminator; {@code \r\n} and {@code \r} become {@code \n}, as in Python's text mode.
     */
    private static void read(InputStream stream, BlockingQueue<Line> lines) {
        try (Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var line = new StringBuilder();
            char[] buffer = new char[8192];
            boolean afterCr = false;
            int count;
            while ((count = in.read(buffer)) >= 0) {
                for (int i = 0; i < count; i++) {
                    char c = buffer[i];
                    if (afterCr && c == '\n') {
                        afterCr = false;
                        continue;
                    }
                    afterCr = c == '\r';
                    if (c == '\n' || c == '\r') {
                        line.append('\n');
                        lines.add(new Line(System.nanoTime(), line.toString()));
                        line.setLength(0);
                    } else {
                        line.append(c);
                    }
                }
            }
            if (!line.isEmpty()) {
                lines.add(new Line(System.nanoTime(), line.toString()));
            }
        } catch (IOException e) {
            // The stream was closed while the child was being killed.
        } finally {
            lines.add(END);
        }
    }

    public static void main(String[] argv) throws IOException, InterruptedException {
        var p = new Args(
                USAGE,
                "Compare explicit JVM/AOT/native command vectors; no shell, no generated timing claims.",
                argv,
                List.of("--output", "--runs", "--warmups", "--timeout"),
                List.of(),
                List.of("config"));
        Path configPath = Path.of(p.positional(0));
        Path outputDir = Path.of(p.required("--output"));
        int runs = p.integer("--runs", 5);
        int warmups = p.integer("--warmups", 1);
        double timeout = p.decimal("--timeout", 600);
        if (runs < 1 || warmups < 0) {
            p.error("runs must be positive; warmups cannot be negative");
        }
        Map<String, Object> config = Json.object(Json.parse(TextFiles.read(configPath)));
        mkdirsNew(outputDir);
        TextFiles.write(outputDir.resolve("config.json"), Json.pretty(config) + "\n");
        var results = new ArrayList<Map<String, Object>>();
        Map<String, Object> modes = Json.object(config.get("modes"));
        List<String> names = new ArrayList<>(modes.keySet());
        for (int iteration = -warmups; iteration < runs; iteration++) {
            // Rotate order each round to reduce temperature/order bias. Do not evict OS page caches.
            for (String name : rotated(names, iteration)) {
                Map<String, Object> mode = Json.object(modes.get(name));
                for (String kind : List.of("startup", "editor")) {
                    if (!truthy(mode.get(kind))) {
                        continue;
                    }
                    String tag = name + "-" + kind + "-" + iteration;
                    Path fresh = outputDir.toAbsolutePath().normalize().resolve(tag + "-config");
                    Files.createDirectory(fresh);
                    List<String> command = Json.array(mode.get(kind)).stream()
                            .map(arg -> ((String) arg).replace("{config_dir}", fresh.toString()))
                            .toList();
                    var env = new LinkedHashMap<String, String>();
                    Object modeEnv = mode.get("env");
                    if (modeEnv != null) {
                        Json.object(modeEnv).forEach((k, v) -> env.put(k, (String) v));
                    }
                    env.put("EDITORA_CONFIG_DIR", fresh.toString());
                    if (kind.equals("startup")) {
                        env.put("EDITORA_PERF", "1");
                        env.put("EDITORA_PERF_EXIT", "1");
                    }
                    Map<String, Object> result = run(command, env, outputDir.resolve(tag + ".log"), kind, timeout);
                    result.put("mode", name);
                    result.put("workload", kind);
                    result.put("iteration", iteration);
                    result.put("command", command);
                    results.add(result);
                    TextFiles.write(outputDir.resolve("raw.json"), Json.pretty(results) + "\n");
                    System.out.println(tag + ": " + (Boolean.TRUE.equals(result.get("ok")) ? "PASS" : "FAIL"));
                    System.out.flush();
                }
            }
        }
        var buckets = new TreeMap<String, List<Number>>();
        var checksums = new LinkedHashMap<String, List<List<List<Object>>>>();
        var failures = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : results) {
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                failures.add(subset(
                        r, "mode", "workload", "iteration", "exit_code", "timeout", "protocol_error", "log"));
                continue;
            }
            if ((int) r.get("iteration") < 0) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = (List<Map<String, Object>>) r.get("events");
            String run = r.get("mode") + "/" + r.get("workload");
            if (r.get("workload").equals("editor")) {
                checksums.computeIfAbsent((String) r.get("mode"), k -> new ArrayList<>())
                        .add(events.stream()
                                .filter(e -> "checksum".equals(e.get("kind")))
                                .map(e -> List.of(e.get("bytes"), e.get("name")))
                                .toList());
            }
            for (Map<String, Object> e : events) {
                String prefix = run + "/" + e.get("bytes") + "/" + e.get("name");
                Object eventKind = e.get("kind");
                if (List.of("sample", "startup-ms", "heap-used-bytes").contains(eventKind)) {
                    add(buckets, prefix + "/" + eventKind, (Number) e.get("value"));
                }
                if ("milestone".equals(eventKind)) {
                    add(buckets, prefix + "/received-ms", (Number) e.get("received_ms"));
                }
                if ("heap-used-bytes".equals(eventKind) && e.get("rss_bytes") != null) {
                    add(buckets, prefix + "/rss-bytes", (Number) e.get("rss_bytes"));
                }
            }
            // Explicit late-burst subset; this is not a claim that all JIT activity has stabilized.
            var bursts = new LinkedHashMap<List<Object>, List<Number>>();
            for (Map<String, Object> e : events) {
                if ("sample".equals(e.get("kind"))
                        && List.of("insert-dispatch", "undo-dispatch", "redo-dispatch").contains(e.get("name"))) {
                    bursts.computeIfAbsent(List.of(e.get("bytes"), e.get("name")), k -> new ArrayList<>())
                            .add((Number) e.get("value"));
                }
            }
            bursts.forEach((key, values) -> {
                if (values.size() > 100) {
                    String bucket = run + "/" + key.get(0) + "/" + key.get(1) + "-after-100/sample";
                    buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).addAll(values.subList(100, values.size()));
                }
            });
            if (r.get("peak_rss_bytes") != null) {
                add(buckets, run + "/peak-rss-bytes", (Number) r.get("peak_rss_bytes"));
            }
        }
        List<List<List<Object>>> sequences =
                checksums.values().stream().flatMap(List::stream).toList();
        Boolean equivalent = sequences.isEmpty()
                ? null
                : sequences.stream().allMatch(seq -> Objects.equals(seq, sequences.getFirst()));
        var metrics = new LinkedHashMap<String, Object>();
        buckets.forEach((k, v) -> metrics.put(k, summarize(v)));
        var distributions = new LinkedHashMap<String, Object>();
        Object configured = config.get("distributions");
        if (configured != null) {
            for (var entry : Json.object(configured).entrySet()) {
                distributions.put(entry.getKey(), treeSize((String) entry.getValue()));
            }
        }
        var report = new LinkedHashMap<String, Object>();
        // Python's platform.platform()/processor() reported the libc and CPU name too; the JDK knows less.
        report.put("host", System.getProperty("os.name") + "-" + System.getProperty("os.version") + "-"
                + System.getProperty("os.arch"));
        report.put("cpu", System.getProperty("os.arch"));
        report.put("runs", runs);
        report.put("warmups", warmups);
        report.put("config_sha256", sha256(Files.readAllBytes(configPath)));
        report.put("checksum_sequences_equal", equivalent);
        report.put("failures", failures);
        report.put("metrics", metrics);
        report.put("distribution_bytes", distributions);
        report.put("limitations", List.of(
                "Warm filesystem cache; no forced GC; direct-process Linux RSS excludes children.",
                "Probe milestones are receipt times, including pipe/scheduler delay.",
                "Dispatch samples include FX queueing but exclude later layout; layout samples include two pulses.",
                "Headless pulses are not desktop paint latency. No project/LSP readiness inferred from first-paint.",
                "Missing modes and failed runs have no performance conclusion. Small-n tails are descriptive only."));
        TextFiles.write(outputDir.resolve("summary.json"), Json.pretty(report) + "\n");
        System.exit(!failures.isEmpty() || Boolean.FALSE.equals(equivalent) ? 1 : 0);
    }

    /** {@code names} rotated left by {@code iteration} (modulo its size, never negative). */
    static List<String> rotated(List<String> names, int iteration) {
        int shift = Math.floorMod(iteration, names.size());
        var order = new ArrayList<>(names.subList(shift, names.size()));
        order.addAll(names.subList(0, shift));
        return order;
    }

    /** Python truthiness of a JSON value: null, false, 0, "" and empty arrays/objects are false. */
    static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.doubleValue() != 0;
            case String s -> !s.isEmpty();
            case List<?> l -> !l.isEmpty();
            case Map<?, ?> m -> !m.isEmpty();
            default -> true;
        };
    }

    /** Creates {@code dir} and its parents, failing if it already exists (Python's {@code exist_ok=False}). */
    static void mkdirsNew(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        Files.createDirectories(dir);
    }

    static Map<String, Object> subset(Map<String, Object> map, String... keys) {
        var subset = new LinkedHashMap<String, Object>();
        for (String key : keys) {
            subset.put(key, map.get(key));
        }
        return subset;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void add(Map<String, List<Number>> buckets, String key, Number value) {
        buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }
}
