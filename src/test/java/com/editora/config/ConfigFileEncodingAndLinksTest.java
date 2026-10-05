package com.editora.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Two ordinary ways a config file differs from the one Editora wrote, neither of which may cost the user
 * their settings: it is a symlink into a dotfiles repository, or another editor saved it with a byte-order
 * mark or in a legacy encoding.
 */
class ConfigFileEncodingAndLinksTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static String settingsJson(String... properties) {
        return "{\"schemaVersion\": " + Settings.SCHEMA_VERSION + ", " + String.join(", ", properties) + "}";
    }

    private static byte[] withBom(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(body, 0, bytes, BOM.length, body.length);
        return bytes;
    }

    /** Links {@code link} to {@code target}, skipping the test where the platform refuses (Windows without the privilege). */
    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links are not available here: " + e);
        }
    }

    // --- a symlinked config file (stow / chezmoi) ---------------------------------------------------

    @Test
    void savingWritesThroughASymlinkedSettingsFile(@TempDir Path dir, @TempDir Path dotfiles) throws IOException {
        Path real = dotfiles.resolve("settings.json");
        Files.writeString(real, settingsJson("\"fontSize\": 18"));
        Path link = dir.resolve("settings.json");
        symlink(link, real);

        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();
        assertEquals(18, settings.getFontSize());
        settings.setFontSize(20);
        assertTrue(config.save());

        assertTrue(Files.isSymbolicLink(link), "the link is still a link");
        assertEquals(20, JSON.readTree(real.toFile()).get("fontSize").asInt(), "the repository copy got the change");
        try (var left = Files.list(dotfiles)) {
            assertEquals(1, left.count(), "no staging file is left beside the real file");
        }
    }

    @Test
    void aSynchronouslyWrittenStoreKeepsItsSymlinkToo(@TempDir Path dir, @TempDir Path dotfiles) throws IOException {
        Path real = dotfiles.resolve("notes.json");
        Files.writeString(real, "{\"schemaVersion\": " + NoteStore.SCHEMA_VERSION + "}");
        Path link = dir.resolve("notes.json");
        symlink(link, real);

        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.saveNotes();

        assertTrue(Files.isSymbolicLink(link));
        assertTrue(
                JSON.readTree(real.toFile()).has("byProject"), "written to the real file: " + Files.readString(real));
    }

    @Test
    void aBrokenSymlinkIsReplacedRatherThanFailingTheSave(@TempDir Path dir) throws IOException {
        Path link = dir.resolve("settings.json");
        symlink(link, dir.resolve("gone").resolve("settings.json"));

        ConfigWriter.writeAtomic(link, "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals("{}", Files.readString(link));
    }

    // --- byte-order mark / legacy encoding ----------------------------------------------------------

    @Test
    void aByteOrderMarkDoesNotResetTheSettings(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        Files.write(file, withBom(settingsJson("\"fontSize\": 22", "\"aiApiKey\": \"sk-kept\"")));

        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();

        assertEquals(22, settings.getFontSize());
        assertEquals("sk-kept", settings.getAiApiKey());
        assertTrue(config.shared().takeLoadProblems().isEmpty(), "a BOM is not a problem worth reporting");
        assertFalse(Files.exists(dir.resolve("settings.json.corrupt.bak")));

        assertTrue(config.save());
        JsonNode saved = JSON.readTree(file.toFile());
        assertEquals(22, saved.get("fontSize").asInt(), "and the next save does not write defaults over it");
    }

    @Test
    void aByteOrderMarkDoesNotResetAStore(@TempDir Path dir) throws IOException {
        Files.write(
                dir.resolve("bookmarks.json"),
                withBom("{\"schemaVersion\": " + BookmarkStore.SCHEMA_VERSION
                        + ", \"byProject\": {\"\": {\"/a/b.txt\": [{\"line\": 3}]}}}"));

        ConfigManager config = new ConfigManager(dir);
        config.load();

        assertEquals(1, config.getBookmarks().get("/a/b.txt").size());
        assertTrue(config.shared().takeLoadProblems().isEmpty());
    }

    @Test
    void aByteOrderMarkDoesNotLoseTheLegacyTomlSettings(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("settings.toml"), withBom("schemaVersion = 100\nfontSize = 19\n"));

        ConfigManager config = new ConfigManager(dir);

        assertEquals(19, config.load().getFontSize());
        assertTrue(config.shared().takeLoadProblems().isEmpty());
    }

    @Test
    void oneByteThatIsNotUtf8CostsOneCharacterNotTheFile(@TempDir Path dir) throws IOException {
        // Saved as Windows-1252: the "é" of the author's name is the single byte 0xE9, which is not UTF-8.
        String text = settingsJson("\"fontSize\": 21", "\"authorName\": \"René\"", "\"theme\": \"light\"");
        Files.write(dir.resolve("settings.json"), text.getBytes(StandardCharsets.ISO_8859_1));

        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();

        assertEquals(21, settings.getFontSize(), "a value before the stray byte");
        assertEquals("light", settings.getTheme(), "a value after it");
        assertEquals("Ren�", settings.getAuthorNameRaw(), "only the undecodable character is replaced");
        assertTrue(config.shared().takeLoadProblems().isEmpty());
    }
}
