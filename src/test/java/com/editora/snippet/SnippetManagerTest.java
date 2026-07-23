package com.editora.snippet;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests loading bundled snippets, language + global scoping, and user overrides. */
class SnippetManagerTest {

    private SnippetManager manager(Path configDir) {
        return new SnippetManager(new ConfigManager(configDir));
    }

    @Test
    void loadsBundledLanguageAndGlobalSnippets(@TempDir Path dir) {
        SnippetManager m = manager(dir);
        assertNotNull(m.byPrefix("java", "main")); // bundled java
        assertNotNull(m.byPrefix("java", "fori"));
        assertNotNull(m.byPrefix("python", "def")); // bundled python
        assertNotNull(m.byPrefix("java", "date")); // global available in every language
    }

    @Test
    void allBundledLanguagesLoad(@TempDir Path dir) {
        SnippetManager m = manager(dir);
        // Every language Editora highlights should have a bundled snippet file that parses to >=1
        // language-specific snippet (a parse failure would fall back to global only).
        for (String lang : new String[] {
            "java",
            "c",
            "cpp",
            "csharp",
            "css",
            "go",
            "html",
            "kotlin",
            "markdown",
            "powershell",
            "python",
            "ruby",
            "rust",
            "shell",
            "sql",
            "xml",
            "batchfile",
            "groovy",
            "json",
            "yaml",
            "ini"
        }) {
            boolean own =
                    m.forLanguage(lang).stream().anyMatch(s -> s.language().equals(lang));
            assertTrue(own, "no bundled snippets parsed for " + lang);
        }
    }

    @Test
    void arrayPrefixRegistersEveryTrigger(@TempDir Path dir) throws Exception {
        // VS Code allows an array of prefixes; friendly-snippets uses this (e.g. PowerShell).
        Files.createDirectories(dir.resolve("snippets"));
        Files.writeString(
                dir.resolve("snippets").resolve("java.json"),
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
        Files.writeString(
                dir.resolve("snippets").resolve("java.json"),
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
        Files.writeString(
                dir.resolve("snippets").resolve("java.json"),
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

    // --- Settings → Snippets management (saveUserSnippets / userSnippets / userSnippetLanguages) ---

    @Test
    void saveUserSnippetsRoundTripsAndIsLive(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        m.saveUserSnippets(
                "java", java.util.List.of(new Snippet("Logger", "logx", "log.info(\"$1\");$0", "log call", "java")));

        // Round-trips through the user file (file order, single prefix).
        java.util.List<Snippet> user = m.userSnippets("java");
        assertEquals(1, user.size());
        assertEquals("Logger", user.get(0).name());
        assertEquals("logx", user.get(0).prefix());
        assertEquals("log.info(\"$1\");$0", user.get(0).body());

        // The file exists and the snippet is live (saveUserSnippets clears the cache).
        assertTrue(Files.isReadable(m.userFile("java")));
        assertNotNull(m.byPrefix("java", "logx"));
        assertNotNull(m.byPrefix("java", "main")); // bundled still present
    }

    @Test
    void userSnippetOverridesBundledOnPrefixClash(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        m.saveUserSnippets("java", java.util.List.of(new Snippet("MyMain", "main", "// mine", "", "java")));
        Snippet s = m.byPrefix("java", "main");
        assertNotNull(s);
        assertEquals("// mine", s.body()); // user wins over the bundled "main"
    }

    @Test
    void blankNamedSnippetsAreNotWritten(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        m.saveUserSnippets(
                "go",
                java.util.List.of(new Snippet("keep", "k", "body", "", "go"), new Snippet("  ", "x", "y", "", "go")));
        assertEquals(1, m.userSnippets("go").size());
    }

    @Test
    void userSnippetLanguagesListsSavedFiles(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        assertTrue(m.userSnippetLanguages().isEmpty());
        m.saveUserSnippets("python", java.util.List.of(new Snippet("p", "p", "pass", "", "python")));
        m.saveUserSnippets("global", java.util.List.of(new Snippet("g", "g", "x", "", "global")));
        assertEquals(java.util.List.of("global", "python"), m.userSnippetLanguages()); // sorted
    }

    @Test
    void bundledSnippetsReturnsTheShippedSetWithoutUserEntries(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        // The shipped java snippets are listed (so the Settings page isn't empty) — and a user override is
        // NOT part of bundledSnippets (that's the user file's job).
        java.util.List<Snippet> bundled = m.bundledSnippets("java");
        assertTrue(bundled.size() > 1, "expected shipped java snippets");
        assertTrue(bundled.stream().anyMatch(s -> "main".equals(s.prefix())));

        m.saveUserSnippets("java", java.util.List.of(new Snippet("MyMain", "main", "// mine", "", "java")));
        assertTrue(
                m.bundledSnippets("java").stream().noneMatch(s -> "MyMain".equals(s.name())),
                "bundledSnippets must not include user entries");
    }

    /**
     * The bundled PowerShell snippets use regex transforms, and used to expand wrongly — the transform was
     * ignored and a leading non-value occurrence stole the value slot (#624 / #642). Expand the real shipped
     * bodies and check the derived text, so a regression in the transform pipeline is caught end to end.
     */
    @Test
    void bundledPowershellTransformSnippetsExpandCorrectly(@TempDir Path dir) {
        SnippetManager m = manager(dir);

        Snippet foreachItem = m.byPrefix("powershell", "foreach-item");
        assertNotNull(foreachItem, "the bundled foreach-item snippet");
        // TM_SELECTED_TEXT empty → ${1:${TM_SELECTED_TEXT:collection}} falls back to "collection"
        String a = SnippetParser.parse(foreachItem.body(), name -> null).text();
        assertTrue(
                a.contains("foreach (collectionItem in collection)"),
                "foreach-item derives the loop variable and keeps the collection default: " + a);

        Snippet splat = m.byPrefix("powershell", "splat");
        assertNotNull(splat, "the bundled splat snippet");
        String b = SnippetParser.parse(splat.body(), name -> "Get-Item").text();
        // $${1/[^\w]/_/}Params must sanitise to $Get_ItemParams, never the invalid $Get-ItemParams
        assertTrue(b.contains("$Get_ItemParams"), "splat sanitises non-word chars in the mirror: " + b);
        assertTrue(!b.contains("$Get-ItemParams"), "the invalid unsanitised form must not appear: " + b);
    }
}
