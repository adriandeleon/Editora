package com.editora.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two features record a file's past: Editora's own Local History and Git's history of the file. Each has one
 * name, in every language, wherever it is mentioned — they were both "File History", and the command of that
 * name did not open the window of that name.
 */
class HistoryNamingTest {

    private static final List<String> CATALOGS = List.of("", "_de", "_es", "_fr", "_it", "_pt");

    private static Properties load(String suffix) {
        Properties p = new Properties();
        try (InputStream in =
                Messages.class.getResourceAsStream("/com/editora/i18n/messages" + suffix + ".properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return p;
    }

    @Test
    void theLocalFeatureHasOneNameInEveryLanguage() {
        for (String suffix : CATALOGS) {
            Properties p = load(suffix);
            String name = p.getProperty("settings.section.localHistory");
            assertEquals(name, p.getProperty("toolwindow.fileHistory"), suffix + ": the tool window's title");
            assertEquals(name, p.getProperty("menubar.vcs.localHistory"), suffix + ": the menu-bar submenu");
            assertTrue(
                    p.getProperty("command.tool.fileHistory").endsWith(name),
                    suffix + ": the tool-window command names the window");
            // Every palette command of the feature starts with the same words.
            String prefix =
                    p.getProperty("command.history.putLabel").split(":")[0].strip();
            for (String command : List.of(
                    "history.recentChanges",
                    "history.setMaxPerFile",
                    "history.setMaxAgeDays",
                    "history.setMaxTotalMb",
                    "localHistory.purgeFile",
                    "localHistory.purgeProject")) {
                assertEquals(
                        prefix,
                        p.getProperty("command." + command).split(":")[0].strip(),
                        suffix + ": prefix of " + command);
            }
            assertEquals(name.toLowerCase(), prefix.toLowerCase(), suffix + ": the prefix is the feature's name");
        }
    }

    @Test
    void theGitCommandIsNotNamedLikeTheLocalOne() {
        for (String suffix : CATALOGS) {
            Properties p = load(suffix);
            String git = p.getProperty("project.menu.git.fileHistory");
            assertTrue(git.contains("Git"), suffix + ": the Git item says Git: " + git);
            assertEquals(git, p.getProperty("gitlog.menu.fileHistory"), suffix);
            assertTrue(p.getProperty("command.git.fileHistory").endsWith(git), suffix);
            assertNotEquals(git, p.getProperty("project.menu.localHistory"), suffix);
            assertFalse(
                    p.getProperty("toolwindow.fileHistory").equals(p.getProperty("toolwindow.gitLog")),
                    suffix + ": two tool windows with one title");
        }
        Properties en = load("");
        assertEquals("Show Git History", en.getProperty("project.menu.git.fileHistory"));
        assertEquals("Local History", en.getProperty("toolwindow.fileHistory"));
        for (String key : en.stringPropertyNames()) {
            assertFalse(
                    en.getProperty(key).contains("File History"),
                    key + " still uses the name both features had: " + en.getProperty(key));
        }
    }

    /** "Put Label" makes a new revision; renaming an existing one is a different question, titled so. */
    @Test
    void puttingALabelAndEditingOneAreTitledDifferently() {
        for (String suffix : CATALOGS) {
            Properties p = load(suffix);
            assertNotEquals(p.getProperty("history.label.title"), p.getProperty("history.label.editTitle"), suffix);
        }
    }

    /** The limits are counted in one unit, and the size limit says what it measures and what it spares. */
    @Test
    void theLimitsUseOneUnitWordAndSayWhatTheyMeasure() {
        Properties en = load("");
        for (String key : List.of(
                "command.history.setMaxPerFile",
                "command.history.setMaxPerFile.desc",
                "command.history.setMaxAgeDays.desc",
                "command.history.setMaxTotalMb.desc",
                "settings.history.maxPerFile",
                "settings.history.note",
                "settings.history.maxAgeDays.note")) {
            String value = en.getProperty(key).toLowerCase();
            assertTrue(value.contains("revision"), key + ": " + value);
            assertFalse(value.contains("snapshot"), key + ": " + value);
        }
        for (String key : List.of("settings.history.note", "command.history.setMaxTotalMb.desc")) {
            String value = en.getProperty(key);
            assertTrue(value.contains("project"), key);
            assertTrue(value.contains("uncompressed"), key);
            assertTrue(value.contains("newest revision"), key);
            assertTrue(value.contains("labelled revision"), key);
            assertTrue(value.contains("before a delete"), key);
        }
        assertTrue(en.getProperty("settings.history.maxTotalMb").contains("uncompressed"));
        assertTrue(en.getProperty("settings.history.maxAgeDays.note").startsWith("0 = no age limit"));
    }
}
