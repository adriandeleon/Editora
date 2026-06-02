package com.editora.snippet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.editora.config.ConfigManager;

/** Tests loading bundled snippets, language + global scoping, and user overrides. */
class SnippetManagerTest {

    private SnippetManager manager(Path configDir) {
        return new SnippetManager(new ConfigManager(configDir));
    }

    @Test
    void loadsBundledLanguageAndGlobalSnippets(@TempDir Path dir) {
        SnippetManager m = manager(dir);
        assertNotNull(m.byPrefix("java", "main"));  // bundled java
        assertNotNull(m.byPrefix("java", "fori"));
        assertNotNull(m.byPrefix("python", "def")); // bundled python
        assertNotNull(m.byPrefix("java", "date"));  // global available in every language
    }

    @Test
    void allBundledLanguagesLoad(@TempDir Path dir) {
        SnippetManager m = manager(dir);
        // Every language Editora highlights should have a bundled snippet file that parses to >=1
        // language-specific snippet (a parse failure would fall back to global only).
        for (String lang : new String[]{"java", "c", "cpp", "csharp", "css", "go", "html", "kotlin",
                "markdown", "powershell", "python", "ruby", "rust", "shell", "sql",
                "xml", "batchfile", "groovy", "json", "yaml", "ini"}) {
            boolean own = m.forLanguage(lang).stream().anyMatch(s -> s.language().equals(lang));
            assertTrue(own, "no bundled snippets parsed for " + lang);
        }
    }

    @Test
    void arrayPrefixRegistersEveryTrigger(@TempDir Path dir) throws Exception {
        // VS Code allows an array of prefixes; friendly-snippets uses this (e.g. PowerShell).
        Files.createDirectories(dir.resolve("snippets"));
        Files.writeString(dir.resolve("snippets").resolve("java.json"),
                "{ \"Multi\": { \"prefix\": [\"aa\", \"bb\"], \"body\": \"X\", \"scope\": \"java\" } }");
        SnippetManager m = manager(dir);
        assertNotNull(m.byPrefix("java", "aa"));
        assertNotNull(m.byPrefix("java", "bb"));
    }

    @Test
    void unknownLanguageStillGetsGlobal(@TempDir Path dir) {
        SnippetManager m = manager(dir);
        assertNotNull(m.byPrefix("nosuchlang", "date"));
        assertNull(m.byPrefix("nosuchlang", "fori")); // a java-only prefix isn't there
    }

    @Test
    void userSnippetOverridesBundledByPrefix(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("snippets"));
        Files.writeString(dir.resolve("snippets").resolve("java.json"),
                "{ \"My For\": { \"prefix\": \"for\", \"body\": \"USERFOR\", \"description\": \"mine\" } }");
        SnippetManager m = manager(dir);
        assertEquals("USERFOR", m.byPrefix("java", "for").body());
        assertEquals("My For", m.byPrefix("java", "for").name());
    }

    @Test
    void reloadPicksUpNewUserFile(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        assertNull(m.byPrefix("java", "zzz"));
        Files.createDirectories(dir.resolve("snippets"));
        Files.writeString(dir.resolve("snippets").resolve("java.json"),
                "{ \"Z\": { \"prefix\": \"zzz\", \"body\": [\"a\", \"b\"] } }");
        m.reload();
        Snippet s = m.byPrefix("java", "zzz");
        assertNotNull(s);
        assertEquals("a\nb", s.body()); // array body joined with newlines
    }

    @Test
    void malformedJsonIsSkippedNotFatal(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("snippets"));
        Files.writeString(dir.resolve("snippets").resolve("java.json"), "{ not valid json");
        SnippetManager m = manager(dir);
        assertTrue(m.forLanguage("java").size() > 0); // bundled still load
        assertNotNull(m.byPrefix("java", "main"));
    }
}
