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
import org.jspecify.annotations.Nullable;

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
    public record Settings(
            boolean enabled,
            String node,
            Duration timeout,
            Path shimDir,
            @Nullable Debugger debugger,
            String nodeSetting,
            @Nullable Path projectRoot) {
        public Settings {
            node = node == null || node.isBlank() ? "node" : node.strip();
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
            nodeSetting = nodeSetting == null ? "" : nodeSetting.strip();
        }

        /**
         * With the HTTP field blank (or a bare {@code node}), the run uses the Node.js every Node feature shares
         * ({@link HttpNodeChoice}): {@code nodeSetting} is Settings → Node.js, {@code projectRoot} bounds the
         * {@code .nvmrc} lookup.
         */
        public Settings(boolean enabled, String node, Duration timeout, Path shimDir, @Nullable Debugger debugger) {
            this(enabled, node, timeout, shimDir, debugger, "", null);
        }

        /** Settings for a run that is not debugged. */
        public Settings(boolean enabled, String node, Duration timeout, Path shimDir) {
            this(enabled, node, timeout, shimDir, null);
        }
    }

    /**
     * Attaches a debugger to a script waiting for one (Debug Request): each script of the run is started with
     * {@code --inspect-brk} on a free 127.0.0.1 port — and, under the permission model, {@code --allow-inspector}
     * (adl, 2026-10-16) — and this is told the port once Node listens. No timeout applies while it is debugged; a
     * debugger that has not attached after {@link #ATTACH_DEADLINE} is given up on and the script stopped.
     */
    public interface Debugger {
        /**
         * Called on a launcher thread once the script named {@code label} waits on {@code port}; {@code shim} is the
         * runner around it, to be stepped over. {@code unguardedNode} is the Node version when the script runs without
         * the permission model because that Node cannot let a debugger in under it ({@link
         * HttpScripts#allowsInspector}), else null.
         */
        void attach(int port, String label, @Nullable Path shim, @Nullable String unguardedNode);
    }

    /** How long a debugged script waits for its debugger to attach. */
    static final Duration ATTACH_DEADLINE = Duration.ofSeconds(30);

    private static final java.util.regex.Pattern INSPECTOR_LISTENING =
            java.util.regex.Pattern.compile("Debugger listening on ws://127\\.0\\.0\\.1:(\\d+)/");

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
    private volatile SecretGate.@Nullable Verdict verdict;

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
                || requests.stream()
                        .allMatch(r -> r.handlers().isEmpty() && r.preScripts().isEmpty())) {
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

    /** Why no script of this run can start, or {@code null} when they can. */
    private enum Skip {
        OFF,
        NO_FOLDER,
        UNTRUSTED,
        CANCELLED,
        NO_NODE,
        SHIM
    }

    /** The Node and shim a script runs with, or why it cannot run ({@code skip}, with {@code detail}). */
    private record Ready(
            @Nullable Node node,
            @Nullable Path shim,
            @Nullable Skip skip,
            @Nullable String detail) {
        /** The Node of a run that can start (no {@code skip}). */
        Node runNode() {
            if (node == null) {
                throw new IllegalStateException("No Node to run scripts with: " + skip);
            }
            return node;
        }

        /** The shim of a run that can start (no {@code skip}). */
        Path runShim() {
            if (shim == null) {
                throw new IllegalStateException("No script shim: " + skip);
            }
            return shim;
        }
    }

    private Ready ready(BooleanSupplier cancelled) {
        if (!settings.enabled()) {
            return new Ready(null, null, Skip.OFF, null);
        }
        switch (verdict(cancelled)) {
            case CANCELLED -> {
                return new Ready(null, null, Skip.CANCELLED, null);
            }
            case NO_FOLDER -> {
                return new Ready(null, null, Skip.NO_FOLDER, null);
            }
            case REFUSED -> {
                return new Ready(null, null, Skip.UNTRUSTED, null);
            }
            default -> {}
        }
        Node node = node();
        if (node.error() != null) {
            return new Ready(node, null, Skip.NO_NODE, node.executable());
        }
        try {
            return new Ready(node, shim(settings.shimDir()), null, null);
        } catch (IOException | RuntimeException e) {
            return new Ready(node, null, Skip.SHIM, String.valueOf(e.getMessage()));
        }
    }

    private Result runAll(HttpFile.Parsed request, HttpExchange exchange, Input input) {
        String body = exchange.result().body();
        Ready ready = ready(input.cancelled());
        if (ready.skip() != null) {
            return switch (ready.skip()) {
                case CANCELLED -> Result.NONE;
                case OFF -> skipped(tr("http.warn.handlerOff"));
                case NO_FOLDER -> skipped(tr("http.warn.handlerNoFolder"));
                case UNTRUSTED -> skipped(tr("http.warn.handlerUntrusted"));
                case NO_NODE -> skipped(tr("http.warn.nodeMissing", ready.detail()));
                case SHIM -> skipped(tr("http.warn.handlerShim", ready.detail()));
            };
        }
        if (body != null && body.length() > CapturedResponses.MAX_BODY_CHARS) {
            return skipped(tr("http.warn.handlerBodyTooLarge", body.length(), CapturedResponses.MAX_BODY_CHARS));
        }
        Node node = ready.runNode();
        HttpExchange.Details details = exchange.details();
        List<String> secretValues = details == null ? List.of() : details.secretValues();
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
            String nonce = nonce();
            String stdin =
                    HttpScripts.input(nonce, script, request, exchange, input.environment(), globals, secretValues);
            Launch launch = launch(
                    argv(ready, debugging()),
                    workingDir(),
                    stdin,
                    settings.timeout(),
                    input.cancelled(),
                    watch(tr("http.debug.label.handler", label(request)), ready));
            if (launch.cancelled()) {
                break;
            }
            if (launch.startError() != null) {
                warnings.add(tr("http.warn.nodeStart", node.executable(), launch.startError()));
                break;
            }
            HttpScripts.Output out = launch.timedOut() ? null : HttpScripts.parseOutput(launch.stdout(), nonce);
            tests = tests.plus(outcome(launch, out));
            if (out != null) {
                for (GlobalChange c : out.changes()) {
                    c.applyTo(globals);
                    changes.add(c);
                }
            }
        }
        if (node.guard() == HttpScripts.Guard.NONE) {
            warnings.add(tr("http.warn.nodeUnguarded", node.version()));
        } else if (debugging() && debugsUnguarded(ready)) {
            warnings.add(tr("http.warn.debugUnguarded", node.version()));
        }
        return new Result(tests, changes, warnings);
    }

    @Override
    public PreResult runPre(HttpFile.Parsed request, PreInput input) {
        if (request.preScripts().isEmpty()) {
            return PreResult.NONE;
        }
        try {
            return runPreScripts(request, input);
        } catch (RuntimeException | OutOfMemoryError failed) {
            return notRun(
                    tr("http.preScript.why.failed", String.valueOf(failed)), HttpTests.NONE, List.of(), List.of());
        }
    }

    /**
     * Runs the pre-request scripts in order, each seeing the {@code request.variables} the ones before it set. Any that
     * cannot run, or stops with an error, keeps the request unsent (adl, 2026-10-16): it was written expecting the
     * script's values. {@code client.exit()} is not an error.
     */
    private PreResult runPreScripts(HttpFile.Parsed request, PreInput input) {
        Ready ready = ready(input.cancelled());
        if (ready.skip() != null) {
            String why = switch (ready.skip()) {
                case CANCELLED -> null;
                case OFF -> tr("http.preScript.why.off");
                case NO_FOLDER -> tr("http.preScript.why.noFolder");
                case UNTRUSTED -> tr("http.preScript.why.untrusted");
                case NO_NODE -> tr("http.preScript.why.noNode", ready.detail());
                case SHIM -> tr("http.preScript.why.shim", ready.detail());
            };
            return why == null ? cancelled() : notRun(why, HttpTests.NONE, List.of(), List.of());
        }
        Node node = ready.runNode();
        HttpTests tests = HttpTests.NONE;
        List<GlobalChange> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> globals = new LinkedHashMap<>(input.globals());
        Map<String, String> variables = new LinkedHashMap<>();
        for (HttpFile.Handler h : request.preScripts()) {
            if (input.cancelled().getAsBoolean()) {
                return cancelled();
            }
            if (h.source() != null && !h.closed()) {
                return notRun(tr("http.preScript.why.unclosed"), tests, changes, warnings);
            }
            HttpScripts.Script script;
            try {
                script = script(h);
            } catch (IOException | IllegalArgumentException e) {
                return notRun(
                        tr("http.preScript.why.file", String.valueOf(h.path()), String.valueOf(e.getMessage())),
                        tests,
                        changes,
                        warnings);
            }
            String nonce = nonce();
            String stdin = HttpScripts.preInput(nonce, script, request, input, variables, globals);
            Launch launch = launch(
                    argv(ready, debugging()),
                    workingDir(),
                    stdin,
                    settings.timeout(),
                    input.cancelled(),
                    watch(tr("http.debug.label.pre", label(request)), ready));
            if (launch.cancelled()) {
                return cancelled();
            }
            if (launch.debuggerMissing()) {
                return notRun(tr("http.preScript.why.debugger"), tests, changes, warnings);
            }
            if (launch.startError() != null) {
                return notRun(
                        tr("http.preScript.why.start", node.executable(), launch.startError()),
                        tests,
                        changes,
                        warnings);
            }
            HttpScripts.Output out = launch.timedOut() ? null : HttpScripts.parseOutput(launch.stdout(), nonce);
            tests = tests.plus(outcome(launch, out));
            if (out != null) {
                for (GlobalChange c : out.changes()) {
                    c.applyTo(globals);
                    changes.add(c);
                }
                variables.putAll(out.variables());
            }
            if (launch.timedOut()) {
                return notRun(
                        tr("http.preScript.why.timeout", settings.timeout().toSeconds()), tests, changes, warnings);
            }
            if (out == null) {
                return notRun(tr("http.preScript.why.crashed", launch.exit()), tests, changes, warnings);
            }
            if (out.error() != null) {
                return notRun(tr("http.preScript.why.error"), tests, changes, warnings);
            }
        }
        if (node.guard() == HttpScripts.Guard.NONE) {
            warnings.add(tr("http.warn.nodeUnguarded", node.version()));
        } else if (debugging() && debugsUnguarded(ready)) {
            warnings.add(tr("http.warn.debugUnguarded", node.version()));
        }
        return new PreResult(null, variables, tests, changes, warnings);
    }

    private static PreResult notRun(String why, HttpTests tests, List<GlobalChange> changes, List<String> warnings) {
        return new PreResult(tr("status.http.preScriptNotRun", why), Map.of(), tests, changes, warnings);
    }

    private static PreResult cancelled() {
        return new PreResult(HttpClientService.CANCELLED, Map.of(), HttpTests.NONE, List.of(), List.of());
    }

    /** What one launch reports as tests: its output, or the timeout or crash as the error. */
    private HttpTests outcome(Launch launch, HttpScripts.@Nullable Output out) {
        if (launch.debuggerMissing()) {
            return new HttpTests(List.of(), List.of(), tr("http.script.debuggerMissing", ATTACH_DEADLINE.toSeconds()));
        }
        if (launch.timedOut()) {
            return new HttpTests(
                    List.of(),
                    List.of(),
                    tr("http.script.timeout", settings.timeout().toSeconds()));
        }
        if (out == null) {
            return new HttpTests(List.of(), List.of(), tr("http.script.crashed", launch.exit(), tail(launch.stderr())));
        }
        return new HttpTests(out.tests(), out.log(), out.error());
    }

    private static List<String> argv(Ready ready, boolean debug) {
        Node node = ready.runNode();
        Path shim = ready.runShim();
        List<String> argv = new ArrayList<>();
        argv.add(node.executable());
        argv.addAll(HttpScripts.launchFlags(node.guard(), HttpScripts.parseVersion(node.version()), shim, debug));
        argv.add(shim.toString());
        return argv;
    }

    /**
     * Whether a debugged script on this Node runs without its permission model: the model is on for every other run,
     * but this Node cannot open the inspector under it ({@link HttpScripts#allowsInspector}).
     */
    private static boolean debugsUnguarded(Ready ready) {
        Node node = ready.runNode();
        return node.guard() != HttpScripts.Guard.NONE
                && !HttpScripts.allowsInspector(HttpScripts.parseVersion(node.version()));
    }

    private boolean debugging() {
        return settings.debugger() != null;
    }

    /** What a debugged launch watches for, or null for a run that is not debugged. */
    private @Nullable DebugWatch watch(String label, Ready ready) {
        Debugger d = settings.debugger();
        return d == null
                ? null
                : new DebugWatch(
                        d,
                        label,
                        ready.runShim(),
                        debugsUnguarded(ready) ? ready.runNode().version() : null);
    }

    /** A request as a debug session names it: its {@code @name}, else its method and URL as written. */
    private static String label(HttpFile.Parsed request) {
        return request.name() != null && !request.name().isBlank()
                ? request.name()
                : request.method() + " " + request.url();
    }

    private static String nonce() {
        return "\u0001editora-" + HexFormat.of().formatHex(nonceBytes()) + ":";
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
        String written = h.path();
        if (written == null) { // not a file: its source is inline
            String name =
                    httpFile == null ? "handler.js" : httpFile.toAbsolutePath().toString();
            return new HttpScripts.Script(h.source(), name, h.line(), h.column());
        }
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
    record Node(
            String executable,
            @Nullable String version,
            HttpScripts.Guard guard,
            @Nullable String error) {}

    private Node node() {
        String command = nodeCommand();
        List<String> resolved = ProcessRunner.resolveExecutable(List.of(command));
        String exe = resolved.get(0);
        int[] version = version(exe);
        if (version == null) {
            return new Node(exe, null, HttpScripts.Guard.NONE, tr("http.warn.nodeMissing", command));
        }
        String text = "v" + version[0] + "." + version[1] + "." + version[2];
        return new Node(exe, text, HttpScripts.guardFor(version), null);
    }

    /** The Node command of this run (review E6): the HTTP field, else the shared Node.js. Off the FX thread. */
    private String nodeCommand() {
        if (HttpNodeChoice.explicit(settings.node())) {
            return settings.node();
        }
        if (com.editora.vfs.Vfs.isRemote(httpFile) || com.editora.vfs.Vfs.isRemote(settings.projectRoot())) {
            // The scripts still run here; a remote folder's .nvmrc names a Node of that machine.
            return HttpNodeChoice.resolveLocal(settings.node(), settings.nodeSetting(), null, null);
        }
        return HttpNodeChoice.resolveLocal(settings.node(), settings.nodeSetting(), httpFile, settings.projectRoot());
    }

    /** {@code exe}'s {@code node --version}, asked once per executable and modification time; null when it fails. */
    public static int @Nullable [] version(String exe) {
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

    /**
     * The shim, copied into {@code dir} under a name that changes with its content, by its <b>real</b> path: Node
     * resolves the script it runs, and under the permission model reading a link on the way ({@code /var} on macOS, a
     * linked home or config folder) is refused — the run fails before the shim starts.
     */
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
            return target.toRealPath();
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
        return target.toRealPath();
    }

    // --- launching ---------------------------------------------------------------------------------------------

    /** How one Node process went. {@code startError} is set when it could not be started at all. */
    record Launch(
            int exit,
            String stdout,
            String stderr,
            boolean timedOut,
            boolean cancelled,
            @Nullable String startError,
            boolean debuggerMissing) {

        Launch(
                int exit,
                String stdout,
                String stderr,
                boolean timedOut,
                boolean cancelled,
                @Nullable String startError) {
            this(exit, stdout, stderr, timedOut, cancelled, startError, false);
        }
    }

    /**
     * A debugged launch: hands the inspector port Node prints to the {@code debugger}, and notes when one attached
     * (Node prints that too).
     */
    static final class DebugWatch {
        private final Debugger debugger;
        private final String label;
        private final @Nullable Path shim;
        private final @Nullable String unguardedNode;
        private volatile long listeningAt;
        private volatile boolean attached;

        DebugWatch(Debugger debugger, String label, @Nullable Path shim, @Nullable String unguardedNode) {
            this.debugger = debugger;
            this.label = label;
            this.shim = shim;
            this.unguardedNode = unguardedNode;
        }

        void onLine(String line) {
            java.util.regex.Matcher m = INSPECTOR_LISTENING.matcher(line);
            if (listeningAt == 0 && m.find()) {
                listeningAt = System.nanoTime();
                try {
                    debugger.attach(Integer.parseInt(m.group(1)), label, shim, unguardedNode);
                } catch (RuntimeException failed) {
                    // no debugger attaches: the deadline stops the script
                }
            } else if (line.startsWith("Debugger attached")) {
                attached = true;
            }
        }

        /** Whether the debugger has had its time and is still not there. */
        boolean gaveUp(long startedAt) {
            if (attached) {
                return false;
            }
            long since = listeningAt == 0 ? startedAt : listeningAt;
            return System.nanoTime() - since > ATTACH_DEADLINE.toNanos();
        }
    }

    /**
     * Runs {@code argv} in {@code dir} with the minimal environment, {@code stdin} as its input; kills it (and anything
     * it started) after {@code timeout} or once {@code cancelled}.
     */
    static Launch launch(
            List<String> argv, @Nullable Path dir, String stdin, Duration timeout, BooleanSupplier cancelled) {
        return launch(argv, dir, stdin, timeout, cancelled, null);
    }

    /**
     * As {@link #launch(List, Path, String, Duration, BooleanSupplier)}; under a {@code watch} (a debugged script)
     * there is no timeout — a paused script waits for its user — but a debugger that does not attach in time stops it.
     */
    static Launch launch(
            List<String> argv,
            @Nullable Path dir,
            String stdin,
            Duration timeout,
            BooleanSupplier cancelled,
            @Nullable DebugWatch watch) {
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
        Thread errPump = watch == null
                ? pump(p.getErrorStream(), err, MAX_STDERR)
                : linePump(p.getErrorStream(), err, MAX_STDERR, watch::onLine);
        Thread feeder = Thread.ofVirtual().name("http-script-stdin").start(() -> {
            try (OutputStream in = p.getOutputStream()) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException closed) {
                // Node exited (or was killed) before reading everything: its result says why
            }
        });
        long startedAt = System.nanoTime();
        long deadline = startedAt + timeout.toNanos();
        boolean timedOut = false;
        boolean wasCancelled = false;
        boolean debuggerMissing = false;
        try {
            while (!p.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    wasCancelled = true;
                    break;
                }
                if (watch != null) {
                    if (watch.gaveUp(startedAt)) {
                        debuggerMissing = true;
                        break;
                    }
                } else if (System.nanoTime() > deadline) {
                    timedOut = true;
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            wasCancelled = true;
        }
        if (timedOut || wasCancelled || debuggerMissing) {
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
        return new Launch(exit, stdout, stderr, timedOut, wasCancelled, null, debuggerMissing);
    }

    /** {@link #pump}, also handing each whole line to {@code onLine} (Node's inspector messages). */
    private static Thread linePump(
            InputStream in, ByteArrayOutputStream into, int cap, java.util.function.Consumer<String> onLine) {
        return Thread.ofVirtual().name("http-script-err").start(() -> {
            try (java.io.BufferedReader r =
                    new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    onLine.accept(line);
                    byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                    synchronized (into) {
                        int room = cap - into.size();
                        if (room > 0) {
                            into.write(bytes, 0, Math.min(bytes.length, room));
                        }
                    }
                }
            } catch (IOException closed) {
                // the process went away
            }
        });
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
