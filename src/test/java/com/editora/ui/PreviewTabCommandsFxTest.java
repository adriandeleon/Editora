package com.editora.ui;

import java.util.List;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.MarkdownPreviewPane;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The commands that open a Markdown preview in a tab of its own, beside the editor or in its group. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewTabCommandsFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private EditorArea editorArea;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        editorArea = FxTestSupport.field(fx.controller, "editorArea");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer open(String content, String language) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            if (language != null) {
                b.setLanguageOverride(language);
            }
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
        FxTestSupport.drainFx();
    }

    private List<MarkdownPreviewPane> previews() throws Exception {
        return FxTestSupport.callOnFx(() -> editorArea.tabs().stream()
                .map(Tab::getUserData)
                .filter(MarkdownPreviewPane.class::isInstance)
                .map(MarkdownPreviewPane.class::cast)
                .toList());
    }

    private EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    @Test
    void previewToTheSideOpensInTheNextGroupAndFollowsTheFileBeingEdited() throws Exception {
        EditorBuffer plain = open("not markdown\n", null);
        run("view.markdownPreviewToSide");
        assertTrue(previews().isEmpty(), "only a Markdown file has a preview");
        assertEquals(tr("status.notMarkdown"), SaveDecisionsFxTest.lastMessage(fx));

        EditorBuffer first = open("# First\n\nBody.\n", "markdown");
        int groupsBefore = FxTestSupport.callOnFx(() -> editorArea.groupCount());
        run("view.markdownPreviewToSide");
        List<MarkdownPreviewPane> opened = previews();
        assertEquals(1, opened.size());
        MarkdownPreviewPane pane = opened.get(0);
        assertSame(first, FxTestSupport.callOnFx(pane::source));
        assertEquals(
                groupsBefore + 1,
                (int) FxTestSupport.callOnFx(() -> editorArea.groupCount()),
                "beside, in a new group");
        assertSame(first, active(), "and the editor keeps the focus: the preview is beside, not in front");

        run("view.markdownPreviewToSide");
        assertEquals(1, previews().size(), "asked again for the same file: the open one is shown, not a second");

        // Another Markdown file becomes active: the preview follows it. A plain one: it stays.
        FxTestSupport.runOnFx(() -> editorArea.select(tabOf(first)));
        EditorBuffer second = open("# Second\n", "markdown");
        FxTestSupport.drainFx();
        assertSame(second, FxTestSupport.callOnFx(pane::source));
        FxTestSupport.runOnFx(() -> editorArea.select(tabOf(plain)));
        FxTestSupport.drainFx();
        assertSame(second, FxTestSupport.callOnFx(pane::source));

        // Locked, it stays on its file whatever becomes active.
        run("view.toggleMarkdownPreviewLock");
        assertTrue(FxTestSupport.callOnFx(pane::isLocked));
        assertEquals(tr("status.markdown.previewLocked"), SaveDecisionsFxTest.lastMessage(fx));
        FxTestSupport.runOnFx(() -> editorArea.select(tabOf(first)));
        FxTestSupport.drainFx();
        assertSame(second, FxTestSupport.callOnFx(pane::source));
        run("view.toggleMarkdownPreviewLock");
        assertFalse(FxTestSupport.callOnFx(pane::isLocked));

        // Closing its tab lets go of the source and of the preview.
        Tab previewTab = FxTestSupport.callOnFx(() -> tabOf(pane));
        assertNotNull(previewTab);
        FxTestSupport.runOnFx(() -> editorArea.remove(previewTab));
        FxTestSupport.drainFx();
        assertTrue(previews().isEmpty());
        run("view.toggleMarkdownPreviewLock");
        assertEquals(tr("status.markdown.noSidePreview"), SaveDecisionsFxTest.lastMessage(fx));

        // In a tab of the same group: the preview is the selected tab.
        FxTestSupport.runOnFx(() -> editorArea.select(tabOf(first)));
        run("view.markdownPreviewTab");
        assertEquals(1, previews().size());
        assertSame(first, FxTestSupport.callOnFx(previews().get(0)::source));
        assertTrue(FxTestSupport.callOnFx(() -> editorArea.selectedTab().getUserData() instanceof MarkdownPreviewPane));
    }

    private Tab tabOf(Object content) {
        for (Tab tab : editorArea.tabs()) {
            if (tab.getUserData() == content) {
                return tab;
            }
        }
        return null;
    }
}
