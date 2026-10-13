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
        public String toTargetPath(Path path) {
            return path.toString();
        }

        @Override
        public Path toPath(String targetPath) {
            throw new UnsupportedOperationException();
        }
    }
}
