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
}
