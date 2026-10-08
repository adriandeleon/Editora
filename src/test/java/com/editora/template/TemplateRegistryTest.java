package com.editora.template;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests loading bundled templates and user overrides/additions against a temp config dir. */
class TemplateRegistryTest {

    private TemplateRegistry registry(Path configDir) {
        return new TemplateRegistry(new ConfigManager(configDir));
    }

    private Template byId(TemplateRegistry r, String id) {
        return r.all().stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void loadsBundledTemplates(@TempDir Path dir) {
        TemplateRegistry r = registry(dir);
        Template java = byId(r, "java-class");
        assertNotNull(java);
        assertEquals("Java Class", java.name());
        assertEquals("${className:Main}.java", java.fileName());
        assertFalse(java.isMultiFile());
        assertTrue(java.body().contains("${cursor}"));
    }

    @Test
    void loadsShellScriptTemplate(@TempDir Path dir) {
        Template shell = byId(registry(dir), "shell-script");
        assertNotNull(shell);
        assertEquals("Shell Script", shell.name());
        assertEquals("shell", shell.language());
        assertEquals("${baseName:script}.sh", shell.fileName());
        assertFalse(shell.isMultiFile());
        assertTrue(shell.body().contains("#!/usr/bin/env bash"));
        assertTrue(shell.body().contains("${cursor}"));
    }

    @Test
    void loadsZshScriptTemplate(@TempDir Path dir) {
        Template zsh = byId(registry(dir), "zsh-script");
        assertNotNull(zsh);
        assertEquals("Zsh Script", zsh.name());
        assertEquals("shell", zsh.language());
        assertEquals("${baseName:script}.zsh", zsh.fileName());
        assertFalse(zsh.isMultiFile());
        assertTrue(zsh.body().contains("#!/usr/bin/env zsh"));
        assertTrue(zsh.body().contains("${cursor}"));
    }

    @Test
    void loadsMultiFileTemplate(@TempDir Path dir) {
        Template bundle = byId(registry(dir), "html-bundle");
        assertNotNull(bundle);
        assertTrue(bundle.isMultiFile());
        assertEquals(2, bundle.files().size());
        assertEquals("${baseName:index}.html", bundle.files().get(0).path());
    }

    @Test
    void userTemplateOverridesBundledById(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(
                dir.resolve("templates").resolve("java-class.json"),
                "{ \"name\": \"Mine\", \"language\": \"java\", \"fileName\": \"X.java\", \"body\": \"X\" }");
        Template t = byId(registry(dir), "java-class");
        assertEquals("Mine", t.name());
        assertEquals("X.java", t.fileName());
    }

    @Test
    void userOnlyTemplateAppears(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(
                dir.resolve("templates").resolve("note.json"),
                "{ \"name\": \"Note\", \"fileName\": \"note.txt\", \"body\": [\"a\", \"b\"] }");
        Template t = byId(registry(dir), "note");
        assertNotNull(t);
        assertEquals("a\nb", t.body()); // array body joined with newlines
    }

    @Test
    void reloadPicksUpNewUserFile(@TempDir Path dir) throws Exception {
        TemplateRegistry r = registry(dir);
        assertNull(byId(r, "zzz"));
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(
                dir.resolve("templates").resolve("zzz.json"),
                "{ \"name\": \"Z\", \"fileName\": \"z.txt\", \"body\": \"z\" }");
        r.reload();
        assertNotNull(byId(r, "zzz"));
    }

    @Test
    void malformedUserTemplateIsSkippedNotFatal(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates").resolve("bad.json"), "{ not valid json");
        TemplateRegistry r = registry(dir);
        assertNotNull(byId(r, "java-class")); // bundled still load
        assertNull(byId(r, "bad"));
    }

    // --- Settings → Templates management (bundledTemplates / userTemplates / save / delete) ---

    @Test
    void bundledTemplatesAreListedAndExcludeUserEntries(@TempDir Path dir) throws Exception {
        TemplateRegistry r = registry(dir);
        assertTrue(r.bundledTemplates().size() >= 4, "expected the shipped templates");
        assertTrue(r.bundledTemplates().stream().anyMatch(t -> t.id().equals("java-class")));
        assertTrue(r.userTemplates().isEmpty());

        r.saveUserTemplate(new Template("mine", "Mine", "", "java", "${n}.java", "// x", null));
        assertTrue(r.bundledTemplates().stream().noneMatch(t -> t.id().equals("mine")));
        assertEquals("mine", r.userTemplates().get(0).id());
    }

    @Test
    void saveUserTemplateOverridesBundledById_andDeleteReverts(@TempDir Path dir) throws Exception {
        TemplateRegistry r = registry(dir);
        r.saveUserTemplate(new Template("java-class", "My Class", "", "java", "X.java", "// mine", null));
        assertEquals("// mine", byId(r, "java-class").body()); // user override wins
        assertTrue(Files.isReadable(r.userDir().resolve("java-class.json")));

        r.deleteUserTemplate("java-class");
        assertNotNull(byId(r, "java-class")); // bundled reappears
        assertFalse(byId(r, "java-class").body().equals("// mine"));
    }

    @Test
    void anIdThatIsNotAPlainFileNameIsRefusedInsteadOfWrittenOutsideTheTemplatesFolder(@TempDir Path dir) {
        assertTrue(TemplateRegistry.isValidId("java-class"));
        assertTrue(TemplateRegistry.isValidId(".gitignore"));
        assertTrue(TemplateRegistry.isValidId("My Template 2"));
        for (String bad : new String[] {"", " ", "a/b", "../escaped", "a\\b", "c:x", "what?"}) {
            org.junit.jupiter.api.Assertions.assertFalse(TemplateRegistry.isValidId(bad), bad);
        }
        TemplateRegistry r = registry(dir);
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> r.saveUserTemplate(new Template("../escaped", "x", "", "", "x.txt", "body", null)));
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(dir.resolve("escaped.json")));
    }

