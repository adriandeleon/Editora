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
}
