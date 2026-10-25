package com.editora.install;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InstallReceiptTest {

    @TempDir
    Path dir;

    @Test
    void aWrittenReceiptReadsBack() throws IOException {
        Instant at = Instant.parse("2026-10-25T12:00:00Z");
        new InstallReceipt("jdtls", "1.63.0.202610191322", at).write(dir);

        assertEquals(new InstallReceipt("jdtls", "1.63.0.202610191322", at), InstallReceipt.read(dir));
        assertEquals(InstallReceipt.FILE_NAME, ".editora-install");
    }

    @Test
    void aFolderWithoutOneHasNone() {
        assertNull(InstallReceipt.read(dir));
        assertNull(InstallReceipt.read(dir.resolve("missing")));
        assertNull(InstallReceipt.read(null));
    }

    @Test
    void aReceiptWithoutIdOrVersionIsNone() {
        assertNull(InstallReceipt.parse("id=jdtls\n"));
        assertNull(InstallReceipt.parse("version=1.0\n"));
        assertNull(InstallReceipt.parse(""));
        assertNull(InstallReceipt.parse(null));
    }

    @Test
    void anUnreadableDateStillGivesTheVersion() {
        InstallReceipt r = InstallReceipt.parse("id=gls\nversion=0.4.8\ninstalled=yesterday\n");

        assertEquals("0.4.8", r.version());
        assertNull(r.installedAt());
    }

    @Test
    void aFileTooBigToBeAReceiptIsIgnored() throws IOException {
        Files.writeString(dir.resolve(InstallReceipt.FILE_NAME), "id=x\nversion=1\n" + "#".repeat(8192));

        assertNull(InstallReceipt.read(dir));
    }
}
