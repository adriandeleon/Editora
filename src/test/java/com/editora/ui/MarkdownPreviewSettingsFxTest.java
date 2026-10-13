package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.editor.PreviewPrefs;
import com.editora.editor.PreviewScrollSync;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The preview's settings reach the previews: view mode of a newly opened file, font, width, sync, front matter. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkdownPreviewSettingsFxTest {

    private FxWindowFixture fx;
    private PreviewCoordinator previews;
    private ConfigManager config;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        previews = FxTestSupport.field(fx.controller, "previews");
        config = FxTestSupport.field(fx.controller, "config");
    }

    @AfterAll
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            PreviewPrefs.set(0, 0, false);
            PreviewScrollSync.setEnabled(true);
        });
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void theSettingNamesAViewModeAndAnythingElseIsTheEditor() {
        assertEquals(MarkdownViewMode.EDITOR, PreviewCoordinator.defaultViewMode("editor"));
        assertEquals(MarkdownViewMode.SPLIT, PreviewCoordinator.defaultViewMode("split"));
        assertEquals(MarkdownViewMode.PREVIEW, PreviewCoordinator.defaultViewMode("preview"));
        assertEquals(MarkdownViewMode.SPLIT, PreviewCoordinator.defaultViewMode(" Split "));
        assertEquals(MarkdownViewMode.EDITOR, PreviewCoordinator.defaultViewMode("sideways"));
        assertEquals(MarkdownViewMode.EDITOR, PreviewCoordinator.defaultViewMode(""));
        assertEquals(MarkdownViewMode.EDITOR, PreviewCoordinator.defaultViewMode(null));
    }

    private EditorBuffer buffer(Path file, String language) {
        EditorBuffer b = new EditorBuffer();
        b.setContent("# Title\n\nBody.\n");
        b.setPath(file);
        if (language != null) {
            b.setLanguageOverride(language);
        }
        return b;
    }

    @Test
    void aMarkdownFileWithNoRememberedModeOpensInTheDefaultOne(@TempDir Path dir) throws Exception {
        Path fresh = Files.writeString(dir.resolve("fresh.md"), "# Title\n");
        Path known = Files.writeString(dir.resolve("known.md"), "# Title\n");
        Path other = Files.writeString(dir.resolve("notes.txt"), "plain\n");
        FxTestSupport.runOnFx(() -> {
            Settings s = config.getSettings();
            String before = s.getMarkdownDefaultViewMode();
            try {
                EditorBuffer first = buffer(fresh, "markdown");
                previews.restoreMarkdownMode(first);
                assertEquals(MarkdownViewMode.EDITOR, first.getMarkdownViewMode(), "the default default");

                s.setMarkdownDefaultViewMode("split");
                EditorBuffer second = buffer(fresh, "markdown");
                previews.restoreMarkdownMode(second);
                assertEquals(MarkdownViewMode.SPLIT, second.getMarkdownViewMode());

                // A file whose mode was chosen before keeps that choice.
                config.getWorkspaceState().getMarkdownViewModes().put(known.toString(), "PREVIEW");
                EditorBuffer third = buffer(known, "markdown");
                previews.restoreMarkdownMode(third);
                assertEquals(MarkdownViewMode.PREVIEW, third.getMarkdownViewMode());
                config.getWorkspaceState().getMarkdownViewModes().put(known.toString(), "EDITOR");
                EditorBuffer fourth = buffer(known, "markdown");
                previews.restoreMarkdownMode(fourth);
                assertEquals(MarkdownViewMode.EDITOR, fourth.getMarkdownViewMode(), "even when it is the editor");

                // It is a Markdown setting: a plain file is not touched.
                EditorBuffer plain = buffer(other, null);
                previews.restoreMarkdownMode(plain);
                assertEquals(MarkdownViewMode.EDITOR, plain.getMarkdownViewMode());
                for (EditorBuffer b : new EditorBuffer[] {first, second, third, fourth, plain}) {
                    b.dispose();
                }
            } finally {
                s.setMarkdownDefaultViewMode(before);
                config.getWorkspaceState().getMarkdownViewModes().remove(known.toString());
            }
        });
    }

    @Test
    void fontWidthFrontMatterAndScrollSyncAreTakenFromTheSettings() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Settings s = config.getSettings();
            previews.applyPreviewSettings();
            assertEquals(15, PreviewPrefs.baseFont(), "0 in the settings is the built-in size");
            assertEquals(860, PreviewPrefs.contentWidth());
            assertFalse(PreviewPrefs.hideFrontMatter());
            assertTrue(PreviewScrollSync.enabled());

            s.setMarkdownPreviewFontSize(18);
            s.setMarkdownPreviewWidth(700);
            s.setMarkdownHideFrontMatter(true);
            s.setMarkdownScrollSync(false);
            try {
                previews.applyPreviewSettings();
                assertEquals(18, PreviewPrefs.baseFont());
                assertEquals(700, PreviewPrefs.contentWidth());
                assertTrue(PreviewPrefs.hideFrontMatter());
                assertFalse(PreviewScrollSync.enabled());
            } finally {
                s.setMarkdownPreviewFontSize(0);
                s.setMarkdownPreviewWidth(0);
                s.setMarkdownHideFrontMatter(false);
                s.setMarkdownScrollSync(true);
                previews.applyPreviewSettings();
            }
            assertEquals(15, PreviewPrefs.baseFont());
            assertTrue(PreviewScrollSync.enabled());
        });
    }
}
