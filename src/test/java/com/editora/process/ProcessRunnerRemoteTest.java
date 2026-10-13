package com.editora.process;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ProcessRunner} sends a command to the target that owns its working directory. */
class ProcessRunnerRemoteTest {

    /** Records what it is asked to start and answers with whatever {@code reply} makes of it. */
    private static final class RecordingTarget implements ExecTarget {
        final List<ExecSpec> started = new ArrayList<>();
        private final FileSystem owned;
        private final Function<ExecSpec, Process> reply;

        RecordingTarget(FileSystem owned, Function<ExecSpec, Process> reply) {
            this.owned = owned;
            this.reply = reply;
        }

        @Override
        public String id() {
            return "user@fake:22";
        }

        @Override
        public boolean isLocal() {
            return false;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Process start(ExecSpec spec) {
            started.add(spec);
            return reply.apply(spec);
        }

        @Override
        public String toTargetPath(Path path) {
            return path.toString();
        }

        @Override
        public Path toPath(String targetPath) {
            return owned.getPath(targetPath);
        }
    }

    private interface Body {
        void run(Path remoteDir, RecordingTarget target) throws Exception;
    }

    private static void withTarget(Path dir, Function<ExecSpec, Process> reply, Body body) throws Exception {
        try (FileSystem zip = NonDefaultPaths.open(dir)) {
            RecordingTarget target = new RecordingTarget(zip, reply);
            ExecTargets.Resolver resolver = path -> path.getFileSystem() == zip ? target : null;
            ExecTargets.register(resolver);
            try {
                body.run(zip.getPath("/srv/app"), target);
            } finally {
                ExecTargets.unregister(resolver);
            }
        }
    }

    @Test
    void aCommandInARemoteDirectoryRunsOnItsTarget(@TempDir Path dir) throws Exception {
        withTarget(dir, spec -> FakeRemoteProcess.finished(3, "## main\n", "warning: ñ\n"), (remote, target) -> {
            Map<String, String> env = new HashMap<>();
            env.put("GIT_DIR", null);

            ProcessRunner.Result r =
                    ProcessRunner.run(remote, Duration.ofSeconds(5), List.of("git", "status", "-sb"), env);

            assertEquals(3, r.exit());
            assertEquals("## main\n", r.out());
            assertEquals("warning: ñ\n", r.err());
            ExecSpec spec = target.started.getFirst();
            assertEquals(List.of("git", "status", "-sb"), spec.argv(), "never rewritten against this machine's PATH");
            assertSame(remote, spec.workingDir());
            assertEquals(ExecSpec.Locale.PARSE_STABLE, spec.locale());
            assertTrue(spec.env().containsKey("GIT_DIR"));
            assertNull(spec.env().get("GIT_DIR"));
            assertFalse(spec.longLived());
        });
    }

    @Test
    void theUserLocaleVariantsAskForTheUsersLocale(@TempDir Path dir) throws Exception {
        withTarget(dir, spec -> FakeRemoteProcess.finished(0, "", ""), (remote, target) -> {
            ProcessRunner.runInUserLocale(remote, Duration.ofSeconds(5), List.of("mvn", "-v"));
            ProcessRunner.runScrubbed(remote, Duration.ofSeconds(5), List.of("dot", "-V"), Map.of());

            assertEquals(ExecSpec.Locale.USER, target.started.get(0).locale());
            assertFalse(target.started.get(0).scrubSecrets());
            assertTrue(target.started.get(1).scrubSecrets());
        });
    }

    @Test
    void stdinIsWrittenToTheRemoteChild(@TempDir Path dir) throws Exception {
        FakeRemoteProcess child = FakeRemoteProcess.finished(0, "", "");
        withTarget(dir, spec -> child, (remote, target) -> {
            ProcessRunner.run(remote, Duration.ofSeconds(5), List.of("git", "apply"), Map.of(), "patch\n");

            for (int i = 0; i < 200 && child.stdin.size() == 0; i++) {
                Thread.sleep(10); // the stdin writer is its own thread
            }
            assertEquals("patch\n", child.stdin.toString());
        });
    }

    @Test
    void aTimeoutStopsTheRemoteTree(@TempDir Path dir) throws Exception {
        FakeRemoteProcess stuck = new FakeRemoteProcess(true);
        withTarget(dir, spec -> stuck, (remote, target) -> {
            ProcessRunner.Result r = ProcessRunner.run(remote, Duration.ofMillis(100), List.of("git", "fetch"));

            assertTrue(r.timedOut());
            assertEquals(List.of(false), stuck.signals);
        });
    }

    @Test
    void aTargetThatCannotStartIsAFailedResultNotAnException(@TempDir Path dir) throws Exception {
        try (FileSystem zip = NonDefaultPaths.open(dir)) {
            ProcessRunner.Result r =
                    ProcessRunner.run(zip.getPath("/srv/app"), Duration.ofSeconds(5), List.of("git", "status"));

            assertEquals(-1, r.exit());
            assertEquals("no connection to run git on", r.err());
        }
    }
}
