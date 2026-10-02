package com.editora.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Unit tests for the {@code .http} file-reference containment rule. */
class HttpPathsTest {

    private static final Path BASE = Path.of("/home/u/repo/api");

    @Test
    void resolvesAPlainRelativeReference() {
        assertEquals(BASE.resolve("body.json"), HttpPaths.contained(BASE, "body.json"));
        assertEquals(BASE.resolve("body.json"), HttpPaths.contained(BASE, "./body.json"));
    }

    @Test
    void resolvesADeeperReferenceInsideTheFolder() {
        assertEquals(BASE.resolve("fixtures/big.json"), HttpPaths.contained(BASE, "fixtures/big.json"));
    }

    @Test
    void allowsAClimbThatLandsBackInside() {
        assertEquals(BASE.resolve("body.json"), HttpPaths.contained(BASE, "fixtures/../body.json"));
    }

    @Test
    void refusesAnAbsolutePath() {
        assertNull(HttpPaths.contained(BASE, "/etc/passwd"));
    }

    @Test
    void refusesAParentClimb() {
        assertNull(HttpPaths.contained(BASE, "../../.ssh/id_rsa"));
        assertNull(HttpPaths.contained(BASE, "../secret.txt"));
    }

    @Test
    void refusesASiblingFolderWithACommonPrefix() {
        // "/home/u/repo/api-secrets" must not pass merely because its string starts with the base's
        assertNull(HttpPaths.contained(BASE, "../api-secrets/keys.json"));
    }

    @Test
    void refusesBlankAndNullInput() {
        assertNull(HttpPaths.contained(BASE, null));
        assertNull(HttpPaths.contained(BASE, ""));
        assertNull(HttpPaths.contained(BASE, "   "));
        assertNull(HttpPaths.contained(null, "body.json"));
    }

    @Test
    void treatsTheBaseFolderItselfAsContained() {
        assertEquals(BASE, HttpPaths.contained(BASE, "."));
    }

    // --- real-path containment: a symlink inside the folder must not carry a read or a write out of it ---

    /** Creates {@code link -> target}, skipping the test where the platform refuses (Windows without the right). */
    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
        }
    }

    @Test
    void refusesAReadThroughASymlinkPointingOutsideTheFolder(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("repo/api"));
        Path secret = Files.writeString(tmp.resolve("id_rsa"), "PRIVATE KEY");
        symlink(base.resolve("payload.json"), secret);
        assertNull(HttpPaths.contained(base, "./payload.json"), "payload.json -> ../../id_rsa is exfiltration");
    }

    @Test
    void refusesAReadThroughASymlinkedFolder(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("repo/api"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("keys.json"), "{}");
        symlink(base.resolve("fixtures"), outside);
        assertNull(HttpPaths.contained(base, "fixtures/keys.json"));
        assertNull(HttpPaths.contained(base, "fixtures/not-there-yet.json"), "the existing parent decides");
    }

    @Test
    void allowsAReadThroughASymlinkThatStaysInsideTheFolder(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("repo/api"));
        Files.writeString(base.resolve("real.json"), "{}");
        symlink(base.resolve("alias.json"), base.resolve("real.json"));
        assertEquals(base.resolve("alias.json"), HttpPaths.contained(base, "alias.json"));
    }

    @Test
    void readsOnlyRegularFiles(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("api"));
        Files.createDirectories(base.resolve("fixtures"));
        Files.writeString(base.resolve("body.json"), "{}");
        assertEquals(base.resolve("body.json"), HttpPaths.contained(base, "body.json"));
        assertNull(HttpPaths.contained(base, "fixtures"), "a directory is not a body file");
        assertEquals(base.resolve("missing.json"), HttpPaths.contained(base, "missing.json"), "missing → caller 404s");
    }

    @Test
    void refusesADanglingSymlink(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("api"));
        symlink(base.resolve("out"), tmp.resolve("does-not-exist-yet"));
        assertNull(HttpPaths.contained(base, "out"));
        assertNull(HttpPaths.containedForWrite(base, "out"), "a >>! through it would create the outside file");
    }

    @Test
    void refusesAWriteWhoseFinalComponentIsASymlink(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("repo/api"));
        Path bashrc = Files.writeString(tmp.resolve("bashrc"), "# rc");
        symlink(base.resolve("out"), bashrc);
        assertNull(HttpPaths.containedForWrite(base, "./out"), "out -> ~/.bashrc must not be overwritten");
        // even a link that stays inside the folder is refused for writes: the final component must be a real file
        Files.writeString(base.resolve("real.txt"), "x");
        symlink(base.resolve("alias.txt"), base.resolve("real.txt"));
        assertNull(HttpPaths.containedForWrite(base, "alias.txt"));
    }

    @Test
    void refusesAWriteThroughASymlinkedFolder(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("repo/api"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        symlink(base.resolve("logs"), outside);
        assertNull(HttpPaths.containedForWrite(base, "logs/response.json"));
        assertNull(HttpPaths.containedForWrite(base, "logs/new/deeper/response.json"));
    }

    @Test
    void allowsAnOrdinaryWriteTarget(@TempDir Path tmp) throws IOException {
        Path base = Files.createDirectories(tmp.resolve("api"));
        Files.writeString(base.resolve("existing.json"), "{}");
        assertEquals(base.resolve("existing.json"), HttpPaths.containedForWrite(base, "existing.json"));
        assertEquals(base.resolve("new/out.json"), HttpPaths.containedForWrite(base, "new/out.json"));
        assertNull(HttpPaths.containedForWrite(base, "../out.json"));
        assertNull(HttpPaths.containedForWrite(base, "/etc/cron.d/x"));
        Files.createDirectories(base.resolve("dir"));
        assertNull(HttpPaths.containedForWrite(base, "dir"), "an existing directory is not a write target");
    }
}
