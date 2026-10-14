package com.editora.process;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecTargetsTest {

    @org.junit.jupiter.api.BeforeEach
    void allowRemoteExecution() {
        ExecTargets.setRemoteEnabled(() -> true);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreTheDefault() {
        ExecTargets.setRemoteEnabled(null);
    }

    @Test
    void noPathAndALocalPathRunHere(@TempDir Path dir) {
        assertSame(ExecTargets.local(), ExecTargets.forPath(null));
        assertSame(ExecTargets.local(), ExecTargets.forPath(dir));
        assertTrue(ExecTargets.local().isLocal());
        assertTrue(ExecTargets.local().isAvailable());
    }

    @Test
    void theLocalTargetNamesPathsAsTheyAre(@TempDir Path dir) {
        ExecTarget local = ExecTargets.local();
        Path file = dir.resolve("a b.txt");
        assertEquals(file.toString(), local.toTargetPath(file));
        assertEquals(file, local.toPath(file.toString()));
    }

    @Test
    void aToolsRelativePathIsResolvedAgainstWhereItRan(@TempDir Path dir) {
        ExecTarget local = ExecTargets.local();
        assertEquals(dir.resolve("src/A.java"), local.resolve(dir, "src/A.java"));
        Path absolute = dir.resolve("B.java");
        assertEquals(absolute, local.resolve(dir.resolve("elsewhere"), absolute.toString()));
        assertEquals(Path.of("C.java"), local.resolve(null, "C.java"));
    }

    @Test
    void aRemotePathNobodyOwnsCannotRunAnything(@TempDir Path dir) throws IOException {
        try (FileSystem zip = NonDefaultPaths.open(dir)) {
            Path remote = zip.getPath("/srv/app");
            ExecTarget target = ExecTargets.forPath(remote);

            assertFalse(target.isLocal());
            assertFalse(target.isAvailable());
            ExecSpec spec = ExecSpec.of(List.of("git", "status"), remote, Map.of(), ExecSpec.Locale.PARSE_STABLE);
            assertThrows(IOException.class, () -> target.start(spec));
            // It still names paths on that filesystem, never on this machine's.
            assertEquals("/srv/app", target.toTargetPath(remote));
            assertSame(zip, target.toPath("/srv/app/x").getFileSystem());
        }
    }

    @Test
    void theOwningResolverAnswersAndIsForgottenWhenUnregistered(@TempDir Path dir) throws IOException {
        try (FileSystem zip = NonDefaultPaths.open(dir)) {
            Path remote = zip.getPath("/srv/app");
            ExecTarget owned = new StubTarget();
            ExecTargets.Resolver other = path -> null;
            ExecTargets.Resolver owner = path -> path.getFileSystem() == zip ? owned : null;
            ExecTargets.register(other);
            ExecTargets.register(owner);
            try {
                assertSame(owned, ExecTargets.forPath(remote));
                assertSame(
                        ExecTargets.local(), ExecTargets.forPath(dir), "a resolver is never asked about local paths");
            } finally {
                ExecTargets.unregister(owner);
                ExecTargets.unregister(other);
            }
            assertFalse(ExecTargets.forPath(remote).isAvailable());
        }
    }

    @Test
    void whileTheSettingIsOffNoRemotePathCanRunAnything(@TempDir Path dir) throws IOException {
        try (FileSystem zip = NonDefaultPaths.open(dir)) {
            Path remote = zip.getPath("/srv/app");
            boolean[] allowed = {false};
            ExecTargets.Resolver owner = path -> new StubTarget();
            ExecTargets.register(owner);
            ExecTargets.setRemoteEnabled(() -> allowed[0]);
            try {
                ExecTarget off = ExecTargets.forPath(remote);
                assertFalse(off.isAvailable(), "the owning engine is not even asked");
                ExecSpec spec = ExecSpec.of(List.of("git"), remote, Map.of(), ExecSpec.Locale.USER);
                IOException refused = assertThrows(IOException.class, () -> off.start(spec));
                assertEquals("running commands on remote hosts is turned off", refused.getMessage());
                assertSame(ExecTargets.local(), ExecTargets.forPath(dir), "local commands are untouched");

                allowed[0] = true; // read on every lookup: no restart, no re-registration
                assertTrue(ExecTargets.forPath(remote).isAvailable());
            } finally {
                ExecTargets.unregister(owner);
            }
        }
    }

    @Test
    void aHostTheUserHasNotAllowedCannotRunAnythingAndHasNoTools(@TempDir Path dir) throws IOException {
        try (FileSystem zip = NonDefaultPaths.open(dir)) {
            Path remote = zip.getPath("/srv/app");
            ExecTargets.Resolver owner = path -> new StubTarget();
            ExecTargets.register(owner);
            ExecTargets.setRemoteHostAllowed(id -> !id.equals("user@stub:22"));
            ExecTargets.setRemoteTools(
                    (id, tool) -> id.equals("user@stub:22") && tool.equals("git") ? "/opt/git/bin/git" : null);
            try {
                ExecTarget refused = ExecTargets.forPath(remote);
                assertFalse(refused.isAvailable());
                ExecSpec spec = ExecSpec.of(List.of("git"), remote, Map.of(), ExecSpec.Locale.USER);
                IOException e = assertThrows(IOException.class, () -> refused.start(spec));
                assertEquals("running commands on user@stub:22 has not been allowed", e.getMessage());

                ExecTargets.setRemoteHostAllowed(id -> true);
                assertTrue(ExecTargets.forPath(remote).isAvailable());
                assertEquals("/opt/git/bin/git", ExecTargets.remoteTool(remote, "git"));
                assertEquals(null, ExecTargets.remoteTool(remote, "rg"), "nothing configured: the host's PATH");
                assertEquals(null, ExecTargets.remoteTool(dir, "git"), "local tools have their own settings");
            } finally {
                ExecTargets.unregister(owner);
                ExecTargets.setRemoteHostAllowed(null);
                ExecTargets.setRemoteTools(null);
            }
        }
    }

    @Test
    void theDefaultIsOff(@TempDir Path dir) throws IOException {
        ExecTargets.setRemoteEnabled(null);
        try (FileSystem zip = NonDefaultPaths.open(dir)) {
            ExecTargets.Resolver owner = path -> new StubTarget();
            ExecTargets.register(owner);
            try {
                assertFalse(ExecTargets.forPath(zip.getPath("/srv")).isAvailable());
            } finally {
                ExecTargets.unregister(owner);
            }
        }
    }

    @Test
    void aSpecNeedsACommandAndALocale() {
        assertThrows(
                IllegalArgumentException.class, () -> ExecSpec.of(List.of(), null, Map.of(), ExecSpec.Locale.USER));
        assertThrows(IllegalArgumentException.class, () -> ExecSpec.of(List.of("git"), null, Map.of(), null));
        assertEquals(
                Map.of(),
                ExecSpec.of(List.of("git"), null, null, ExecSpec.Locale.USER).env());
    }

    private static final class StubTarget implements ExecTarget {
        @Override
        public String id() {
            return "user@stub:22";
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
            throw new UnsupportedOperationException();
        }

        @Override
        public PortForward forwardLocal(String host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toTargetPath(Path path) {
            return path.toString();
        }

        @Override
        public Path toPath(String targetPath) {
            throw new UnsupportedOperationException();
        }
    }
}
