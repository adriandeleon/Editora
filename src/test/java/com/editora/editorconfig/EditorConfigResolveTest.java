package com.editora.editorconfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorConfigResolveTest {

    @TempDir
    Path root;

    @BeforeEach
    void clear() {
        EditorConfig.clearCache();
    }

    private void write(Path dir, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".editorconfig"), content);
    }

    @Test
    void noEditorConfigYieldsEmpty() throws IOException {
        Path f = Files.createFile(root.resolve("a.txt"));
        assertTrue(EditorConfig.resolveFor(f).isEmpty());
    }

    @Test
    void matchesSectionForFile() throws IOException {
        write(root, "root = true\n[*]\nindent_style = space\nindent_size = 2\n");
        EditorConfigProperties p = EditorConfig.resolveFor(root.resolve("a.py"));
        assertEquals(Boolean.TRUE, p.insertSpaces());
        assertEquals(2, p.indentSize());
    }

    @Test
    void nearerDirectoryWins() throws IOException {
        write(root, "root = true\n[*]\nindent_size = 4\nend_of_line = lf\n");
        Path sub = root.resolve("sub");
        write(sub, "[*]\nindent_size = 2\n");
        EditorConfigProperties p = EditorConfig.resolveFor(sub.resolve("a.js"));
        assertEquals(2, p.indentSize()); // nearer overrides
        assertEquals("lf", p.endOfLine()); // inherited from the farther (root) file
    }

    @Test
    void unsetClearsTheInheritedValueInsteadOfKeepingIt() throws IOException {
        write(root, "root = true\n[*]\nindent_style = tab\nindent_size = 4\nend_of_line = crlf\ncharset = latin1\n");
        Path sub = root.resolve("sub");
        write(sub, "[*]\nindent_size = unset\nend_of_line = UNSET\n[*.md]\ncharset = unset\n");
        EditorConfigProperties p = EditorConfig.resolveFor(sub.resolve("a.md"));
        assertNull(p.indentSize(), "unset removes the parent's indent_size");
        assertNull(p.endOfLine(), "values are case-insensitive");
        assertNull(p.charset(), "a later matching section can unset too");
        assertEquals(Boolean.FALSE, p.insertSpaces()); // untouched keys still inherit

        // A later section may set the key again after an unset.
        write(sub, "[*]\nindent_size = unset\n[*.md]\nindent_size = 3\n");
        EditorConfig.clearCache();
        assertEquals(3, EditorConfig.resolveFor(sub.resolve("a.md")).indentSize());
    }

    @Test
    void invalidNearerValueStillInheritsTheParentValue() throws IOException {
        write(root, "root = true\n[*]\nindent_size = 4\ncharset = utf-8\n");
        Path sub = root.resolve("sub");
        write(sub, "[*]\nindent_size = huge\ncharset = klingon\n");
        EditorConfigProperties p = EditorConfig.resolveFor(sub.resolve("a.js"));
        assertEquals(4, p.indentSize());
        assertEquals("utf-8", p.charset());
    }

    @Test
    void rootTrueStopsTheWalk() throws IOException {
        write(root, "[*]\ncharset = latin1\n"); // would apply if reached
        Path sub = root.resolve("proj");
        write(sub, "root = true\n[*]\nindent_size = 8\n");
        EditorConfigProperties p = EditorConfig.resolveFor(sub.resolve("a.c"));
        assertEquals(8, p.indentSize());
        assertNull(p.charset()); // the parent was never consulted
    }

    @Test
    void laterSectionOverridesEarlierWithinFile() throws IOException {
        write(root, "root = true\n[*]\nindent_size = 4\n[*.md]\nindent_size = 2\ntrim_trailing_whitespace = false\n");
        EditorConfigProperties md = EditorConfig.resolveFor(root.resolve("README.md"));
        assertEquals(2, md.indentSize());
        assertEquals(Boolean.FALSE, md.trimTrailingWhitespace());
        EditorConfigProperties other = EditorConfig.resolveFor(root.resolve("a.txt"));
        assertEquals(4, other.indentSize());
        assertNull(other.trimTrailingWhitespace());
    }
}
