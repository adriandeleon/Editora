package com.editora.wsl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTargets;
import com.editora.process.PortForward;
import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A folder inside a <b>real</b> WSL distribution, reached from Windows as {@code \\wsl.localhost\…}: its
 * commands run inside the distribution. Opt-in, on Windows with WSL: {@code -Dwsl.probe=<distribution>}. The
 * distribution needs {@code git} and {@code python3}.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "wsl.probe", matches = ".+")
class WslProbeTest {

    private String distro;
    private Path folder;
    private String inside;
    private final WslTargets targets = new WslTargets(List.of("wsl.exe"));

    /** {@code script} run inside {@code distro}, carried as {@link WslExecTarget} carries its own commands. */
    private static ProcessRunner.Result wsl(String distro, String script) {
        List<String> argv = new java.util.ArrayList<>(List.of("wsl.exe", "-d", distro, "--exec"));
        argv.addAll(WslExecTarget.windowsSafe(List.of("sh", "-c", script)));
        return ProcessRunner.run(null, Duration.ofSeconds(120), argv);
    }

    @BeforeEach
    void setUp() throws Exception {
        distro = System.getProperty("wsl.probe");
        ProcessRunner.Result made = wsl(
                distro, "mkdir -p \"$HOME/editora probe\" && cd \"$HOME/editora probe\" && echo hola > a.txt && pwd");
        assertTrue(made.ok(), made.message());
        inside = made.out().strip();
        folder = null;
        for (String host : List.of("\\\\wsl.localhost\\", "\\\\wsl$\\")) {
            Path candidate = Path.of(host + distro + inside.replace('/', '\\'));
            if (Files.isDirectory(candidate)) {
                folder = candidate;
                break;
            }
        }
        assertTrue(folder != null, "Windows reaches the folder through \\\\wsl.localhost or \\\\wsl$: " + inside);
        ExecTargets.setRemoteEnabled(() -> true);
        ExecTargets.registerMapped(targets);
    }

    @AfterEach
    void tearDown() {
        ExecTargets.unregisterMapped(targets);
        ExecTargets.setRemoteEnabled(null);
        wsl(distro, "rm -rf \"$HOME/editora probe\"");
    }

    @Test
    void aCommandForTheFolderRunsInsideTheDistribution() {
        assertFalse(ExecTargets.runsHere(folder.resolve("a.txt")));

        ProcessRunner.Result r = ProcessRunner.run(
                folder,
                Duration.ofSeconds(120),
                List.of("sh", "-c", "pwd; cat a.txt; uname -s; test -n \"$WSL_DISTRO_NAME\" && echo wsl"));

        assertTrue(r.ok(), r.message());
        assertEquals(List.of(inside, "hola", "Linux", "wsl"), r.out().lines().toList());
        assertEquals(inside + "/a.txt", ExecTargets.forPath(folder).toTargetPath(folder.resolve("a.txt")));
    }

    @Test
    void gitForTheFolderIsTheDistributions() {
        ProcessRunner.Result init = ProcessRunner.run(
                folder,
                Duration.ofSeconds(120),
                List.of("sh", "-c", "git init -q && git add a.txt && git status --porcelain && command -v git"));

        assertTrue(init.ok(), init.message());
        assertTrue(init.out().contains("A  a.txt"), init.out());
        assertTrue(init.out().strip().endsWith("/git"), "a Linux git, not git.exe: " + init.out());
    }

    @Test
    void stoppingAProgramStopsWhatItStartedInside() throws Exception {
        Process p = ExecTargets.forPath(folder)
                .start(ExecSpec.of(
                                List.of("sh", "-c", "sleep 300 & sleep 301; wait"),
                                folder,
                                Map.of(),
                                ExecSpec.Locale.PARSE_STABLE)
                        .withLongLived(true));
        Thread.sleep(2000);
        assertEquals("2", wsl(distro, "pgrep -f '^sleep 30[01]$' | wc -l").out().strip(), "both sleeps running");

        ProcessRegistry.killTree(p);

        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the program did not end");
        String left = "";
        for (int i = 0; i < 50; i++) {
            left = wsl(distro, "pgrep -f '^sleep 30[01]$' | wc -l").out().strip();
            if (left.equals("0")) {
                break;
            }
            Thread.sleep(200);
        }
        assertEquals("0", left, "left running in the distribution");
    }

    @Test
    void aPortInTheDistributionIsReachedFromHere() throws Exception {
        Process server = ExecTargets.forPath(folder)
                .start(ExecSpec.of(
                                List.of(
                                        "python3",
                                        "-c",
                                        "import socket\n"
                                                + "s=socket.socket(); s.bind(('127.0.0.1',0)); s.listen(1)\n"
                                                + "print(s.getsockname()[1], flush=True)\n"
                                                + "c,_=s.accept(); n=c.makefile().readline().strip()\n"
                                                + "c.sendall(('hello from WSL, '+n+'\\n').encode()); c.close()"),
                                folder,
                                Map.of(),
                                ExecSpec.Locale.PARSE_STABLE)
                        .withLongLived(true));
        try {
            int port = Integer.parseInt(new BufferedReader(new InputStreamReader(server.getInputStream()))
                    .readLine()
                    .strip());
            try (PortForward forward = ExecTargets.forPath(folder).forwardLocal("127.0.0.1", port);
                    Socket socket = new Socket(InetAddress.getLoopbackAddress(), forward.localPort())) {
                socket.getOutputStream().write("Ana\n".getBytes());
                socket.getOutputStream().flush();
                assertEquals(
                        "hello from WSL, Ana",
                        new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine());
            }
        } finally {
            ProcessRegistry.killTree(server);
        }
    }
}
