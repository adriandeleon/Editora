package com.editora.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.process.BusyExecutable;
import com.editora.process.ProcessRunner;

import static com.editora.i18n.Messages.tr;

/**
 * Runs JavaScript response handlers on the user's own Node.js ({@code node} on the PATH, or the configured one),
 * one short process per handler: {@code node [permission flags] <shim>} with the handler's input on stdin, its result
 * line read back from stdout ({@link HttpScripts}). One instance serves one run.
 *
 * <p><b>Trust.</b> A handler is code from the file's folder, so it runs only once the folder is trusted — the same
 * trust store as build scripts and secrets. {@link #beforeRun} asks once per run (through {@link Trust}), before the
 * first request is sent; refused, every handler of the run is skipped with a warning that says why, while
 * {@code @assert}/{@code @capture} still run. A handler file ({@code > ./check.js}) must itself be in a trusted
 * folder, a regular file of at most {@link #MAX_SCRIPT_BYTES}; Editora reads it and hands the source to Node, which
 * may read nothing but the shim.
 *
 * <p><b>Limits.</b> Node is started with a minimal environment and, where it supports one, its permission model
 * ({@link HttpScripts}); it is killed after the timeout, or when the run is cancelled. A response body larger than
 * {@link CapturedResponses#MAX_BODY_CHARS} is not handed to a handler.
 */
public final class NodeResponseScripts implements ResponseScripts {

    /** The largest handler file read. */
    public static final long MAX_SCRIPT_BYTES = 1024 * 1024;

    /** The most stdout read back from one handler. */
    static final int MAX_STDOUT = 8 * 1024 * 1024;

    /** The most stderr kept, for the message of a Node that failed. */
    static final int MAX_STDERR = 16 * 1024;

    /** The default timeout of one handler (adl, 2026-10-15). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** The shim's resource, copied to the cache folder because Node runs a file. */
    static final String SHIM_RESOURCE = "/com/editora/http/http-handler-shim.js";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** {@code node --version} answers, by resolved executable and its modification time. */
    private static final Map<String, int[]> VERSIONS = new ConcurrentHashMap<>();

    /**
     * The run's settings: whether handlers run at all, the Node command ({@code node}, or a path), the timeout of one
     * handler, and the folder the shim is copied to.
     */
    public record Settings(boolean enabled, String node, Duration timeout, Path shimDir) {
        public Settings {
            node = node == null || node.isBlank() ? "node" : node.strip();
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
        }
    }

    /** The trust side, given by the window. */
    public interface Trust {
        /**
         * Whether the run's file may run scripts: asks the user when its folder is not trusted yet (may block on the
         * FX thread). {@link SecretGate.Verdict#NO_FOLDER} for an untitled buffer outside a project.
         */
        SecretGate.Verdict allowRun(BooleanSupplier cancelled);

        /** Whether {@code file} (a handler file, real path) is in a trusted folder. */
        boolean isTrusted(Path file);
    }

    private final Settings settings;
    private final Trust trust;
    private final Path httpFile;
    private final Path baseDir;
    private volatile SecretGate.Verdict verdict;

    /**
     * Handlers of {@code httpFile} (null for an untitled buffer), whose relative handler paths resolve against
     * {@code baseDir}.
     */
    public NodeResponseScripts(Settings settings, Trust trust, Path httpFile, Path baseDir) {
        this.settings = settings;
        this.trust = trust;
        this.httpFile = httpFile;
        this.baseDir = baseDir;
    }

    @Override
    public void beforeRun(List<HttpFile.Parsed> requests, BooleanSupplier cancelled) {
        if (!settings.enabled()
                || requests == null
                || requests.stream().allMatch(r -> r.handlers().isEmpty())) {
            return;
        }
        verdict(cancelled);
    }

    private SecretGate.Verdict verdict(BooleanSupplier cancelled) {
        SecretGate.Verdict v = verdict;
        if (v == null) {
            synchronized (this) {
                v = verdict;
                if (v == null) {
                    try {
                        v = trust == null ? SecretGate.Verdict.REFUSED : trust.allowRun(cancelled);
                    } catch (RuntimeException failed) {
                        v = SecretGate.Verdict.REFUSED;
                    }
                    verdict = v == null ? SecretGate.Verdict.REFUSED : v;
                    v = verdict;
                }
            }
        }
        return v;
    }

    @Override
    public Result run(HttpFile.Parsed request, HttpExchange exchange, Input input) {
        if (request.handlers().isEmpty()) {
            return Result.NONE;
        }
        try {
            return runAll(request, exchange, input);
        } catch (RuntimeException | OutOfMemoryError failed) {
            return new Result(
                    HttpTests.NONE, List.of(), List.of(tr("http.warn.handlerFailed", String.valueOf(failed))));
        }
    }