    // --- T9: no cache, so a second registry (another window) sees a change at once -----------------

    @Test
    void anotherRegistryOnTheSameFolderSeesANewTemplateWithoutReload(@TempDir Path dir) throws Exception {
        TemplateRegistry windowA = registry(dir);
        TemplateRegistry windowB = registry(dir);
        int before = windowB.all().size(); // would have primed the old cache
        windowA.saveUserTemplate(new Template("fresh", "Fresh", "", "", "a.txt", "b", null));
        assertEquals(before + 1, windowB.all().size());
        assertNotNull(byId(windowB, "fresh"));
        // A template JSON saved from the editor is just a file write: it is live too.
        Files.writeString(windowA.userDir().resolve("typed.json"), "{\"name\":\"Typed\",\"body\":\"x\"}");
        assertNotNull(byId(windowB, "typed"));
        windowA.deleteUserTemplate("fresh");
        assertNull(byId(windowB, "fresh"));
    }

    // --- T10: bad files are skipped with a reason; no junk rows ------------------------------------

    private static TemplateRegistry.Problem problem(TemplateRegistry r, String fileName) {
        return r.problems().stream()
                .filter(p -> p.file().getFileName().toString().equals(fileName))
                .findFirst()
                .orElse(null);
    }

    @Test
    void badUserTemplatesAreReportedWithFileReasonAndLine(@TempDir Path dir) throws Exception {
        Path ud = Files.createDirectories(dir.resolve("templates"));
        Files.writeString(ud.resolve("bad.json"), "{\n  \"name\": \"Bad\",\n  not json\n}");
        Files.writeString(ud.resolve("arr.json"), "[1,2]");
        Files.writeString(ud.resolve("nul.json"), "null");
        Files.writeString(ud.resolve("empty.json"), "{}");
        Files.writeString(ud.resolve("blankname.json"), "{\"name\":\"  \",\"body\":\"x\"}");
        Files.writeString(ud.resolve("nobody.json"), "{\"name\":\"NB\"}");
        Files.writeString(ud.resolve("emptyfiles.json"), "{\"name\":\"EF\",\"files\":[]}");
        Files.writeString(ud.resolve("nopath.json"), "{\"name\":\"NP\",\"files\":[{\"body\":\"x\"}]}");
        Files.writeString(ud.resolve("badbody.json"), "{\"name\":\"BB\",\"body\":{\"a\":1}}");
        Files.writeString(ud.resolve("badfiles.json"), "{\"name\":\"BF\",\"files\":\"zzz\"}");
        Files.writeString(ud.resolve("good.json"), "{\"name\":\"Good\",\"body\":[\"a\",\"b\"]}");
        TemplateRegistry r = registry(dir);

        assertEquals("a\nb", byId(r, "good").body());
        for (String id : new String[] {
            "bad", "arr", "nul", "empty", "blankname", "nobody", "emptyfiles", "nopath", "badbody", "badfiles"
        }) {
            assertNull(byId(r, id), id + " must not load as a junk row");
            assertNotNull(problem(r, id + ".json"), id + ".json must be reported");
        }
        TemplateRegistry.Problem bad = problem(r, "bad.json");
        assertEquals(TemplateRegistry.ProblemKind.MALFORMED_JSON, bad.kind());
        assertEquals(3, bad.line(), "the line the parser stopped on");
        assertFalse(bad.detail().isBlank());
        assertEquals(
                TemplateRegistry.ProblemKind.NOT_AN_OBJECT,
                problem(r, "arr.json").kind());
        assertEquals(
                TemplateRegistry.ProblemKind.NOT_AN_OBJECT,
                problem(r, "nul.json").kind());
        assertEquals(
                TemplateRegistry.ProblemKind.MISSING_NAME,
                problem(r, "empty.json").kind());
        assertEquals(
                TemplateRegistry.ProblemKind.MISSING_NAME,
                problem(r, "blankname.json").kind());
        assertEquals(
                TemplateRegistry.ProblemKind.NO_CONTENT,
                problem(r, "nobody.json").kind());
        assertEquals(
                TemplateRegistry.ProblemKind.NO_CONTENT,
                problem(r, "emptyfiles.json").kind());
        assertEquals(
                TemplateRegistry.ProblemKind.BAD_FILES,
                problem(r, "nopath.json").kind());
        assertEquals(
                TemplateRegistry.ProblemKind.NO_CONTENT,
                problem(r, "badbody.json").kind());
        assertEquals(
                TemplateRegistry.ProblemKind.BAD_FILES,
                problem(r, "badfiles.json").kind());
        assertTrue(registry(dir.resolve("clean")).problems().isEmpty(), "the bundled set loads clean");
    }

