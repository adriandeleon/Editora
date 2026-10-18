package com.editora.ui;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.LspDiagnostic;
import com.editora.lsp.LspManager;
import com.editora.process.ExecSpec;
import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;
import com.editora.process.PortForward;
import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import com.editora.process.RemoteProcess;
import com.editora.run.RunService;
import com.editora.search.RemoteFiles;
import com.editora.vfs.RemoteConnection;
import com.editora.vfs.RemoteFileSystems;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Remote development against a <b>real</b> SSH server: every feature that runs on a host, driven through
 * Editora's own connection, exec and forwarding code, on a server the tests do not control. Opt-in, and only
 * for a POSIX host that is this machine (some checks look at its processes with {@code ps}):
 *
 * <pre>
 * mvn test -Dtest=RealSshdProbeFxTest -Dgroups=fx \
 *     -Dremote.probe=user@127.0.0.1:22522 -Dremote.probe.key=/path/to/key -Dremote.probe.dir=/scratch/work
 * </pre>
 *
 * {@code remote.probe.dir} is a folder on the host the probe may fill and empty. Optional:
 * {@code remote.probe.sshdPid} (the listening sshd's pid: enables the dropped-connection check, which kills
 * that server's per-connection processes), {@code remote.probe.jdtls}, {@code remote.probe.javaDebug},
 * {@code remote.probe.debugpy} (a folder holding the debugpy package) and {@code remote.probe.jsDebug}.
 * Each step reports PASS / FAIL / SKIP; the test fails when any step failed.
 */
@Tag("fx")
@EnabledIfSystemProperty(named = "remote.probe", matches = ".+@.+:\\d+")
class RealSshdProbeFxTest {

    private final Map<String, String> report = new LinkedHashMap<>();
    private RemoteFileSystems engine;
    private RemoteConnection conn;
    private Path work; // the probe folder, as Editora reaches it over SFTP
    private String hostWork; // the same folder by its path on the host

    private interface Step {
        String run() throws Exception;
    }

    private void step(String name, Step body) {
        long t0 = System.nanoTime();
        String outcome;
        try {
            String note = body.run();
            outcome = (note != null && note.startsWith("SKIP") ? note : "PASS" + (note == null ? "" : " — " + note));
        } catch (Throwable e) {
            outcome = "FAIL — " + e;
        }
        report.put(name, outcome + " (" + (System.nanoTime() - t0) / 1_000_000 + " ms)");
        System.out.println("PROBE " + name + ": " + report.get(name));
    }

    /** Collects what a terminal prints, on a virtual thread, into the returned buffer. */
    private static StringBuffer pump(com.editora.process.TerminalChannel shell) {
        StringBuffer seen = new StringBuffer();
        Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = shell.output().read(buf)) > 0) {
                    seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException ended) {
                // closed
            }
        });
        return seen;
    }

    /** Waits for a terminal to print {@code needle}; the failure says what it printed instead. */
    private static void awaitOutput(StringBuffer seen, String needle) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && !seen.toString().contains(needle)) {
            Thread.sleep(50);
        }
        check(seen.toString().contains(needle), "\"" + needle + "\" in the terminal's output: [" + seen + "]");
    }

    private static void type(com.editora.process.TerminalChannel shell, String text) throws java.io.IOException {
        shell.input().write(text.getBytes(StandardCharsets.UTF_8));
        shell.input().flush();
    }

    /** Whether a {@code sleep <arg>} runs on this machine — the probe's host is this machine. */
    private static boolean sleeping(String arg) {
        try {
            // pgrep, not ProcessHandle: macOS does not give other processes' arguments to ProcessHandle.Info.
            return new ProcessBuilder("pgrep", "-f", "sleep " + arg).start().waitFor() == 0;
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void await(String what, long seconds, BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out: " + what);
            }
            Thread.sleep(50);
        }
    }

    private static void onFx(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        check(done.await(30, TimeUnit.SECONDS), "the FX thread ran the task");
    }

    /** Process ids in process group {@code pgid} on this machine (the host is this machine). */
    private static List<Long> group(long pgid) throws Exception {
        Process ps = new ProcessBuilder("ps", "-A", "-o", "pid=,pgid=").start(); // -A: all, on Linux and macOS
        List<Long> out = new ArrayList<>();
        for (String line : new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
            String[] f = line.strip().split("\\s+");
            if (f.length == 2 && f[1].equals(String.valueOf(pgid))) {
                out.add(Long.parseLong(f[0]));
            }
        }
        return out;
    }

    /**
     * The process groups of {@code sshdPid}'s per-connection processes ({@code sshd-session [priv]} and its
     * child). {@code ps --ppid} is GNU only, so every process is listed and the children picked here.
     */
    private static List<Long> sessionGroups(String sshdPid) throws Exception {
        Process ps = new ProcessBuilder("ps", "-A", "-o", "ppid=,pgid=").start();
        List<Long> out = new ArrayList<>();
        for (String line : new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
            String[] f = line.strip().split("\\s+");
            if (f.length == 2 && f[0].equals(sshdPid.strip())) {
                out.add(Long.parseLong(f[1]));
            }
        }
        return out;
    }

    private static void signalGroups(List<Long> groups, String signal) throws Exception {
        for (long g : groups) {
            new ProcessBuilder("kill", "-s", signal, "--", "-" + g).start().waitFor();
        }
    }

    private void connect(RemoteConnection connection) throws Exception {
        AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        engine.connect(connection, new char[0], r -> {
            result.set(r);
            done.countDown();
        });
        check(done.await(60, TimeUnit.SECONDS), "connected in time");
        check(result.get().ok(), "connected: " + result.get().error());
        work = result.get().root().resolve(hostWork.substring(1));
    }

    private ExecTarget target() {
        return ExecTargets.forPath(work);
    }

    private Process longLived(String script) throws Exception {
        return target().start(ExecSpec.of(List.of("sh", "-c", script), work, Map.of(), ExecSpec.Locale.PARSE_STABLE)
                .withLongLived(true));
    }

    @Test
    void everythingThatRunsOnAHostWorksAgainstARealServer() throws Exception {
        FxToolkit.registerPrimaryStage();
        String spec = System.getProperty("remote.probe");
        String user = spec.substring(0, spec.indexOf('@'));
        String host = spec.substring(spec.indexOf('@') + 1, spec.lastIndexOf(':'));
        int port = Integer.parseInt(spec.substring(spec.lastIndexOf(':') + 1));
        hostWork = System.getProperty("remote.probe.dir");
        Path knownHosts = Files.createTempFile("editora-probe-known_hosts", "");
        var ctor = RemoteFileSystems.class.getDeclaredConstructor(RemoteFileSystems.HostKeyPrompt.class, Path.class);
        ctor.setAccessible(true);
        engine = ctor.newInstance((RemoteFileSystems.HostKeyPrompt) (h, p, type, fp) -> true, knownHosts);
        // Keep-alives every 3 s instead of 30, so a silent connection is given up on within seconds here.
        var clientField = RemoteFileSystems.class.getDeclaredField("client");
        clientField.setAccessible(true);
        var keepAlive = RemoteFileSystems.class.getDeclaredMethod(
                "keepAlive", org.apache.sshd.client.SshClient.class, Duration.class);
        keepAlive.setAccessible(true);
        keepAlive.invoke(null, clientField.get(engine), Duration.ofSeconds(3));
        conn = new RemoteConnection(
                host,
                port,
                user,
                RemoteConnection.AuthMethod.KEY,
                System.getProperty("remote.probe.key"),
                "probe",
                "/");
        ExecTargets.setRemoteEnabled(() -> true);
        Map<String, String> tools = new ConcurrentHashMap<>();
        ExecTargets.setRemoteTools((id, tool) -> tools.get(tool));
        try {
            run(tools);
        } finally {
            ExecTargets.setRemoteTools(null);
            ExecTargets.setRemoteEnabled(null);
            engine.shutdown();
            Files.deleteIfExists(knownHosts);
            System.out.println("PROBE REPORT");
            report.forEach((k, v) -> System.out.println("  " + k + ": " + v));
        }
        List<String> failed = report.entrySet().stream()
                .filter(e -> e.getValue().startsWith("FAIL"))
                .map(Map.Entry::getKey)
                .toList();
        assertEquals(List.of(), failed);
    }

    private void run(Map<String, String> tools) throws Exception {
        step("connect with a key, mount", () -> {
            AtomicReference<RemoteFileSystems.Result> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            engine.connect(conn, new char[0], r -> {
                result.set(r);
                done.countDown();
            });
            check(done.await(60, TimeUnit.SECONDS), "connected in time");
            check(result.get().ok(), "connected: " + result.get().error());
            work = result.get().root().resolve(hostWork.substring(1)); // the host path, on the connection
            Files.createDirectories(work.resolve("my app"));
            check(target().isAvailable(), "the host can run commands");
            return "root " + result.get().root();
        });
        if (work == null) {
            return;
        }
        Path app = work.resolve("my app");

        step("login-shell PATH and locale reach commands", () -> {
            ProcessRunner.Result r =
                    ProcessRunner.run(app, Duration.ofSeconds(30), List.of("sh", "-c", "command -v node; pwd"));
            check(r.ok(), "ran: " + r.err());
            // node as the login shell finds it: a version manager's or Homebrew's, which a bare command's PATH lacks.
            ProcessRunner.Result login = ProcessRunner.run(
                    app,
                    Duration.ofSeconds(30),
                    List.of(
                            "sh",
                            "-c",
                            "exec \"${SHELL:-/bin/sh}\" -l -i -c 'command -v node' </dev/null 2>/dev/null"));
            String node = login.out().lines().reduce((a, b) -> b).orElse("").strip();
            check(!node.isEmpty(), "the login shell finds node: " + login.out());
            check(
                    r.out().lines().findFirst().orElse("").equals(node),
                    "the same node as the login shell (" + node + "): " + r.out());
            check(r.out().strip().endsWith("/my app"), "ran in the folder with a space: " + r.out());
            return r.out().strip().replace('\n', ' ');
        });

        step("what the login profile exports reaches commands", () -> {
            ProcessRunner.Result login = ProcessRunner.run(
                    app,
                    Duration.ofSeconds(30),
                    // From an environment like the one sshd gives a login: this command already carries what
                    // the profile exports, and a profile that prepends (Homebrew's INFOPATH) would add it twice.
                    List.of(
                            "sh",
                            "-c",
                            "exec env -i HOME=\"$HOME\" USER=\"$USER\" LOGNAME=\"$LOGNAME\" SHELL=\"$SHELL\""
                                    + " PATH=/usr/bin:/bin:/usr/sbin:/sbin \"${SHELL:-/bin/sh}\" -l -i -c env"
                                    + " </dev/null 2>/dev/null"));
            ProcessRunner.Result ours = ProcessRunner.run(app, Duration.ofSeconds(30), List.of("env"));
            Map<String, String> theirs = new LinkedHashMap<>();
            for (String line : login.out().split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).matches("[A-Z][A-Z0-9_]*")) {
                    theirs.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            List<String> missing = new ArrayList<>();
            java.util.Set<String> session = java.util.Set.of(
                    "PWD",
                    "OLDPWD",
                    "SHLVL",
                    "PS1",
                    "PS2",
                    "PS4",
                    "TERM",
                    "COLUMNS",
                    "LINES",
                    "MAIL",
                    "DISPLAY",
                    "LS_COLORS",
                    "LSCOLORS", // what RemoteEnv leaves to the terminal, as LS_COLORS
                    "PS3",
                    "PROMPT_COMMAND",
                    "TERMCAP",
                    "COLORTERM",
                    "MAILCHECK",
                    "GPG_TTY",
                    "MOTD_SHOWN",
                    "LANG",
                    "LANGUAGE",
                    "PATH",
                    "_");
            for (var e : theirs.entrySet()) {
                String name = e.getKey();
                boolean sessionOwn = session.contains(name)
                        || java.util.stream.Stream.of("SSH_", "BASH_", "LC_", "HIST", "XDG_SESSION")
                                .anyMatch(name::startsWith);
                if (!sessionOwn && !ours.out().contains(name + "=" + e.getValue())) {
                    missing.add(name);
                }
            }
            check(missing.isEmpty(), "a command lacks what the login shell has: " + missing);
            return theirs.containsKey("JAVA_HOME")
                    ? "JAVA_HOME=" + theirs.get("JAVA_HOME")
                    : theirs.size() + " variables";
        });

        step("arguments survive quoting", () -> {
            String tricky = "a 'quoted' \"double\" $HOME `x` ä; rm -rf /tmp/nothing";
            ProcessRunner.Result r = ProcessRunner.run(app, Duration.ofSeconds(30), List.of("printf", "%s", tricky));
            check(tricky.equals(r.out()), "printf echoed exactly: " + r.out());
            return null;
        });

        step("git on the host (status through GitService)", () -> {
            Files.writeString(app.resolve("README.md"), "hello\n");
            ProcessRunner.Result init = ProcessRunner.run(
                    app,
                    Duration.ofSeconds(30),
                    List.of(
                            "sh",
                            "-c",
                            "git init -q && git add . && git -c user.name=p -c user.email=p@x commit -qm init"));
            check(init.ok(), "git init: " + init.err());
            Files.writeString(app.resolve("README.md"), "hello\nchanged\n");
            com.editora.git.GitService git = new com.editora.git.GitService();
            check(git.gitAvailable(app), "git found on the host");
            CompletableFuture<com.editora.git.GitService.RepoState> state = new CompletableFuture<>();
            onFx(() -> git.status(app.resolve("README.md"), state::complete));
            var s = state.get(60, TimeUnit.SECONDS);
            check(s != null && s.status().repo(), "a repository");
            check(
                    s.status().files().stream().anyMatch(f -> f.toString().contains("README.md")),
                    "README.md changed: " + s.status().files());
            return "branch " + s.status().branch();
        });

        step("clone onto the host (the host's git, from the host)", () -> {
            // The repository above, cloned by the host into a new folder next to it: a URL only the host can
            // reach (its own path) and a destination named as the host names it.
            Path destination = work.resolve("cloned app");
            com.editora.git.GitService git = new com.editora.git.GitService();
            CompletableFuture<ProcessRunner.Result> cloned = new CompletableFuture<>();
            onFx(() -> git.clone(hostWork + "/my app", destination, cloned::complete));
            ProcessRunner.Result r = cloned.get(120, TimeUnit.SECONDS);
            check(r.ok(), "cloned: " + r.message());
            check(Files.readString(destination.resolve("README.md")).equals("hello\n"), "the committed README");
            return null;
        });

        step("fetch automatically on the host (a new commit reaches the clone's origin/main)", () -> {
            Path clone = work.resolve("cloned app");
            if (!Files.isDirectory(clone)) {
                return "SKIP — no clone from the step before";
            }
            ProcessRunner.Result commit = ProcessRunner.run(
                    app,
                    Duration.ofSeconds(30),
                    List.of(
                            "sh",
                            "-c",
                            "git -c user.name=p -c user.email=p@x commit -q --allow-empty -m second"
                                    + " && git rev-parse HEAD"));
            check(commit.ok(), "a second commit: " + commit.err());
            com.editora.git.GitService git = new com.editora.git.GitService();
            CompletableFuture<ProcessRunner.Result> fetched = new CompletableFuture<>();
            onFx(() -> git.autoFetch(clone, fetched::complete));
            ProcessRunner.Result r = fetched.get(120, TimeUnit.SECONDS);
            check(r != null && r.ok(), "fetched: " + (r == null ? "not run" : r.message()));
            ProcessRunner.Result origin =
                    ProcessRunner.run(clone, Duration.ofSeconds(30), List.of("git", "rev-parse", "origin/HEAD"));
            check(
                    origin.out().strip().equals(commit.out().strip()),
                    "origin moved to " + commit.out().strip() + ": " + origin.out() + origin.err());
            return null;
        });

        step("the host says where a project's repositories are (the Project tree's Git menus)", () -> {
            ProcessRunner.Result nested = ProcessRunner.run(
                    app,
                    Duration.ofSeconds(30),
                    List.of("sh", "-c", "mkdir -p libs/inner && git -C libs/inner init -q"));
            check(nested.ok(), "a nested repository: " + nested.err());
            GitPathScope.learnRemote(app, true);
            await("the host's answer", 30, () -> GitPathScope.knowsRemote(app));
            Path innerFile = app.resolve("libs/inner/x.txt");
            check(
                    app.resolve("libs/inner").equals(GitPathScope.nearestRepository(innerFile)),
                    "the nested one: " + GitPathScope.nearestRepository(innerFile));
            check(GitPathScope.of(innerFile, app) == GitPathScope.OTHER, "another repository than the project's");
            check(GitPathScope.of(app.resolve("README.md"), app) == GitPathScope.ACTIVE, "the project's own");
            return null;
        });

        step("ripgrep lists and searches on the host", () -> {
            Files.writeString(app.resolve("needle.txt"), "a needle here\n");
            if (!RemoteFiles.hasRipgrep(app)) {
                return "SKIP — no rg on the host";
            }
            RemoteFiles.Listing listing = RemoteFiles.list(app, true, 100);
            check(listing != null && listing.paths().contains("needle.txt"), "listed: " + listing);
            return listing.paths().size() + " files";
        });

        step("run with stdin, output and exit code", () -> {
            RunService run = new RunService();
            List<String> out = new CopyOnWriteArrayList<>();
            CompletableFuture<Integer> exit = new CompletableFuture<>();
            run.runInDir(
                    app,
                    List.of(
                            "python3",
                            "-c",
                            "import sys\nline=sys.stdin.readline().strip()\nprint(line[::-1])\nsys.exit(3)"),
                    new RunService.Listener() {
                        @Override
                        public void onStart(String commandLine) {}

                        @Override
                        public void onOutput(String line, boolean stderr) {
                            out.add(line);
                        }

                        @Override
                        public void onExit(int code) {
                            exit.complete(code);
                        }

                        @Override
                        public void onError(String message) {
                            exit.completeExceptionally(new AssertionError(message));
                        }
                    });
            run.sendInput("éditora");
            int code = exit.get(60, TimeUnit.SECONDS);
            check(out.contains("arotidé"), "reversed, in UTF-8: " + out);
            check(code == 3, "exit code: " + code);
            return null;
        });

        step("stopping a tree kills the whole process group", () -> {
            Process p = longLived("sleep 300 & sleep 301; wait");
            long pgid = ((RemoteProcess) p).remotePid();
            await("both sleeps up", 10, () -> {
                try {
                    return group(pgid).size() >= 3;
                } catch (Exception e) {
                    return false;
                }
            });
            ProcessRegistry.killTree(p);
            await("the group gone", 15, () -> {
                try {
                    return group(pgid).isEmpty();
                } catch (Exception e) {
                    return false;
                }
            });
            return "pgid " + pgid;
        });

        step("channel budget: a sixth long-lived command is refused, short ones still run", () -> {
            List<Process> held = new ArrayList<>();
            try {
                for (int i = 0; i < 5; i++) {
                    held.add(longLived("sleep 120"));
                }
                String refusal = null;
                try {
                    held.add(longLived("sleep 120"));
                } catch (java.io.IOException e) {
                    refusal = e.getMessage();
                }
                check(refusal != null, "the sixth was refused");
                List<CompletableFuture<ProcessRunner.Result>> shorts = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    shorts.add(CompletableFuture.supplyAsync(() ->
                            ProcessRunner.run(app, Duration.ofSeconds(60), List.of("sh", "-c", "sleep 1; echo ok"))));
                }
                for (var s : shorts) {
                    ProcessRunner.Result r = s.get(90, TimeUnit.SECONDS);
                    check(
                            r.out().strip().equals("ok"),
                            "a short command ran beside five long ones: exit " + r.exit() + ", out [" + r.out()
                                    + "], err [" + r.err() + "]");
                }
                return refusal;
            } finally {
                held.forEach(ProcessRegistry::killTree);
            }
        });

        step("a port on the host is reached through a tunnel", () -> {
            Process server = longLived(
                    "exec python3 -c '"
                            + "import socket,sys\n"
                            + "s=socket.socket(); s.bind((\"127.0.0.1\",0)); s.listen(1)\n"
                            + "print(s.getsockname()[1], flush=True)\n"
                            + "c,_=s.accept(); c.recv(1024); c.sendall(b\"HTTP/1.0 200 OK\\r\\n\\r\\nhello from the host\"); c.close()'");
            try {
                String line =
                        new java.io.BufferedReader(new java.io.InputStreamReader(server.getInputStream())).readLine();
                int hostPort = Integer.parseInt(line.strip());
                try (PortForward forward = target().forwardLocal("127.0.0.1", hostPort)) {
                    var http = (java.net.HttpURLConnection) URI.create("http://127.0.0.1:" + forward.localPort() + "/")
                            .toURL()
                            .openConnection();
                    String body;
                    try (InputStream in = http.getInputStream()) {
                        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    check(body.contains("hello from the host"), "body: " + body);
                    return "host port " + hostPort + " → local " + forward.localPort();
                }
            } finally {
                ProcessRegistry.killTree(server);
            }
        });

        step("the outside-change watch sees a file made on the host", () -> {
            List<ProjectPanel.FsChange> seen = new CopyOnWriteArrayList<>();
            RemoteWatcher watcher = RemoteWatcher.start(app, seen::addAll);
            check(watcher != null, "started");
            try {
                Thread.sleep(1500);
                ProcessRunner.run(app, Duration.ofSeconds(30), List.of("sh", "-c", "echo x > made-outside.txt"));
                await(
                        "the change reported",
                        20,
                        () -> seen.stream().anyMatch(c -> c.toString().contains("made-outside.txt")));
                return seen.size() + " change(s)";
            } finally {
                watcher.stop();
            }
        });

        step("host clock: a just-written file is not taken as older than now", () -> {
            Path fresh = Files.writeString(app.resolve("fresh.txt"), "x");
            long now = System.currentTimeMillis();
            long mtime = Files.getLastModifiedTime(fresh).toMillis();
            check(!com.editora.lsp.WorkspaceEditMapper.unchangedSince(fresh, mtime, now), "fresh is not unchanged");
            check(com.editora.lsp.WorkspaceEditMapper.unchangedSince(fresh, mtime, now + 10_000), "10 s later it is");
            return "latest local = " + (target().latestLocalTime(mtime) - mtime) + " ms from host time";
        });

        step("pyright on the host: diagnostics for a remote file", () -> {
            Path py = Files.writeString(app.resolve("bad.py"), "x: int = 'text'\n");
            Map<Path, List<LspDiagnostic>> diags = new ConcurrentHashMap<>();
            LspManager lsp = new LspManager(diags::put, (t, m) -> {});
            try {
                lsp.configure(true, Map.of());
                onFx(() -> lsp.openDocument(py, app, "python", "x: int = 'text'\n"));
                // pyright answers diagnostic pulls; the editor asks after typing settles, so ask the same way.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                while (diags.values().stream().allMatch(List::isEmpty) && System.nanoTime() < deadline) {
                    onFx(() -> lsp.pullDiagnostics(py));
                    Thread.sleep(1000);
                }
                check(diags.values().stream().anyMatch(l -> !l.isEmpty()), "diagnostics arrived");
                return diags.values().stream()
                        .flatMap(List::stream)
                        .findFirst()
                        .map(LspDiagnostic::message)
                        .orElse("");
            } finally {
                lsp.shutdownAll();
            }
        });

        String jdtls = System.getProperty("remote.probe.jdtls");
        String javaDebug = System.getProperty("remote.probe.javaDebug");
        step("jdtls on the host, and a Java debug session stopping at a breakpoint", () -> {
            if (jdtls == null || javaDebug == null) {
                return "SKIP — remote.probe.jdtls / remote.probe.javaDebug not given";
            }
            tools.put("lsp.java", jdtls);
            Path src = Files.createDirectories(app.resolve("src/main/java/demo"));
            Files.writeString(
                    app.resolve("pom.xml"),
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                            + "<groupId>demo</groupId><artifactId>app</artifactId><version>1</version>"
                            + "<properties><maven.compiler.release>17</maven.compiler.release></properties></project>\n");
            String text =
                    "package demo;\npublic class Main {\n  public static void main(String[] a) {\n    int x = 1;\n    System.out.println(\"sum \" + (x + 1));\n  }\n}\n";
            Path main = Files.writeString(src.resolve("Main.java"), text);
            ProcessRunner.Result javac = ProcessRunner.run(
                    app,
                    Duration.ofSeconds(120),
                    List.of("javac", "-g", "--release", "17", "-d", "target/classes", "src/main/java/demo/Main.java"));
            check(javac.ok(), "javac on the host: " + javac.err());
            List<String> seen = new CopyOnWriteArrayList<>();
            LspManager lsp = new LspManager((f, d) -> {}, (t, m) -> {
                if ("ServiceReady".equals(t)) {
                    seen.add("READY");
                }
            });
            lsp.configure(true, Map.of());
            lsp.setDebugBundles(List.of(javaDebug));
            DapManager dap = new DapManager(lsp);
            try {
                onFx(() -> lsp.openDocument(main, app, "java", text));
                await("jdtls ready", 240, () -> seen.contains("READY"));
                Thread.sleep(3000);
                debug(dap, main, "java", app, seen, 3, () -> dap.configure(true, javaDebug));
                // And Run (not Debug): jdtls resolves the class path on the host; the program runs there.
                CompletableFuture<DapManager.ResolvedLaunch> launch = new CompletableFuture<>();
                onFx(() -> dap.resolveMainClasses(main, options -> {
                    if (options.isEmpty()) {
                        launch.completeExceptionally(new AssertionError("no main class"));
                    } else {
                        dap.resolveLaunch(main, options.get(0), launch::complete);
                    }
                }));
                DapManager.ResolvedLaunch info = launch.get(120, TimeUnit.SECONDS);
                check(info.ok(), "resolved: " + info.error());
                String out = runOnHost(
                        app,
                        com.editora.run.JavaRunCommand.build(
                                RunCoordinator.hostJava("", info.javaExec()),
                                info.modulePaths(),
                                info.classPaths(),
                                "demo.Main",
                                List.of(),
                                List.of()));
                check(out.contains("sum 2"), "ran on the host: " + out);
                seen.add("ran with " + info.javaExec());
                // And a Java file in no project: compiled on the host for the debugger, then debugged.
                Path loose = Files.createDirectories(work.resolve("loose"));
                String looseText =
                        "public class Loose {\n  public static void main(String[] a) {\n    int x = 1;\n    System.out.println(\"sum \" + (x + 1));\n  }\n}\n";
                Path looseFile = Files.writeString(loose.resolve("Loose.java"), looseText);
                onFx(() -> lsp.openDocument(looseFile, loose, "java", looseText));
                Thread.sleep(3000);
                debug(dap, looseFile, "java", null, seen, 2, () -> dap.configure(true, javaDebug));
                return String.join(" | ", seen);
            } finally {
                onFx(dap::shutdown);
                lsp.shutdownAll();
                tools.remove("lsp.java");
            }
        });

        step("a Java project's main class runs on the host with Maven's classpath (no jdtls)", () -> {
            // Beside the other test project, not inside it: a pom.xml above would make this a reactor module.
            Path proj = Files.createDirectories(work.resolve("mvnapp/src/main/java/demo"));
            Path root = work.resolve("mvnapp");
            Files.writeString(
                    root.resolve("pom.xml"),
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                            + "<groupId>demo</groupId><artifactId>mvnapp</artifactId><version>1</version>"
                            + "<properties><maven.compiler.release>17</maven.compiler.release></properties></project>\n");
            Files.writeString(
                    proj.resolve("Hello.java"),
                    "package demo;\npublic class Hello {\n  public static void main(String[] a) {\n    System.out.println(\"hello from maven \" + a.length);\n  }\n}\n");
            List<String> cp = com.editora.maven.MavenClasspathResolver.resolve(root, "");
            check(cp != null && !cp.isEmpty(), "Maven gave a classpath on the host: " + cp);
            check(
                    cp.get(0).startsWith("/") && cp.get(0).endsWith("/target/classes"),
                    "named as the host names it: " + cp);
            String out = runOnHost(
                    root,
                    com.editora.run.JavaRunCommand.build("java", List.of(), cp, "demo.Hello", List.of(), List.of("x")));
            check(out.contains("hello from maven 1"), "ran: " + out);
            try (var left = Files.list(root.resolve("target"))) {
                check(
                        left.noneMatch(f -> f.getFileName().toString().startsWith("editora-cp-")),
                        "the classpath file was removed");
            }
            return cp.size() + " classpath entries";
        });

        String debugpy = System.getProperty("remote.probe.debugpy");
        step("debugpy on the host stops at a breakpoint", () -> {
            if (debugpy == null) {
                return "SKIP — remote.probe.debugpy not given";
            }
            tools.put("dap.python", "env PYTHONPATH=" + debugpy + " python3");
            Path py = Files.writeString(app.resolve("dbg.py"), "a = 1\nb = a + 1\nprint('sum', a + b)\n");
            DapManager dap = new DapManager(null);
            try {
                debug(
                        dap,
                        py,
                        "python",
                        null,
                        new CopyOnWriteArrayList<>(),
                        1,
                        () -> dap.configure(true, "", true, "", true, ""));
                return null;
            } finally {
                onFx(dap::shutdown);
                tools.remove("dap.python");
            }
        });

        String jsDebug = System.getProperty("remote.probe.jsDebug");
        step("vscode-js-debug on the host stops at a breakpoint, through a tunnel", () -> {
            if (jsDebug == null) {
                return "SKIP — remote.probe.jsDebug not given";
            }
            tools.put("dap.js", jsDebug);
            Path js = Files.writeString(
                    app.resolve("dbg.js"), "let a = 1;\nlet b = a + 1;\nconsole.log('sum', a + b);\n");
            DapManager dap = new DapManager(null);
            try {
                debug(
                        dap,
                        js,
                        "javascript",
                        null,
                        new CopyOnWriteArrayList<>(),
                        1,
                        () -> dap.configure(true, "", true, "", true, ""));
                return null;
            } finally {
                onFx(dap::shutdown);
                tools.remove("dap.js");
            }
        });

        step("a terminal on the host: a real tty, its size, and a resize", () -> {
            com.editora.process.TerminalChannel shell =
                    target().startTerminal(new com.editora.process.TerminalSpec(app, 100, 30));
            try {
                StringBuffer seen = pump(shell);
                type(shell, "stty size; tty; echo $TERM $TERM_PROGRAM; pwd -P\n");
                // Each line awaited on its own: they arrive one by one, and dropbear can be a beat slower.
                awaitOutput(seen, "30 100");
                awaitOutput(seen, "/dev/"); // a real tty
                awaitOutput(seen, "xterm-256color Editora"); // TERM and TERM_PROGRAM
                shell.resize(120, 40);
                type(shell, "stty size\n");
                await(
                        "stty size 40 120 after the resize",
                        20,
                        () -> seen.toString().contains("40 120"));
                return "30x100 then 40x120";
            } finally {
                shell.close();
            }
        });

        step("a program in a terminal on the host: a tty, its variables and its exit code", () -> {
            com.editora.process.TerminalChannel shell = target().startTerminal(com.editora.process.TerminalSpec.program(
                    app,
                    80,
                    24,
                    List.of("/bin/sh", "-c", "[ -t 0 ] && echo is-a-tty; echo \"X=$X pwd=$(pwd -P)\"; exit 7"),
                    Map.of("X", "from editora")));
            try {
                StringBuffer seen = pump(shell);
                awaitOutput(seen, "is-a-tty");
                awaitOutput(seen, "X=from editora");
                check(!seen.toString().contains("EDITORA "), "the pid line is not shown");
                int code = shell.waitFor();
                check(code == 7, "the program's exit code reaches the channel: " + code);
                check(shell.pid() > 0, "its pid was marked");
                return "tty, variables and exit code 7";
            } finally {
                shell.close();
            }
        });

        step("shell integration on the host: folder and prompt marks, and the close check", () -> {
            String integration = com.editora.terminal.ShellIntegration.installOn(target().cache());
            com.editora.process.TerminalChannel shell =
                    target().startTerminal(new com.editora.process.TerminalSpec(app, 80, 24, integration));
            try {
                StringBuffer seen = pump(shell);
                // Split so the echo of what was typed does not match: only the output reads "login=/…".
                type(shell, "echo log\"\"in=$SHELL\n");
                await("the account's shell", 20, () -> seen.toString().contains("login=/"));
                java.util.regex.Matcher login =
                        java.util.regex.Pattern.compile("login=\\S*/(\\S+)").matcher(seen.toString());
                String name = login.find() ? login.group(1) : "?";
                await("the shell's pid line", 20, () -> shell.pid() > 0);
                check(!seen.toString().contains("EDITORA "), "the pid line is not shown");
                if (!name.equals("bash") && !name.equals("zsh")) {
                    return "SKIP — the account's shell is " + name + ", which has no integration";
                }
                awaitOutput(seen, "\u001b]133;A");
                awaitOutput(seen, "\u001b]7;file://");
                type(shell, "cd /; false\n");
                awaitOutput(seen, "\u001b]133;D;1");
                await(
                        "an OSC 7 for / after the cd",
                        20,
                        () -> java.util.regex.Pattern.compile("\u001b]7;file://[^/\u0007]*/\u0007")
                                .matcher(seen.toString())
                                .find());
                type(shell, "sleep 6011\n");
                await("the terminal's sleep up", 10, () -> sleeping("6011"));
                List<String> busy = TerminalCoordinator.remotePrograms(app, shell.pid());
                check(busy.contains("sleep"), "the close check sees the shell's sleep: " + busy);
                return name + ": OSC 7 and 133 A/D, pid " + shell.pid() + ", running " + busy;
            } finally {
                shell.close();
            }
        });

        String sshdPid = System.getProperty("remote.probe.sshdPid");
        step("a frozen connection is given up on, and its folder reconnects", () -> {
            if (sshdPid == null) {
                return "SKIP — remote.probe.sshdPid not given";
            }
            List<Boolean> heard = new CopyOnWriteArrayList<>();
            engine.addConnectionListener((authority, up) -> heard.add(up));
            List<Long> groups = sessionGroups(sshdPid);
            check(!groups.isEmpty(), "the server has this connection's processes");
            // A network that drops packets: the server stops answering, and nobody closes anything.
            signalGroups(groups, "STOP");
            try {
                await("the window told the connection is down", 60, () -> heard.contains(false));
            } finally {
                signalGroups(groups, "KILL");
            }
            check(!target().isAvailable(), "nothing can be started on a dead connection");
            connect(conn);
            check(target().isAvailable(), "reconnected");
            return "noticed after the keep-alives went unanswered";
        });

        step("a dropped connection: the host's programs die, the window hears of it", () -> {
            if (sshdPid == null) {
                return "SKIP — remote.probe.sshdPid not given";
            }
            List<Boolean> heard = new CopyOnWriteArrayList<>();
            engine.addConnectionListener((authority, up) -> heard.add(up));
            Process p = longLived("sleep 600");
            long pgid = ((RemoteProcess) p).remotePid();
            // A terminal running a foreground program: the hang-up must end it, and the terminal must say why.
            com.editora.process.TerminalChannel shell =
                    target().startTerminal(new com.editora.process.TerminalSpec(app, 80, 24));
            type(shell, "exec sleep 6007\n");
            await("the terminal's sleep up", 10, () -> sleeping("6007"));
            await("the sleep up", 10, () -> {
                try {
                    return !group(pgid).isEmpty();
                } catch (Exception e) {
                    return false;
                }
            });
            // What a lost connection leaves: the server's per-connection processes gone, the program orphaned.
            signalGroups(sessionGroups(sshdPid), "KILL");
            await("the window told the connection is down", 30, () -> heard.contains(false));
            await("the host's program gone (watchdog)", 20, () -> {
                try {
                    return group(pgid).isEmpty();
                } catch (Exception e) {
                    return false;
                }
            });
            check(!target().isAvailable(), "nothing can be started on a dead connection");
            await("the terminal's channel closed", 20, () -> !shell.isOpen());
            check(shell.lostConnection(), "the terminal reports the connection lost, not an exit");
            await("the terminal's program gone (the server hung up its tty)", 20, () -> !sleeping("6007"));
            return null;
        });

        String ed25519Default = System.getProperty("remote.probe.ed25519Key");
        step("default keys: an ed25519 key beside a usable one does not stop the login", () -> {
            if (ed25519Default == null) {
                return "SKIP — remote.probe.ed25519Key not given";
            }
            // ~/.ssh as many people have it: the new default ed25519 key, and an older one the server accepts.
            Path home = Files.createTempDirectory("editora-probe-home");
            Path ssh = Files.createDirectories(home.resolve(".ssh"));
            Files.copy(Path.of(ed25519Default), ssh.resolve("id_ed25519"));
            Files.copy(Path.of(System.getProperty("remote.probe.key")), ssh.resolve("id_ecdsa"));
            // An engine whose ~/.ssh is that folder (the default keys are read from its SshEnvironment).
            RemoteFileSystems main = engine;
            main.disconnect(conn.id());
            engine = new RemoteFileSystems((h, p, type, fp) -> true, null, com.editora.vfs.SshEnvironment.at(ssh));
            try {
                connect(new RemoteConnection(
                        conn.host(),
                        conn.port(),
                        conn.user(),
                        RemoteConnection.AuthMethod.DEFAULT_KEYS,
                        "",
                        "probe",
                        "/"));
                return null;
            } finally {
                engine.shutdown();
                engine = main;
                connect(conn);
            }
        });

        String ed25519 = System.getProperty("remote.probe.ed25519Key");
        step("log in with an ed25519 key (what ssh-keygen makes by default)", () -> {
            if (ed25519 == null) {
                return "SKIP — remote.probe.ed25519Key not given";
            }
            engine.disconnect(conn.id());
            connect(new RemoteConnection(
                    conn.host(), conn.port(), conn.user(), RemoteConnection.AuthMethod.KEY, ed25519, "probe", "/"));
            return null;
        });
    }

    /** Runs {@code argv} in {@code dir} on the host through Run's service; its whole output. */
    private static String runOnHost(Path dir, List<String> argv) throws Exception {
        RunService run = new RunService();
        StringBuilder out = new StringBuilder();
        CompletableFuture<Integer> exit = new CompletableFuture<>();
        run.runInDir(dir, argv, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {}

            @Override
            public void onOutput(String line, boolean stderr) {
                synchronized (out) {
                    out.append(line).append('\n');
                }
            }

            @Override
            public void onExit(int code) {
                exit.complete(code);
            }

            @Override
            public void onError(String message) {
                exit.completeExceptionally(new AssertionError(message));
            }
        });
        exit.get(120, TimeUnit.SECONDS);
        synchronized (out) {
            return out.toString();
        }
    }

    /**
     * Starts a debug session for {@code file} with a breakpoint on 0-based {@code line}, waits for the stop,
     * checks the frame is the remote file, continues, and waits for the program's output.
     */
    private void debug(
            DapManager dap,
            Path file,
            String language,
            Path projectRoot,
            List<String> seen,
            int line,
            Runnable configure)
            throws Exception {
        List<List<DapModels.StackFrameInfo>> stops = new CopyOnWriteArrayList<>();
        List<String> output = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        onFx(() -> {
            configure.run();
            dap.setProgramConsole(true);
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {}

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                    stops.add(frames);
                }

                @Override
                public void onOutput(String text, String category) {
                    output.add(text);
                }

                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            });
            dap.setBreakpointsSupplier(() -> List.of(
                    new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(line, null, null)))));
            if ("python".equals(language) || "javascript".equals(language)) {
                dap.detectPython(ok -> {}); // availability for local files only; a remote one asks its host
            }
            dap.startLaunch(file, language, (options, chosen) -> chosen.accept(options.get(0)), "", projectRoot);
        });
        await("stopped at the breakpoint (errors " + errors + ")", 120, () -> !stops.isEmpty() || !errors.isEmpty());
        check(errors.isEmpty(), "no errors: " + errors);
        DapModels.StackFrameInfo top = stops.get(0).get(0);
        check(file.equals(top.file()), "the frame is the remote file: " + top.file());
        check(top.line() == line, "on the breakpoint's line: " + top.line());
        onFx(dap::resume);
        await(
                "the program's output after Continue",
                60,
                () -> String.join("", output).contains("sum"));
        seen.add("stopped at " + top.file().getFileName() + ":" + (line + 1));
    }
}
