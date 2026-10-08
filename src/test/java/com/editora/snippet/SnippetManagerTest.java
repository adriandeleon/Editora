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

    // --- JSONC user files and lossless write-back (A4-4) ---

    private static final String JSONC = """
            {
              // Place your snippets for python here.
              /* a block comment */
              "Dataclass": { "prefix": "zdc", "body": ["@dataclass", "class ${1:Name}:",], },
            }
            """;

    @Test
    void userFileWithCommentsAndTrailingCommasLoads(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("snippets"));
        Files.writeString(dir.resolve("snippets").resolve("python.json"), JSONC);
        SnippetManager m = manager(dir);
        assertNotNull(m.byPrefix("python", "zdc"), "a VS Code style (JSONC) file is read");
        assertEquals(1, m.userSnippets("python").size());
        assertNull(m.userFileProblem("python"));
        assertTrue(m.unreadableUserFiles().isEmpty());
    }

    @Test
    void anUnparseableUserFileIsReportedAndNeverOverwritten(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("snippets"));
        Path file = dir.resolve("snippets").resolve("java.json");
        String broken = "{ \"Mine\": { \"prefix\": \"zz\", \"body\": \"x\" } not valid";
        Files.writeString(file, broken);
        SnippetManager m = manager(dir);
        assertNotNull(m.userFileProblem("java"));
        assertNull(m.userFileProblem("kotlin"), "no file is not a problem");
        assertEquals(java.util.List.of("java.json"), m.unreadableUserFiles());
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> m.saveUserSnippets("java", java.util.List.of(new Snippet("New", "nw", "pass", "", "java"))));
        assertEquals(broken, Files.readString(file), "the file the user wrote is left exactly as it was");
    }

    @Test
    void saveKeepsEveryPrefixScopeAndUntouchedArrayBodies(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("snippets"));
        Path file = dir.resolve("snippets").resolve("go.json");
        Files.writeString(file, """
                {
                  "Function": { "prefix": ["zfn", "zfunc"], "body": ["func ${1:name}() {", "\\t$0", "}"],
                                "description": "a function", "scope": "go" },
                  "Log": { "prefix": "zlg", "body": "fmt.Println($1)" }
                }
                """);
        SnippetManager m = manager(dir);
        java.util.List<Snippet> user = new java.util.ArrayList<>(m.userSnippets("go"));
        // What Settings does: edit the OTHER snippet and write the whole list back.
        user.set(1, new Snippet("Log", "zlog", "fmt.Println($1)", "", "go"));
        m.saveUserSnippets("go", user);

        assertNotNull(m.byPrefix("go", "zfn"));
        assertNotNull(m.byPrefix("go", "zfunc"), "the second trigger survives the round trip");
        assertNotNull(m.byPrefix("go", "zlog"));
        assertNull(m.byPrefix("go", "zlg"));
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile());
        assertEquals("go", tree.get("Function").get("scope").asText(), "fields the editor does not model are kept");
        assertTrue(tree.get("Function").get("prefix").isArray());
        assertTrue(tree.get("Function").get("body").isArray(), "an unchanged body keeps its array form");

        // Changing the shown trigger of a multi-prefix snippet replaces the first and keeps the rest.
        user = new java.util.ArrayList<>(m.userSnippets("go"));
        Snippet f = user.get(0);
        user.set(0, new Snippet(f.name(), "zf", f.body(), f.description(), "go"));
        m.saveUserSnippets("go", user);
        assertNotNull(m.byPrefix("go", "zf"));
        assertNotNull(m.byPrefix("go", "zfunc"));
        assertNull(m.byPrefix("go", "zfn"));
    }

    // --- Fix pass: N6, N9, N12, N13, N14, N16, N17, N5 ---

    private static Path write(Path dir, String name, String json) throws Exception {
        Files.createDirectories(dir.resolve("snippets"));
        Path file = dir.resolve("snippets").resolve(name);
        Files.writeString(file, json);
        return file;
    }

    /** N6: a user entry with a bundled snippet's name IS that snippet, edited — every old trigger goes. */
    @Test
    void aUserEntryReplacesTheBundledSnippetOfTheSameNameWithAllItsTriggers(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        Snippet bundled = m.byPrefix("java", "fori");
        assertNotNull(bundled);
        SnippetManager.Entry entry = m.entries("java").stream()
                .filter(e -> e.name().equals(bundled.name()))
                .findFirst()
                .orElseThrow();
        m.saveUserEntry("java", entry.name(), entry.withFields(entry.name(), java.util.List.of("floop"), "LOOP", ""));
        assertNotNull(m.byPrefix("java", "floop"));
        assertNull(m.byPrefix("java", "fori"), "the old trigger no longer expands the bundled body");

        // A bundled snippet with several triggers: editing its body reaches every one of them.
        SnippetManager.Entry multi = m.entries("java").stream()
                .filter(e -> e.prefixes().size() > 1 && e.source() == SnippetManager.Source.BUNDLED)
                .findFirst()
                .orElseThrow();
        m.saveUserEntry("java", multi.name(), multi.withFields(multi.name(), multi.prefixes(), "EDITED", ""));
        for (String trigger : multi.prefixes()) {
            assertEquals("EDITED", m.byPrefix("java", trigger).body(), trigger + " still had the bundled body");
        }
        // Removing the edit brings the bundled snippet back.
        m.removeUserEntry("java", entry.name());
        assertEquals(bundled.body(), m.byPrefix("java", "fori").body());
    }

    @Test
    void aBundledSnippetCanBeSwitchedOffAndOnAgain(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        String name = m.byPrefix("java", "main").name();
        m.setDisabled("java", name, true);
        assertNull(m.byPrefix("java", "main"));
        assertTrue(m.forLanguage("java").stream().noneMatch(s -> s.name().equals(name)));
        SnippetManager.Entry off = m.entries("java").stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow();
        assertTrue(off.disabled() && off.bundled() && !off.body().isEmpty(), "still listed, with its bundled body");
        assertTrue(Files.readString(m.userFile("java")).contains("\"disabled\": true"));
        m.setDisabled("java", name, false);
        assertNotNull(m.byPrefix("java", "main"));
        assertEquals(0, m.userSnippets("java").size(), "the marker entry is gone again");
    }

    /** N13: bundled < plugin < user. */
    @Test
    void theUsersSnippetsOverrideAPluginsAndAPluginOverridesTheBundled(@TempDir Path dir) throws Exception {
        Path plugin = dir.resolve("plug");
        Files.createDirectories(plugin);
        Files.writeString(
                plugin.resolve("go.json"),
                "{ \"P lg\": { \"prefix\": \"lg\", \"body\": \"PLUGIN\" },"
                        + " \"P main\": { \"prefix\": \"pkgm\", \"body\": \"PLUGIN-MAIN\" } }");
        write(dir, "go.json", "{ \"U lg\": { \"prefix\": \"lg\", \"body\": \"USER\" } }");
        SnippetManager m = manager(dir);
        String bundledPkgm = m.byPrefix("go", "pkgm").body();
        m.addExtraSourceDir(plugin);
        assertEquals("USER", m.byPrefix("go", "lg").body(), "the user's own file wins over the plugin");
        assertEquals("PLUGIN-MAIN", m.byPrefix("go", "pkgm").body(), "and the plugin over the bundled one");
        assertTrue(!bundledPkgm.equals("PLUGIN-MAIN"));
        assertEquals(
                1,
                m.forLanguage("go").stream()
                        .filter(s -> s.prefix().equals("lg"))
                        .count());
    }

    /** N14: one source, one trigger, two snippets: both are offered; Tab takes the first. */
    @Test
    void snippetsSharingATriggerAreBothKept(@TempDir Path dir) throws Exception {
        write(
                dir,
                "go.json",
                "{ \"Log line\": { \"prefix\": \"zlg\", \"body\": \"A\" },"
                        + " \"Log fatal\": { \"prefix\": \"zlg\", \"body\": \"B\" } }");
        SnippetManager m = manager(dir);
        assertEquals(
                2,
                m.forLanguage("go").stream()
                        .filter(s -> s.prefix().equals("zlg"))
                        .count());
        assertEquals("A", m.byPrefix("go", "zlg").body());
        // The bundled PowerShell file has three snippets on one trigger.
        assertEquals(
                3,
                m.forLanguage("powershell").stream()
                        .filter(s -> s.prefix().equals("[SuppressMessageAttribute]"))
                        .count());
    }

    /** N16. */
    @Test
    void scopeLimitsAnEntryToItsLanguages(@TempDir Path dir) throws Exception {
        write(
                dir,
                "global.json",
                "{ \"Web log\": { \"prefix\": \"zwl\", \"body\": \"console.log\", \"scope\": \"javascript, typescript\" },"
                        + " \"Sh\": { \"prefix\": \"zsh\", \"body\": \"#!\", \"scope\": \"shellscript\" },"
                        + " \"Tm\": { \"prefix\": \"ztm\", \"body\": \"t\", \"scope\": \"source.python\" } }");
        SnippetManager m = manager(dir);
        assertNotNull(m.byPrefix("javascript", "zwl"));
        assertNotNull(m.byPrefix("typescript", "zwl"));
        assertNull(m.byPrefix("python", "zwl"), "a snippet scoped to JS/TS is not offered in Python");
        assertNotNull(m.byPrefix("shell", "zsh"), "VS Code's id for the language is understood");
        assertNotNull(m.byPrefix("java", "ztm"), "a TextMate scope name is not a language list and limits nothing");
        assertTrue(SnippetManager.scopeAllows("", "java") && SnippetManager.scopeAllows(null, "java"));
    }

    /** N12: JSX/TSX get the JavaScript/TypeScript snippets, bundled and the user's. */
    @Test
    void reactLanguagesFallBackToTheirBaseLanguage(@TempDir Path dir) throws Exception {
        write(dir, "typescript.json", "{ \"Mine\": { \"prefix\": \"zts\", \"body\": \"TS\" } }");
        write(dir, "typescriptreact.json", "{ \"Comp\": { \"prefix\": \"zcomp\", \"body\": \"TSX\" } }");
        SnippetManager m = manager(dir);
        assertNotNull(m.byPrefix("typescriptreact", "interface"), "bundled TypeScript");
        assertEquals("TS", m.byPrefix("typescriptreact", "zts").body());
        assertEquals("TSX", m.byPrefix("typescriptreact", "zcomp").body());
        assertNull(m.byPrefix("typescript", "zcomp"), "the fallback goes one way");
        assertNotNull(m.byPrefix("javascriptreact", "forof"));
        assertTrue(m.languagesWithSnippets().containsAll(java.util.List.of("global", "typst", "typescriptreact")));
        assertEquals("global", m.languagesWithSnippets().get(0));
    }

    /** N12: the list of bundled languages is the resource directory, and every body in it parses. */
    @Test
    void everyBundledBodyParsesAndNamesOnlyRealVariables(@TempDir Path dir) throws Exception {
        java.util.List<String> onDisk;
        try (var files = Files.list(Path.of("src/main/resources/com/editora/snippets"))) {
            onDisk = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json") && !n.equals("global.json"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted()
                    .toList();
        }
        assertEquals(onDisk, SnippetManager.BUNDLED_LANGUAGES);
        SnippetManager m = manager(dir);
        java.util.regex.Pattern variable =
                java.util.regex.Pattern.compile("(?<![\\\\$])\\$\\{?([A-Za-z_][A-Za-z0-9_]*)");
        VariableResolver vars = new VariableResolver("File.ext", "/d", "/d/File.ext", "", "", 0, "");
        java.util.List<String> all = new java.util.ArrayList<>(SnippetManager.BUNDLED_LANGUAGES);
        all.add("global");
        for (String lang : all) {
            java.util.List<SnippetManager.Entry> entries = m.entries(lang);
            assertTrue(entries.size() >= 2, lang + " ships " + entries.size() + " snippets");
            for (SnippetManager.Entry e : entries) {
                String where = lang + " / " + e.name();
                assertTrue(!e.prefixes().isEmpty(), where + " has no trigger");
                assertTrue(!e.body().isBlank(), where + " has no body");
                for (String t : e.prefixes()) {
                    assertTrue(t.length() <= TabExpansion.MAX_TRIGGER, where + ": trigger too long to type: " + t);
                    assertTrue(t.trim().split("\\s+").length <= TabExpansion.MAX_WORDS, where + ": " + t);
                }
                ParsedSnippet p = SnippetParser.parse(e.body(), vars);
                for (TabStop s : p.stops()) {
                    for (int[] r : s.ranges()) {
                        assertTrue(
                                0 <= r[0] && r[0] <= r[1] && r[1] <= p.text().length(), where + ": stop off the text");
                    }
                }
                var names = variable.matcher(e.body());
                while (names.find()) {
                    assertTrue(
                            VariableResolver.NAMES.contains(names.group(1)),
                            where + " names $" + names.group(1) + ", which is not a variable (escape it: \\$)");
                }
            }
        }
    }

    /** N5: the bundled global words are popup/picker-only; the user's own global snippets expand on Tab. */
    @Test
    void bundledGlobalSnippetsAreNotTabTriggers(@TempDir Path dir) throws Exception {
        SnippetManager m = manager(dir);
        assertNotNull(m.byPrefix("java", "date"), "still offered in the popup and picker");
        assertNull(m.byTabTrigger("java", "date"));
        assertNull(m.byTabTrigger("markdown", "time"));
        assertNotNull(m.byTabTrigger("java", "fori"));
        write(dir, "global.json", "{ \"Sig\": { \"prefix\": \"zsig\", \"body\": \"-- me\" } }");
        m.reload();
        assertNotNull(m.byTabTrigger("markdown", "zsig"), "a global snippet the user wrote is deliberate");
        // Editing the bundled one makes it the user's.
        SnippetManager.Entry date = m.entries("global").stream()
                .filter(e -> e.prefixes().contains("date"))
                .findFirst()
                .orElseThrow();
        m.saveUserEntry("global", date.name(), date.withFields(date.name(), date.prefixes(), date.body(), ""));
        assertNotNull(m.byTabTrigger("java", "date"));
        assertNull(m.byTabTrigger("java", "time"));
        assertEquals(1, m.maxTriggerWords("java"));
        assertEquals(3, m.maxTriggerWords("markdown"), "bold and italic");
    }

    /** N9: one bad entry costs one entry; the file is named with the line; an empty file is no error. */
    @Test
    void aBadEntryIsSkippedAloneAndProblemsAreReportedOncePerLoad(@TempDir Path dir) throws Exception {
        Path file = write(
                dir,
                "ruby.json",
                "{\n  \"Good\": { \"prefix\": \"zgood\", \"body\": \"ok\" },\n  \"Bad\": \"just a string\",\n"
                        + "  \"Also good\": { \"prefix\": \"zalso\", \"body\": [\"a\"] }\n}\n");
        write(dir, "lua.json", "");
        write(dir, "go.json", "{\n  \"X\": { \"prefix\": \"x\" \"body\": 1 }\n}\n");
        write(dir, "c.json", "[1, 2]");
        SnippetManager m = manager(dir);
        java.util.List<SnippetManager.Problem> told = new java.util.ArrayList<>();
        m.setProblemListener(told::add);

        assertNotNull(m.byPrefix("ruby", "zgood"));
        assertNotNull(m.byPrefix("ruby", "zalso"), "the entries around the bad one are loaded");
        assertNull(m.userFileProblem("ruby"), "the file as a whole is usable");
        m.forLanguage("ruby");
        assertEquals(1, told.size(), "reported once, not on every lookup");
        SnippetManager.Problem bad = told.get(0);
        assertEquals(SnippetManager.Problem.Kind.BAD_ENTRY, bad.kind());
        assertEquals("Bad", bad.entry());
        assertEquals(3, bad.line());
        assertEquals(file, bad.file());

        assertNull(m.userFileProblem("lua"), "a 0-byte file is simply empty");
        assertNotNull(m.byPrefix("lua", "function"));

        told.clear();
        java.util.List<SnippetManager.Problem> all = m.checkUserFiles();
        assertEquals(3, all.size(), "ruby's entry, go's syntax, c's shape: " + all);
        SnippetManager.Problem syntax = all.stream()
                .filter(p -> p.kind() == SnippetManager.Problem.Kind.SYNTAX)
                .findFirst()
                .orElseThrow();
        assertEquals("go.json", syntax.fileName());
        assertEquals(2, syntax.line());
        for (SnippetManager.Problem p : all) {
            assertTrue(!p.detail().contains("java.") && !p.detail().contains("com.fasterxml"), "no class names: " + p);
        }
        assertEquals(2, told.size(), "go and c are new; ruby was already reported");
        assertEquals(java.util.List.of("c.json", "go.json"), m.unreadableUserFiles());

        m.reload();
        told.clear();
        m.checkUserFiles();
        assertEquals(3, told.size(), "after a reload a problem that is still there is said again");
    }

    /** N17: a save rewrites one entry; comments, order and unmodelled fields elsewhere stay. */
    @Test
    void savingOneEntryKeepsTheCommentsAndARenameKeepsScopeAndTriggers(@TempDir Path dir) throws Exception {
        String original = """
                {
                  // Place your snippets for go here.
                  "Function": {
                    "prefix": ["zfn", "zfunc"],
                    "body": ["func ${1:name}() {", "\\t$0", "}"],
                    "scope": "go",
                    "isFileTemplate": false
                  },
                  /* logging */
                  "Log": { "prefix": "zlg", "body": "fmt.Println($1)" }, // keep me
                }
                """;
        Path file = write(dir, "go.json", original);
        SnippetManager m = manager(dir);
        assertTrue(m.userFileHasComments("go"));
        assertTrue(!m.userEntryHasComments("go", "Function"));
        java.util.List<Path> changed = new java.util.ArrayList<>();
        m.setOnUserFilesChanged(() -> changed.add(file));

        SnippetManager.Entry fn = m.entries("go").stream()
                .filter(e -> e.name().equals("Function"))
                .findFirst()
                .orElseThrow();
        assertEquals(java.util.List.of("zfn", "zfunc"), fn.prefixes());
        assertEquals("go", fn.scope());
        m.saveUserEntry("go", "Function", fn.withFields("Func", fn.prefixes(), fn.body(), "a function"));

        String text = Files.readString(file);
        assertTrue(text.contains("// Place your snippets for go here."), text);
        assertTrue(text.contains("/* logging */") && text.contains("// keep me"), text);
        assertTrue(text.contains("\"Log\": { \"prefix\": \"zlg\", \"body\": \"fmt.Println($1)\" }"), "untouched entry");
        assertTrue(text.indexOf("\"Func\"") < text.indexOf("\"Log\""), "the renamed entry kept its place");
        assertEquals(1, changed.size());
        assertNotNull(m.byPrefix("go", "zfn"));
        assertNotNull(m.byPrefix("go", "zfunc"), "a rename keeps every trigger");
        SnippetManager.Entry renamed = m.entries("go").stream()
                .filter(e -> e.name().equals("Func"))
                .findFirst()
                .orElseThrow();
        assertEquals("go", renamed.scope(), "and its scope");
        assertTrue(text.contains("\"isFileTemplate\": false"), "and the fields this editor does not model");
        assertTrue(
                text.contains("\"body\": [ \"func ${1:name}() {\""), "an unchanged body keeps its array form: " + text);

        // Adding and removing splice too.
        m.saveUserEntry("go", null, SnippetManager.Entry.user("New", java.util.List.of("znew"), "x\ny", ""));
        m.removeUserEntry("go", "Log");
        text = Files.readString(file);
        assertTrue(text.contains("// Place your snippets for go here.") && text.contains("/* logging */"), text);
        assertNotNull(m.byPrefix("go", "znew"));
        assertNull(m.byPrefix("go", "zlg"));
        assertNull(m.userFileProblem("go"), "still a file that parses: " + text);
        assertTrue(SnippetManager.hasComment("{ \"a\": \"http://x\" } // c"));
        assertTrue(!SnippetManager.hasComment("{ \"a\": \"http://x /* no */\" }"));
    }
}