    private Result runAll(HttpFile.Parsed request, HttpExchange exchange, Input input) {
        if (!settings.enabled()) {
            return skipped(tr("http.warn.handlerOff"));
        }
        SecretGate.Verdict v = verdict(input.cancelled());
        switch (v) {
            case CANCELLED -> {
                return Result.NONE;
            }
            case NO_FOLDER -> {
                return skipped(tr("http.warn.handlerNoFolder"));
            }
            case REFUSED -> {
                return skipped(tr("http.warn.handlerUntrusted"));
            }
            default -> {}
        }
        String body = exchange.result().body();
        if (body != null && body.length() > CapturedResponses.MAX_BODY_CHARS) {
            return skipped(tr("http.warn.handlerBodyTooLarge", body.length(), CapturedResponses.MAX_BODY_CHARS));
        }
        Node node = node();
        if (node.error() != null) {
            return skipped(node.error());
        }
        Path shim;
        try {
            shim = shim(settings.shimDir());
        } catch (IOException | RuntimeException e) {
            return skipped(tr("http.warn.handlerShim", String.valueOf(e.getMessage())));
        }
        HttpTests tests = HttpTests.NONE;
        List<GlobalChange> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> globals = new LinkedHashMap<>(input.globals());
        for (HttpFile.Handler h : request.handlers()) {
            if (input.cancelled().getAsBoolean()) {
                break;
            }
            if (h.source() != null && !h.closed()) {
                continue; // the parser already warned that it is not closed
            }
            HttpScripts.Script script;
            try {
                script = script(h);
            } catch (IOException | IllegalArgumentException e) {
                warnings.add(tr("http.warn.handlerFile", h.path(), e.getMessage()));
                continue;
            }
            String nonce = "\u0001editora-" + HexFormat.of().formatHex(nonceBytes()) + ":";
            String stdin = HttpScripts.input(
                    nonce,
                    script,
                    request,
                    exchange,
                    input.environment(),
                    globals,
                    exchange.details().secretValues());
            List<String> argv = new ArrayList<>();
            argv.add(node.executable());
            argv.addAll(HttpScripts.flags(node.guard(), shim));
            argv.add(shim.toString());
            Launch launch = launch(argv, workingDir(), stdin, settings.timeout(), input.cancelled());
            if (launch.cancelled()) {
                break;
            }
            HttpTests one;
            if (launch.timedOut()) {
                one = new HttpTests(
                        List.of(),
                        List.of(),
                        tr("http.script.timeout", settings.timeout().toSeconds()));
            } else if (launch.startError() != null) {
                warnings.add(tr("http.warn.nodeStart", node.executable(), launch.startError()));
                break;
            } else {
                HttpScripts.Output out = HttpScripts.parseOutput(launch.stdout(), nonce);
                if (out == null) {
                    one = new HttpTests(
                            List.of(), List.of(), tr("http.script.crashed", launch.exit(), tail(launch.stderr())));
                } else {
                    one = new HttpTests(out.tests(), out.log(), out.error());
                    for (GlobalChange c : out.changes()) {
                        c.applyTo(globals);
                        changes.add(c);
                    }
                }
            }
            tests = tests.plus(one);
        }
        if (node.guard() == HttpScripts.Guard.NONE) {
            warnings.add(tr("http.warn.nodeUnguarded", node.version()));
        }
        return new Result(tests, changes, warnings);
    }

    private static Result skipped(String why) {
        return new Result(HttpTests.NONE, List.of(), List.of(why));
    }

