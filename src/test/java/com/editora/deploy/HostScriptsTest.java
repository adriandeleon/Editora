package com.editora.deploy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The host scripts, run by this machine's own {@code sh}, {@code find} and {@code tar}. */
@DisabledOnOs(OS.WINDOWS)
class HostScriptsTest {

    @TempDir
    Path dir;

    private static String run(String script, byte[] stdin, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("sh", "-c", script, HostScripts.NAME));
        argv.addAll(List.of(args));
        Process p = new ProcessBuilder(argv).redirectErrorStream(false).start();
        try (var in = p.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin);
            }
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), err);
        return out;
    }

    @Test
    void aMissingFolderIsSaidSo() throws Exception {
        HostScripts.Listing l = HostScripts.parseListing(
                run(HostScripts.listing(List.of()), null, dir.resolve("nope").toString()));
        assertNotNull(l);
        assertEquals(HostScripts.Listing.State.MISSING, l.state());
        assertTrue(l.tar(), "this machine has tar");
    }

    @Test
    void theListingHasSizesSecondsAndPathsAndSkipsPrunedFolders() throws Exception {
        Files.createDirectories(dir.resolve("src/sub"));
        Files.writeString(dir.resolve("src/sub/My File.txt"), "hello");
        Files.setLastModifiedTime(dir.resolve("src/sub/My File.txt"), FileTime.fromMillis(1_700_000_000_000L));
        Files.createDirectories(dir.resolve("node_modules/x"));
        Files.writeString(dir.resolve("node_modules/x/i.js"), "x");
        HostScripts.Listing l = HostScripts.parseListing(
                run(HostScripts.listing(List.of("node_modules", "it's")), null, dir.toString()));
        assertEquals(HostScripts.Listing.State.PRESENT, l.state());
        assertEquals(new HostScripts.RemoteFile(5, 1_700_000_000L), l.files().get("src/sub/My File.txt"));
        assertFalse(l.files().containsKey("node_modules/x/i.js"), "a pruned folder is not entered");
    }

    @Test
    void parsingSkipsNoiseBeforeTheMarkAndReadsEveryStatFlavour() {
        HostScripts.Listing l = HostScripts.parseListing("Welcome!\nEDITORA-DEPLOY 1 present tar=1 gzip=0\n"
                + "5 1700000000.5000000000 a b.txt\n" // GNU find
                + "6 1700000001 ./c/d.txt\n" // stat
                + "garbage\n");
        assertTrue(l.tar());
        assertFalse(l.gzip());
        assertEquals(new HostScripts.RemoteFile(5, 1_700_000_000L), l.files().get("a b.txt"));
        assertEquals(new HostScripts.RemoteFile(6, 1_700_000_001L), l.files().get("c/d.txt"));
        assertNull(HostScripts.parseListing("bash: sh: not found"));
        String decomposed = java.text.Normalizer.normalize("Ñandú.txt", java.text.Normalizer.Form.NFD);
        HostScripts.Listing mac =
                HostScripts.parseListing("EDITORA-DEPLOY 1 present tar=1 gzip=1\n2 1700000000 ./" + decomposed + "\n");
        assertEquals(
                new HostScripts.RemoteFile(2, 1_700_000_000L),
                mac.files().get("Ñandú.txt"),
                "a name macOS's tar stored decomposed is the composed name that was sent");
    }

    @Test
    void anArchiveUnpacksWithItsModesTimesAndLongNamesThenListsAsSent() throws Exception {
        String longName = "deep/" + "x".repeat(120) + "/Ñandú.txt";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes);
                TarWriter tar = new TarWriter(gz)) {
            tar.addFile("run.sh", 10, 1_700_000_000L, true, in("#!/bin/sh\n"));
            tar.addFile(longName, 2, 1_700_000_100L, false, in("hi"));
            tar.addFile("short.txt", 4, 1_700_000_200L, false, in("ab")); // shrank while being sent
        }
        Path root = dir.resolve("app/nested");
        run(HostScripts.unpack(), bytes.toByteArray(), root.toString(), "1");

        assertEquals("#!/bin/sh\n", Files.readString(root.resolve("run.sh")));
        assertTrue(Files.getPosixFilePermissions(root.resolve("run.sh")).contains(PosixFilePermission.OWNER_EXECUTE));
        assertEquals("hi", Files.readString(root.resolve(longName)));
        assertEquals(
                1_700_000_100_000L,
                Files.getLastModifiedTime(root.resolve(longName)).toMillis());
        assertEquals(4, Files.size(root.resolve("short.txt")), "padded to the size in the header");

        HostScripts.Listing l = HostScripts.parseListing(run(HostScripts.listing(List.of()), null, root.toString()));
        assertEquals(new HostScripts.RemoteFile(2, 1_700_000_100L), l.files().get(longName));
    }

    @Test
    void removeDeletesTheNamedFilesAndOnlyEmptiedFolders() throws Exception {
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("a/b/gone.txt"), "x");
        Files.writeString(dir.resolve("a/stays.log"), "x");
        Files.writeString(dir.resolve("it's here.txt"), "x");
        run(
                HostScripts.remove(),
                "a/b/gone.txt\nit's here.txt\nnever-there.txt\n::dirs\na/b\na\n".getBytes(StandardCharsets.UTF_8),
                dir.toString());
        assertFalse(Files.exists(dir.resolve("a/b")));
        assertFalse(Files.exists(dir.resolve("it's here.txt")));
        assertTrue(Files.exists(dir.resolve("a/stays.log")), "a folder with something else in it stays");
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
