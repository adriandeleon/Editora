package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durability of the config stores. The failure this guards against is quiet and total: Jackson's
 * {@code writeValue(File, …)} truncates the target and then streams into it, so a crash / full disk / kill
 * mid-save leaves a <b>torn</b> file — and the read path treats an unparseable file as "absent" and loads an
 * <em>empty</em> store, which the next save writes straight back over the remains. One bad write and every
 * bookmark, note, and breakpoint (or the whole project index) is gone, with nothing to recover from.
 */
class ConfigDurabilityTest {

    @TempDir
    Path dir;

    @Test
    void anAtomicWriteNeverLeavesAPartialFile() throws IOException {
        Path file = dir.resolve("bookmarks.json");
        ObjectMapper mapper = new ObjectMapper();
        Files.writeString(file, "{\"schemaVersion\":1,\"old\":true}");

        ConfigWriter.writeAtomic(file, mapper, java.util.Map.of("schemaVersion", 1, "fresh", true));

        assertTrue(Files.readString(file).contains("fresh"), "the new content landed");
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")), "the temp file is moved, not left");
    }

    @Test
    void aTornFileIsKeptRatherThanSilentlyReplacedByAnEmptyStore() throws IOException {
        // Exactly what a crash mid-`writeValue` leaves: a valid prefix, cut off. It holds most of the user's
        // bookmarks — and the very next save is about to overwrite it.
        Path file = dir.resolve("bookmarks.json");
        String torn = "{\"schemaVersion\":1,\"byProject\":{\"\":{\"/src/Main.java\":[{\"line\":41,\"note\":\"here";
        Files.writeString(file, torn);

        BookmarkStore loaded =
                ConfigMigrations.readVersioned(file, new ObjectMapper(), new BookmarkStore(), ConfigSchema.BOOKMARKS);

        assertNotNull(loaded, "we still start up rather than crash");
        assertTrue(loaded.getByProject().isEmpty(), "an unparseable store loads empty (that part is by design)");

        // ...but the bytes must survive somewhere, or the user's bookmarks are gone for good.
        Path kept = file.resolveSibling("bookmarks.json.corrupt.bak");
        assertTrue(Files.exists(kept), "the unparseable file is preserved");
        assertEquals(torn, Files.readString(kept), "byte-for-byte, so it can be recovered by hand");
    }

    @Test
    void anEmptyFileIsNotBackedUp() throws IOException {
        // Nothing to preserve — don't litter the config dir.
        Path file = dir.resolve("notes.json");
        Files.writeString(file, "");
        ConfigMigrations.readVersioned(file, new ObjectMapper(), new NoteStore(), ConfigSchema.NOTES);
        assertFalse(Files.exists(file.resolveSibling("notes.json.corrupt.bak")));
    }

    @Test
    void aSecondDowngradeDoesNotDestroyTheNewerConfigWithoutABackup() throws IOException {
        // A file NEWER than this build is backed up and defaults are loaded, so an older Editora can't clobber
        // a newer config. But the backup used to be SKIPPED when one already existed — so on the second
        // downgrade the newer file was left in place for the next save to overwrite, and the only copy on disk
        // was the stale one from the first downgrade. Alternating beta/stable ate the user's settings.
        Path file = dir.resolve("settings.toml");
        Files.writeString(file, "first newer version");
        ConfigMigrations.backup(file, 99);
        assertEquals("first newer version", Files.readString(dir.resolve("settings.toml.v99.bak")));

        Files.writeString(file, "second, re-customized newer version");
        ConfigMigrations.backup(file, 99);

        assertFalse(Files.exists(file), "the newer file was moved out of the way, not left to be overwritten");
        assertEquals(
                "first newer version",
                Files.readString(dir.resolve("settings.toml.v99.bak")),
                "the first backup is intact");
        assertEquals(
                "second, re-customized newer version",
                Files.readString(dir.resolve("settings.toml.v99.bak.2")),
                "and the second is kept alongside it, not dropped on the floor");
    }

    @Test
    void aQueuedWriteForADeletedFileIsDropped() throws Exception {
        // Deleting a project deletes its session file — but a coalesced write may still be in the queue, and
        // it would land afterwards and re-create it. Project ids are derived from the folder path, so a
        // resurrected file gets picked straight back up if the folder is ever re-added.
        Path file = dir.resolve("projects").resolve("ghost.json");
        ConfigWriter writer = new ConfigWriter();
        writer.enqueue(file, "{\"resurrected\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.cancel(file);
        writer.flush();
        assertFalse(Files.exists(file), "a cancelled write must not land");

        // Sanity: without the cancel it does land (so the test proves the cancel, not a broken writer).
        writer.enqueue(file, "{\"written\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writer.flush();
        assertTrue(Files.exists(file));
        writer.shutdown();
    }

    @Test
    void everyStoreIsWrittenAtomically() throws IOException {
        // A regression net: no config store may go back to mapper.writeValue(File, …), which truncates first.
        List<String> offenders = new java.util.ArrayList<>();
        Path src = Path.of("src/main/java/com/editora/config");
        try (var files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String body = Files.readString(f);
                for (String line : body.split("\n")) {
                    String code = line.strip();
                    if (code.startsWith("*") || code.startsWith("//")) {
                        continue; // javadoc/comments may name the forbidden call
                    }
                    if (code.contains(".writeValue(") && code.contains(".toFile()")) {
                        offenders.add(f.getFileName() + ": " + line.strip());
                    }
                }
            }
        }
        assertTrue(
                offenders.isEmpty(),
                "these truncate the file before writing — use ConfigWriter.writeAtomic instead: " + offenders);
    }
}
