package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.FormattingOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the shell review found in the generic LSP flows: Format Document sent the global tab size instead of
 * the document's indentation (C1), the "LSP:" status segment stayed hidden after a file was opened on its
 * server (C5), and a long hover — a man page — made a popup taller than any screen (C3).
 */
@Tag("fx")
class LspFormattingStatusHoverFxTest {

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        fx.host.settings.setTabSize(4);
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    private FormattingOptions formatAndGetOptions() throws Exception {
        fx.run(() -> fx.coordinator.formatDocument());
        assertFalse(fx.server().formattings.isEmpty(), "Format Document asked the server");
        return FakeLanguageServer.last(fx.server().formattings).getOptions();
    }

    @Test
    void formatDocumentDescribesTheFilesOwnTwoSpaceIndentNotTheGlobalTabSize() throws Exception {
        fx.open("A.java", "class A {\n  void go() {\n    run();\n  }\n}\n");

        FormattingOptions options = formatAndGetOptions();

        assertEquals(2, options.getTabSize());
        assertTrue(options.isInsertSpaces());
    }

    @Test
    void formatDocumentFollowsTheEditorConfigIndentSize() throws Exception {
        EditorBuffer b = fx.open("A.java", "class A {\n    void go() {}\n}\n");
        FxTestSupport.runOnFx(() -> b.setIndentOverride(true, 2)); // [*] indent_style = space, indent_size = 2

        FormattingOptions options = formatAndGetOptions();

        assertEquals(2, options.getTabSize());
        assertTrue(options.isInsertSpaces());
    }

    @Test
    void aTabIndentedFileAsksForTabs() throws Exception {
        fx.open("A.java", "class A {\n\tvoid go() {}\n}\n");

        assertFalse(formatAndGetOptions().isInsertSpaces());
    }
}
