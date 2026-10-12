package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.CheckBox;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Word wrap is a preference per kind of file: Markdown and plain text wrap by default, code does not, and
 * <em>Toggle Word Wrap</em> flips the one that governs the file in front of the user.
 */
@Tag("fx")
class WordWrapByLanguageFxTest {

    @TempDir
    static Path work;

    private static FxWindowFixture fx;
    private static FileWorkflowCoordinator workflows;
    private static CommandRegistry registry;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static EditorBuffer open(String name, String text) throws Exception {
        Path file = Files.writeString(work.toAbsolutePath().normalize().resolve(name), text);
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (true) {
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                EditorBuffer a = (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                return a != null && file.equals(a.getPath()) && !a.isLoading() ? a : null;
            });
            if (b != null) {
                FxTestSupport.drainFx();
                return b;
            }
            assertTrue(System.nanoTime() < deadline, "did not open " + file);
            Thread.sleep(10);
        }
    }

    private static boolean wraps(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().isWrapText());
    }

    private static String status() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.<javafx.scene.control.Label>field(
                        FxTestSupport.<StatusBar>field(fx.controller, "statusBar"), "echo")
                .getText());
    }

    @Test
    void proseWrapsCodeDoesNotAndTheToggleActsOnTheKindInFront() throws Exception {
        Settings s = fx.shared.getSettings();
        EditorBuffer md = open("notes.md", "# Notes\n\n" + "word ".repeat(200) + "\n");
        EditorBuffer txt = open("notes.txt", "word ".repeat(200) + "\n");
        EditorBuffer java = open("Main.java", "class Main { String s = \"" + "x".repeat(400) + "\"; }\n");
        assertTrue(wraps(md), "Markdown wraps by default");
        assertTrue(wraps(txt), "plain text wraps by default");
        assertFalse(wraps(java), "code does not");

        // In front: the Java file. The toggle is about code and leaves prose alone.
        FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap"));
        assertTrue(s.isWordWrap() && s.isWordWrapProse());
        assertTrue(wraps(java) && wraps(md) && wraps(txt));
        assertEquals(tr("status.toggle.wordWrap", tr("common.on")), status());
        FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap"));
        assertFalse(wraps(java));
        assertTrue(wraps(md), "turning code wrap off again did not touch Markdown");

        // In front: the Markdown file. Now it is the prose preference.
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(md.getPath(), 0));
        FxTestSupport.drainFx();
        Thread.sleep(200);
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap"));
        assertFalse(s.isWordWrapProse());
        assertFalse(s.isWordWrap());
        assertFalse(wraps(md) || wraps(txt), "both prose buffers follow");
        assertEquals(tr("status.toggle.wordWrapProse", tr("common.off")), status());
        FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap"));
        assertTrue(wraps(md) && wraps(txt));
        assertFalse(wraps(java));
    }

    /**
     * Wrapping is on by default for Markdown, and a token with no whitespace in it — a pasted data: image —
     * takes seconds to tens of seconds to lay out wrapped. Such a file opens unwrapped; the toggle opts in.
     */
    @Test
    void aFileWithAGiantUnbrokenTokenOpensUnwrapped() throws Exception {
        fx.shared.getSettings().setWordWrapProse(true);
        long start = System.nanoTime();
        EditorBuffer md = open("shot.md", "# Shot\n\n![s](data:image/png;base64," + "A".repeat(30_000) + ")\n\ntext\n");
        assertFalse(wraps(md));
        assertTrue(FxTestSupport.callOnFx(() -> md.wrap().isSuppressed()));
        assertTrue(FxTestSupport.callOnFx(() -> md.wrap().preferred()), "the preference itself is untouched");
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "and it opened without the wrapped layout");
        EditorBuffer plain = open("plain.md", "# Plain\n\n" + "word ".repeat(400) + "\n");
        assertTrue(wraps(plain), "other Markdown files still wrap");
    }

    @Test
    void aBufferFollowsItsLanguageWhenThatChanges() throws Exception {
        EditorBuffer b = open("data.json", "{\"k\": \"" + "v".repeat(400) + "\"}\n");
        assertFalse(wraps(b));
        FxTestSupport.runOnFx(() -> b.setLanguageOverride("markdown"));
        assertTrue(wraps(b), "set to Markdown: it is prose now");
        FxTestSupport.runOnFx(() -> b.setLanguageOverride("json"));
        assertFalse(wraps(b));
    }

    @Test
    void theSettingsWindowHasASwitchForEachAndFollowsThePalette() throws Exception {
        SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<javafx.stage.Stage>field(fx.controller, "stage")));
        FxTestSupport.drainFx();
        CheckBox code = FxTestSupport.field(window, "wordWrapCheck");
        CheckBox prose = FxTestSupport.field(window, "wordWrapProseCheck");
        Settings s = fx.shared.getSettings();
        assertEquals(s.isWordWrap(), FxTestSupport.callOnFx(code::isSelected));
        assertEquals(s.isWordWrapProse(), FxTestSupport.callOnFx(prose::isSelected));
        boolean before = s.isWordWrapProse();
        FxTestSupport.runOnFx(() -> prose.setSelected(!before));
        assertEquals(!before, s.isWordWrapProse(), "the switch writes the prose preference");
        FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap")); // a prose or a code file is in front
        assertEquals(s.isWordWrap(), FxTestSupport.callOnFx(code::isSelected), "the window follows the palette");
        assertEquals(s.isWordWrapProse(), FxTestSupport.callOnFx(prose::isSelected));
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<javafx.stage.Stage>field(window, "stage").hide());
    }
}