    private static byte[] nonceBytes() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return b;
    }

    private Path workingDir() {
        if (baseDir != null && Files.isDirectory(baseDir)) {
            return baseDir;
        }
        return settings.shimDir();
    }

    /** The script of handler {@code h}: its inline source placed in the {@code .http} file, or its file read. */
    HttpScripts.Script script(HttpFile.Handler h) throws IOException {
        if (!h.isFile()) {
            String name =
                    httpFile == null ? "handler.js" : httpFile.toAbsolutePath().toString();
            return new HttpScripts.Script(h.source(), name, h.line(), h.column());
        }
        String written = h.path();
        if (written.isEmpty()) {
            throw new IllegalArgumentException(tr("http.handlerFile.empty"));
        }
        if (baseDir == null) {
            throw new IllegalArgumentException(tr("http.handlerFile.noFolder"));
        }
        Path file = baseDir.resolve(written).normalize();
        if (!Files.exists(file)) {
            throw new IllegalArgumentException(tr("http.handlerFile.missing"));
        }
        Path real = file.toRealPath();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(tr("http.handlerFile.notFile"));
        }
        if (trust == null || !trust.isTrusted(real)) {
            throw new IllegalArgumentException(tr("http.handlerFile.untrusted", real.getParent()));
        }
        long size = Files.size(real);
        if (size > MAX_SCRIPT_BYTES) {
            throw new IllegalArgumentException(tr("http.handlerFile.tooLarge", size, MAX_SCRIPT_BYTES));
        }
        String source = Files.readString(real, StandardCharsets.UTF_8);
        if (source.startsWith("#!")) {
            source = "//" + source.substring(2); // a shebang line: still line 1, no longer a syntax error
        }
        return new HttpScripts.Script(source, real.toString(), 0, 0);
    }

    // --- Node --------------------------------------------------------------------------------------------------

    /** The Node to run: its executable, version text and guard, or why there is none. */
    record Node(String executable, String version, HttpScripts.Guard guard, String error) {}

    private Node node() {
        List<String> resolved = ProcessRunner.resolveExecutable(List.of(settings.node()));
        String exe = resolved.get(0);
        int[] version = version(exe);
        if (version == null) {
            return new Node(exe, null, HttpScripts.Guard.NONE, tr("http.warn.nodeMissing", settings.node()));
        }
        String text = "v" + version[0] + "." + version[1] + "." + version[2];
        return new Node(exe, text, HttpScripts.guardFor(version), null);
    }

    /** {@code exe}'s {@code node --version}, asked once per executable and modification time; null when it fails. */
    public static int[] version(String exe) {
        String key = exe;
        try {
            Path p = Path.of(exe);
            if (p.isAbsolute() && Files.exists(p)) {
                key = exe + "@" + Files.getLastModifiedTime(p).toMillis();
            }
        } catch (RuntimeException | IOException ignored) {
            // the key without a time: asked again next run
        }
        int[] cached = VERSIONS.get(key);
        if (cached != null) {
            return cached;
        }
        Launch l = launch(List.of(exe, "--version"), null, "", Duration.ofSeconds(10), () -> false);
        int[] v =
                l.startError() == null && !l.timedOut() && l.exit() == 0 ? HttpScripts.parseVersion(l.stdout()) : null;
        if (v != null) {
            VERSIONS.put(key, v);
        }
        return v;
    }

    /** The shim, copied into {@code dir} under a name that changes with its content. */
    static Path shim(Path dir) throws IOException {
        if (dir == null) {
            throw new IOException(tr("http.warn.handlerShimNoFolder"));
        }
        byte[] bytes;
        try (InputStream in = NodeResponseScripts.class.getResourceAsStream(SHIM_RESOURCE)) {
            if (in == null) {
                throw new IOException(SHIM_RESOURCE + " is missing");
            }
            bytes = in.readAllBytes();
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            hash = Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
        Files.createDirectories(dir);
        Path target = dir.resolve("http-handler-shim-" + hash + ".js");
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
            return target;
        }
        // Written aside and moved in: two windows running a handler at once never run half a shim.
        Path part = Files.createTempFile(dir, "shim-", ".part");
        try {
            Files.write(part, bytes);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        return target;
    }

    // --- launching ---------------------------------------------------------------------------------------------

    /** How one Node process went. {@code startError} is set when it could not be started at all. */
    record Launch(int exit, String stdout, String stderr, boolean timedOut, boolean cancelled, String startError) {}

    /**
     * Runs {@code argv} in {@code dir} with the minimal environment, {@code stdin} as its input; kills it (and anything
     * it started) after {@code timeout} or once {@code cancelled}.
     */
    static Launch launch(List<String> argv, Path dir, String stdin, Duration timeout, BooleanSupplier cancelled) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        // The user's locale and the augmented PATH (a script's log is read by the user), then nothing else.
        ProcessRunner.applyUserEnv(pb);
        Map<String, String> env = pb.environment();
        boolean windows =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Map<String, String> minimal = HttpScripts.environment(env, ProcessRunner.augmentedPath(), windows);
        env.clear();
        env.putAll(minimal);
        Process p;
        try {
            p = BusyExecutable.start(pb);
        } catch (IOException e) {
            return new Launch(-1, "", "", false, false, e.getMessage());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outPump = pump(p.getInputStream(), out, MAX_STDOUT);
        Thread errPump = pump(p.getErrorStream(), err, MAX_STDERR);
        Thread feeder = Thread.ofVirtual().name("http-script-stdin").start(() -> {
            try (OutputStream in = p.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException closed) {
                // Node exited (or was killed) before reading everything: its result says why
            }
        });
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean timedOut = false;
        boolean wasCancelled = false;
        try {
            while (!p.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    wasCancelled = true;
                    break;
                }
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            wasCancelled = true;
        }
        if (timedOut || wasCancelled) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        try {
            p.waitFor(2, TimeUnit.SECONDS);
            outPump.join(2000);
            errPump.join(2000);
            feeder.join(500);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        int exit = p.isAlive() ? -1 : p.exitValue();
        String stdout;
        String stderr;
        synchronized (out) {
            stdout = out.toString(StandardCharsets.UTF_8);
        }
        synchronized (err) {
            stderr = err.toString(StandardCharsets.UTF_8);
        }
        return new Launch(exit, stdout, stderr, timedOut, wasCancelled, null);
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream into, int cap) {
        return Thread.ofVirtual().name("http-script-out").start(() -> {
            byte[] buf = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (into) {
                        int room = cap - into.size();
                        if (room > 0) {
                            into.write(buf, 0, Math.min(n, room));
                        }
                    }
                }
            } catch (IOException closed) {
                // the process went away
            }
        });
    }

    /** The last few lines of Node's stderr, for a failure message. */
    static String tail(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "";
        }
        String[] lines = stderr.strip().split("\\R");
        int from = Math.max(0, lines.length - 8);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