    @Test
    void anUpperCaseJsonExtensionIsATemplateToo(@TempDir Path dir) throws Exception {
        Path ud = Files.createDirectories(dir.resolve("templates"));
        Files.writeString(ud.resolve("UP.JSON"), "{\"name\":\"Upper\",\"body\":\"x\"}");
        TemplateRegistry r = registry(dir);
        assertEquals("Upper", byId(r, "UP").name());
        // Saving and deleting act on that file, not on a second UP.json beside it.
        r.saveUserTemplate(new Template("UP", "Upper 2", "", "", "", "y", null));
        assertEquals("Upper 2", byId(r, "UP").name());
        assertEquals(1, r.userFiles().size());
        r.deleteUserTemplate("UP");
        assertTrue(r.userFiles().isEmpty());
    }

    @Test
    void everyTemplateSaysWhereItCameFrom(@TempDir Path dir) throws Exception {
        TemplateRegistry r = registry(dir);
        r.saveUserTemplate(new Template("mine", "Mine", "", "", "a.txt", "b", null));
        assertEquals(Template.Origin.USER, byId(r, "mine").origin());
        assertEquals(Template.Origin.BUNDLED, byId(r, "java-class").origin());
    }

    // --- T15: the reserved id, delete validation ---------------------------------------------------

