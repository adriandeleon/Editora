package com.editora.ui;

import javafx.scene.Node;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (headless-FX) coverage of {@link EditorBuffer} behaviors that need the toolkit: Markdown
 * view-mode switching + the minimap-hidden-in-preview rule, read-only / view mode, programmatic typing
 * (the macro-replay path with auto-close), run-glyph detection per language, and display-name/dirty/EOL
 * bookkeeping. Constructs buffers directly (no services), so it exercises the {@code editor} package alone.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditorBufferFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterAll
    void tearDown() {
        // Buffers are local to each test; nothing global to dispose.
    }

    private EditorBuffer markdownBuffer(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("markdown");
            b.setContent(text);
            b.getNode(); // realize the scene graph (scroll pane + minimap)
            return b;
        });
    }

    private static boolean minimapVisible(EditorBuffer b) {
        Node mm = FxTestSupport.field(b, "minimap");
        return mm.isVisible();
    }

    @Test
    void markdownPreviewModesToggleAndHideTheMinimap() throws Exception {
        EditorBuffer b = markdownBuffer("# Title\n\nSome **body** text.\n");
        assertTrue(FxTestSupport.callOnFx(b::isMarkdown));
        assertTrue(FxTestSupport.callOnFx(b::hasPreview));

        // EDITOR (default): minimap shown.
        assertEquals(MarkdownViewMode.EDITOR, FxTestSupport.callOnFx(b::getMarkdownViewMode));
        assertTrue(minimapVisible(b), "minimap shown in plain editor view");

        // SPLIT: the editor is still on screen, so the minimap stays shown.
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(MarkdownViewMode.SPLIT));
        assertEquals(MarkdownViewMode.SPLIT, FxTestSupport.callOnFx(b::getMarkdownViewMode));
        assertTrue(minimapVisible(b), "minimap shown in split (editor still visible)");

        // PREVIEW: no editor surface, so the minimap is hidden.
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        assertFalse(minimapVisible(b), "minimap hidden in full preview");

        // Back to EDITOR: minimap restored.
        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(MarkdownViewMode.EDITOR));
        assertTrue(minimapVisible(b), "minimap restored on return to editor");
    }

    @Test
    void minimapSettingStillGatesTheEditorView() throws Exception {
        EditorBuffer b = markdownBuffer("plain\n");
        FxTestSupport.runOnFx(() -> b.setMinimapVisible(false));
        assertFalse(minimapVisible(b), "user turned the minimap off");
        FxTestSupport.runOnFx(() -> b.setMinimapVisible(true));
        assertTrue(minimapVisible(b), "user turned the minimap back on");
    }

    @Test
    void viewModeMakesTheBufferReadOnly() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setContent("code");
            return buf;
        });
        assertTrue(FxTestSupport.callOnFx(b::isEditable), "editable by default");
        FxTestSupport.runOnFx(() -> b.setViewMode(true));
        assertTrue(FxTestSupport.callOnFx(b::isViewMode));
        assertFalse(FxTestSupport.callOnFx(b::isEditable), "view mode blocks editing");
        FxTestSupport.runOnFx(() -> b.setViewMode(false));
        assertTrue(FxTestSupport.callOnFx(b::isEditable), "editable again");
    }

    @Test
    void typeStringReusesTheEditAssistsAndAutoClosesBrackets() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setLanguageOverride("java");
            buf.setContent("");
            return buf;
        });
        FxTestSupport.runOnFx(() -> b.typeString("x("));
        // Auto-close inserts the matching ) after the typed ( — same assist the live key filter uses.
        assertEquals("x()", FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void runGlyphDetectionPerLanguage() throws Exception {
        // A Java 25 compact source file (top-level void main) is runnable once the Run feature is on.
        EditorBuffer java = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setDisplayName("Main.java"); // compact-source detection keys off the .java name
            buf.setLanguageOverride("java");
            buf.setRunEnabled(true);
            buf.setContent("void main() {\n    IO.println(\"hi\");\n}\n");
            return buf;
        });
        assertTrue(FxTestSupport.callOnFx(java::isRunnable), "compact-source main ⇒ runnable");
        assertFalse(FxTestSupport.callOnFx(java::isPython));

        // Python is runnable (the controller picks python3 as the runner).
        EditorBuffer py = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setLanguageOverride("python");
            buf.setRunEnabled(true);
            buf.setContent("if __name__ == \"__main__\":\n    print('hi')\n");
            return buf;
        });
        assertTrue(FxTestSupport.callOnFx(py::isRunnable));
        assertTrue(FxTestSupport.callOnFx(py::isPython));

        // A shell script needs BOTH the Run feature and the shell-run gate (the Bash LSP toggle).
        EditorBuffer sh = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setLanguageOverride("shell");
            buf.setRunEnabled(true);
            buf.setContent("#!/bin/bash\necho hi\n");
            return buf;
        });
        assertTrue(FxTestSupport.callOnFx(sh::isShell));
        assertFalse(FxTestSupport.callOnFx(sh::isRunnable), "shell glyph gated off until shell-run enabled");
        FxTestSupport.runOnFx(() -> sh.setShellRunEnabled(true));
        assertTrue(FxTestSupport.callOnFx(sh::isRunnable), "shell glyph appears once enabled");

        // Turning the Run feature off removes the glyph for every language.
        FxTestSupport.runOnFx(() -> py.setRunEnabled(false));
        assertFalse(FxTestSupport.callOnFx(py::isRunnable));
    }

    @Test
    void shebangPromotesAnExtensionlessBufferOnLoad() throws Exception {
        // A file whose name resolves to plain text picks up its language from a first-line shebang.
        EditorBuffer py = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setPath(java.nio.file.Path.of("/tmp/pyscript")); // no extension ⇒ plaintext
            buf.setRunEnabled(true);
            buf.setContent("#!/usr/bin/env python3\nprint('hi')\n");
            return buf;
        });
        assertEquals("python", FxTestSupport.callOnFx(py::getLanguage), "python shebang ⇒ python");
        assertTrue(FxTestSupport.callOnFx(py::isPython));
        assertTrue(FxTestSupport.callOnFx(py::isRunnable));

        // A bash shebang ⇒ shell (still gated by the shell-run toggle for the glyph).
        EditorBuffer sh = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setPath(java.nio.file.Path.of("/tmp/deployer"));
            buf.setContent("#!/usr/bin/env bash\necho hi\n");
            return buf;
        });
        assertEquals("shell", FxTestSupport.callOnFx(sh::getLanguage), "bash shebang ⇒ shell");

        // A `java --source N` shebang ⇒ Java compact source: runnable with no .java extension, and the
        // captured source version feeds the run command.
        EditorBuffer javaBuf = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setPath(java.nio.file.Path.of("/tmp/launcher"));
            buf.setRunEnabled(true);
            buf.setContent("#!/usr/bin/env -S java --source 25\nvoid main() {\n    IO.println(\"hi\");\n}\n");
            return buf;
        });
        assertEquals("java", FxTestSupport.callOnFx(javaBuf::getLanguage), "java --source shebang ⇒ java");
        assertTrue(FxTestSupport.callOnFx(javaBuf::isRunnable), "shebang compact source ⇒ runnable");
        assertEquals(25, FxTestSupport.callOnFx(javaBuf::getShebangJavaSource));

        // A real extension always wins over any first-line content.
        EditorBuffer md = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setPath(java.nio.file.Path.of("/tmp/notes.md"));
            buf.setContent("#!/bin/bash\n# a markdown doc that happens to start oddly\n");
            return buf;
        });
        assertEquals("markdown", FxTestSupport.callOnFx(md::getLanguage), "real extension is never overridden");
    }

    @Test
    void displayNameDirtyAndLineEndingBookkeeping() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setDisplayName("scratch.md");
            buf.setContent("one\n");
            buf.markClean();
            return buf;
        });
        assertEquals("scratch.md", FxTestSupport.callOnFx(b::getDisplayName));
        assertFalse(FxTestSupport.callOnFx(b::isDirty), "clean right after markClean");

        FxTestSupport.runOnFx(() -> b.typeString("two"));
        assertTrue(FxTestSupport.callOnFx(b::isDirty), "an edit marks the buffer dirty");

        // EOL override is reflected by getLineEnding; the static detector reads raw text.
        FxTestSupport.runOnFx(() -> b.setEolOverride("CRLF"));
        assertEquals("CRLF", FxTestSupport.callOnFx(b::getLineEnding));
        assertEquals("CRLF", EditorBuffer.detectLineEnding("a\r\nb"));
        assertEquals("LF", EditorBuffer.detectLineEnding("a\nb"));
    }

    @Test
    void revertingToSavedContentClearsDirty() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setContent("abc");
            buf.markClean();
            return buf;
        });
        org.fxmisc.richtext.CodeArea area = FxTestSupport.field(b, "area");
        assertFalse(FxTestSupport.callOnFx(b::isDirty), "clean after markClean");

        // An edit of different length hits the cheap getLength() path ⇒ dirty.
        FxTestSupport.runOnFx(() -> area.appendText("X"));
        assertTrue(FxTestSupport.callOnFx(b::isDirty), "an edit marks dirty");

        // Reverting to the exact saved text (lengths match again) clears dirty via the getText compare.
        FxTestSupport.runOnFx(() -> area.replaceText("abc"));
        assertFalse(FxTestSupport.callOnFx(b::isDirty), "reverting to saved content clears dirty");
    }
}
