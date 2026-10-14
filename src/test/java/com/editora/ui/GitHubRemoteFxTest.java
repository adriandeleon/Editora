package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.github.GitHubService;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repository on a remote host is served by that host's own {@code gh}: asked there, signed in there or not,
 * and remembered per host, so a window moving between a local and a remote repository does not mix the two.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class GitHubRemoteFxTest {

    private static final String FAKE_GH = """
            #!/bin/sh
            D="$(dirname "$0")"
            printf '%s\\n' "$*" >> "$D/gh.log"
            if [ "$1" = "--version" ]; then echo "gh version 2.96.0 (test stand-in)"; exit 0; fi
            if [ "$1 $2" = "auth status" ]; then cat "$D/auth.json"; exit 0; fi
            exit 0
            """;
    private static final String SIGNED_IN = "{\"hosts\":{\"github.com\":[{\"state\":\"success\",\"active\":true,"
            + "\"host\":\"github.com\",\"login\":\"octocat\"}]}}";
    private static final String SIGNED_OUT = "{\"hosts\":{}}";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private GitHubService service;
    private Path localGh;
    private Path remoteGh;
    private Path remoteDir;

    private static Path fakeGh(Path dir, String auth) throws Exception {
        Files.createDirectories(dir);
        Path gh = Files.writeString(dir.resolve("gh"), FAKE_GH);
        assertTrue(gh.toFile().setExecutable(true));
        Files.writeString(dir.resolve("auth.json"), auth);
        return gh;
    }

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        localGh = fakeGh(base.resolve("local-gh"), SIGNED_IN);
        // The host's gh, signed out there: what the user configured for that host.
        remoteGh = fakeGh(sftp.serverPath("bin"), SIGNED_OUT);
        ExecTargets.setRemoteTools((id, tool) -> tool.equals("gh") ? remoteGh.toString() : null);
        remoteDir = sftp.remotePath("app");
        Files.createDirectories(remoteDir);
        service = new GitHubService();
        service.setCommand(localGh.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        service.shutdown();
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
        ExecTargets.setRemoteTools(null);
    }

    private static <T> T await(Consumer<Consumer<T>> request) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        request.accept(value -> {
            result.set(value);
            done.countDown();
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "gh never answered");
        return result.get();
    }

    private static String log(Path gh) throws Exception {
        Path log = gh.resolveSibling("gh.log");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    @Test
    void eachMachineIsAskedAboutItsOwnGhAndRememberedApart() throws Exception {
        service.setLocation(base);
        GitHubService.Availability here = await(service::detect);
        assertTrue(here.authenticated(), "signed in on this machine");

        service.setLocation(remoteDir);
        assertNull(service.availability(), "the host's gh has not been asked yet: no answer borrowed from here");
        GitHubService.Availability there = await(service::detect);

        assertTrue(there.found());
        assertFalse(there.authenticated(), "signed out on the host");
        assertTrue(log(remoteGh).contains("auth status"), "the host's gh was asked: " + log(remoteGh));

        service.setLocation(base);
        assertSame(here, service.availability(), "back here, this machine's answer, without asking again");
        service.setLocation(remoteDir);
        assertSame(there, service.availability());
    }

    @Test
    void aCommandForARemoteRepositoryRunsTheHostsGh() throws Exception {
        String before = log(localGh);

        ProcessRunner.Result r = await(cb -> service.browse(remoteDir, "README.md:1", "main", cb));

        assertTrue(r.ok(), r.message());
        assertTrue(log(remoteGh).contains("browse --no-browser"), "not run on the host: " + log(remoteGh));
        assertEquals(before, log(localGh), "this machine's gh was not involved");
    }

    @Test
    void changingTheLocalCommandForgetsOnlyThisMachinesAnswer() throws Exception {
        service.setLocation(remoteDir);
        GitHubService.Availability there = await(service::detect);
        assertNotNull(there);

        assertTrue(service.setCommand(localGh.toString() + "-elsewhere"));

        assertSame(there, service.availability(), "the host's gh is not the one that changed");
    }
}