    @Test
    void theIdIndexIsRefusedInsteadOfSavingATemplateThatVanishes(@TempDir Path dir) throws Exception {
        assertFalse(TemplateRegistry.isValidId("index"));
        assertFalse(TemplateRegistry.isValidId("Index"));
        assertTrue(TemplateRegistry.isReservedId("index"));
        assertTrue(TemplateRegistry.isValidId("index2"));
        TemplateRegistry r = registry(dir);
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> r.saveUserTemplate(new Template("index", "Idx", "", "", "a.txt", "b", null)));
        assertFalse(Files.exists(r.userDir().resolve("index.json")));
        // One already on disk (an older build could write it) is reported, not silently ignored.
        Files.createDirectories(r.userDir());
        Files.writeString(r.userDir().resolve("index.json"), "{\"name\":\"Index\",\"body\":\"x\"}");
        assertNull(byId(r, "index"));
        assertEquals(
                TemplateRegistry.ProblemKind.RESERVED_ID,
                problem(r, "index.json").kind());
    }

    @Test
    void deleteRefusesAnIdThatIsAPath(@TempDir Path dir) throws Exception {
        Path victim = Files.writeString(dir.resolve("victim.json"), "{}");
        TemplateRegistry r = registry(dir);
        Files.createDirectories(r.userDir());
        org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class, () -> r.deleteUserTemplate("../victim"));
        assertTrue(Files.exists(victim));
        for (String bad : new String[] {"CON", "a?b", "trailing.", "nul.txt"}) {
            assertFalse(TemplateRegistry.isValidId(bad), bad);
        }
    }

    @Test
    void aBlankNameIsSavedAsTheIdSoTheFileStillLoads(@TempDir Path dir) throws Exception {
        TemplateRegistry r = registry(dir);
        r.saveUserTemplate(new Template("nameless", "  ", "", "", "a.txt", "b", null));
        assertEquals("nameless", byId(r, "nameless").name());
        assertTrue(r.problems().isEmpty());
    }

    // --- T17: bundled < plugin < user --------------------------------------------------------------

    @Test
    void aUserTemplateOverridesAPluginsAndAPluginOverridesABundledOne(@TempDir Path dir) throws Exception {
        Path plugin = Files.createDirectories(dir.resolve("plugins/acme/templates"));
        Files.writeString(plugin.resolve("java-class.json"), "{\"name\":\"Acme Class\",\"body\":\"p\"}");
        Files.writeString(plugin.resolve("shared.json"), "{\"name\":\"Plugin Shared\",\"body\":\"p\"}");
        Files.writeString(plugin.resolve("index.json"), "[\"java-class\",\"shared\"]");
        TemplateRegistry r = registry(dir.resolve("cfg"));
        r.addExtraSourceDir(plugin);

        assertEquals("Acme Class", byId(r, "java-class").name(), "plugin overrides bundled");
        assertEquals(Template.Origin.PLUGIN, byId(r, "java-class").origin());
        assertEquals("acme", byId(r, "java-class").source(), "the plugin is named");
        assertTrue(r.problems().isEmpty(), "a plugin's own index.json is not a problem");
        assertEquals(2, r.pluginTemplates().size());

        r.saveUserTemplate(new Template("shared", "My Shared", "", "", "a.txt", "u", null));
        r.saveUserTemplate(new Template("java-class", "My Class", "", "", "a.txt", "u", null));
        assertEquals("My Shared", byId(r, "shared").name(), "the user's file wins over the plugin's");
        assertEquals("My Class", byId(r, "java-class").name());
        assertEquals(Template.Origin.USER, byId(r, "shared").origin());

        r.deleteUserTemplate("shared");
        assertEquals("Plugin Shared", byId(r, "shared").name(), "removing the override brings the plugin's back");
    }

    // --- T16: copying a bundled template to the user's folder --------------------------------------

    @Test
    void duplicatingABundledMultiFileTemplateGivesAnIdenticalEditableCopy(@TempDir Path dir) throws Exception {
        TemplateRegistry r = registry(dir);
        Template bundled = byId(r, "python-project");
        Path file = r.duplicateToUser(bundled);
        assertEquals(r.userDir().resolve("python-project.json"), file);
        Template copy = byId(r, "python-project");
        assertEquals(Template.Origin.USER, copy.origin());
        assertEquals(bundled.files(), copy.files(), "every path and body survives the round trip");
        assertEquals(bundled.name(), copy.name());
        assertEquals(bundled.description(), copy.description());
        // A second call must not clobber the user's edits.
        Files.writeString(file, "{\"name\":\"Edited\",\"body\":\"mine\"}");
        assertEquals(file, r.duplicateToUser(bundled));
        assertEquals("Edited", byId(r, "python-project").name());
        assertTrue(Files.readString(file).contains("mine"));
    }

    @Test
    void wizardLabelsSurviveLoadAndSave(@TempDir Path dir) throws Exception {
        Path ud = Files.createDirectories(dir.resolve("templates"));
        Files.writeString(
                ud.resolve("issue.json"),
                "{\"name\":\"Issue\",\"body\":\"${issueTitle}\",\"labels\":{\"issueTitle\":\"Titel des Tickets\"}}");
        TemplateRegistry r = registry(dir);
        Template t = byId(r, "issue");
        assertEquals(java.util.Map.of("issueTitle", "Titel des Tickets"), t.labels());
        r.saveUserTemplate(t);
        assertEquals(
                java.util.Map.of("issueTitle", "Titel des Tickets"),
                byId(r, "issue").labels());
    }
}
