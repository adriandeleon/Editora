package com.editora.ui;

import java.nio.file.Path;

import javafx.stage.Popup;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void openingAFileOnItsServerShowsTheStatusSegmentAtOnce() throws Exception {
        fx.open("A.java", "class A {}\n");

        assertFalse(fx.ops.statusBarLabels.isEmpty(), "the status bar was told about the server");
        assertEquals("jdtls", fx.ops.statusBarLabels.get(fx.ops.statusBarLabels.size() - 1));
    }

    @Test
    void aManPageHoverScrollsInsideAPopupNoTallerThanPartOfTheWindow() throws Exception {
        EditorBuffer b = fx.open("A.java", "class A {\n    void go() {}\n}\n");
        fx.awaitHighlighted(b);
        fx.caret(b, 1, 9);
        StringBuilder man = new StringBuilder("```man\nNAME\n       ls - list directory contents\n");
        for (int i = 0; i < 300; i++) {
            man.append("       -").append(i).append("  an option described at some length on its own line\n");
        }
        man.append("```\n");
        fx.server().hoverResponse = new Hover(new MarkupContent("markdown", man.toString()));

        fx.run(() -> fx.coordinator.showHover());
        fx.layout();
        Popup popup = FxTestSupport.field(fx.coordinator, "hoverPopup");
        assertNotNull(popup, "the documentation is shown");
        double windowHeight = FxTestSupport.callOnFx(() -> fx.host.window().getHeight());
        double height = FxTestSupport.callOnFx(
                () -> popup.getScene().getRoot().getLayoutBounds().getHeight());
        assertTrue(
                height <= LspCoordinator.hoverMaxHeight(windowHeight) + 40,
                "the popup is " + height + " px tall in a " + windowHeight + " px window");
        assertNotNull(
                FxTestSupport.callOnFx(() -> popup.getScene().getRoot().lookup(".scroll-pane")),
                "the text scrolls inside the popup");
    }
}
