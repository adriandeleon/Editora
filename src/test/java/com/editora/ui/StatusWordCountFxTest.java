package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The status bar counts the words of a prose buffer, or of its selection. */
@Tag("fx")
class StatusWordCountFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer(String name, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(Path.of("/tmp/words", name).toAbsolutePath());
            b.setContent(text);
            return b;
        });
    }

    @Test
    void proseBuffersShowWordsAndTheSelectionsWords() throws Exception {
        Settings settings = new Settings();
        EditorBuffer md = buffer("a.md", "# Title\n\nOne two three four.\n");
        EditorBuffer txt = buffer("a.txt", "alpha beta\n");
        EditorBuffer java = buffer("A.java", "class A { int words; }\n");
        EditorBuffer[] active = {md};
        StatusBar bar =
                FxTestSupport.callOnFx(() -> new StatusBar(() -> active[0], new CommandRegistry(), () -> settings));
        Label label = bar.wordCount.label();
        FxTestSupport.runOnFx(() -> {
            bar.wordCount.counter = Runnable::run;
            bar.attach(md);
        });
        assertTrue(FxTestSupport.callOnFx(label::isManaged));
        assertEquals("5 words", FxTestSupport.callOnFx(label::getText));
        assertTrue(FxTestSupport.callOnFx(() -> label.getTooltip().getText()).contains("1"));

        // A selection is counted after its pause, not per caret move; clearing it shows the document again.
        FxTestSupport.runOnFx(() -> {
            md.getArea().selectRange(9, 16); // "One two"
            bar.wordCount.selection(md.getArea());
        });
        assertEquals("5 words", FxTestSupport.callOnFx(label::getText), "not counted on the caret path");
        FxTestSupport.runOnFx(bar.wordCount::countSelection);
        assertEquals("2 words selected", FxTestSupport.callOnFx(label::getText));
        FxTestSupport.runOnFx(() -> {
            md.getArea().deselect();
            bar.wordCount.selection(md.getArea());
        });
        assertEquals("5 words", FxTestSupport.callOnFx(label::getText));

        // Other buffers: plain text counts, code does not, no buffer shows nothing.
        FxTestSupport.runOnFx(() -> {
            active[0] = txt;
            bar.attach(txt);
        });
        assertEquals("2 words", FxTestSupport.callOnFx(label::getText));
        FxTestSupport.runOnFx(() -> {
            active[0] = java;
            bar.attach(java);
        });
        assertFalse(FxTestSupport.callOnFx(label::isManaged));
        FxTestSupport.runOnFx(() -> {
            active[0] = md;
            bar.attach(md);
        });
        assertTrue(FxTestSupport.callOnFx(label::isManaged));
        FxTestSupport.runOnFx(() -> {
            active[0] = null;
            bar.attach(null);
        });
        assertFalse(FxTestSupport.callOnFx(label::isManaged));

        // The setting turns it off, and Simple mode hides it.
        FxTestSupport.runOnFx(() -> {
            active[0] = md;
            bar.attach(md);
            settings.setStatusBarWordCount(false);
            bar.refresh();
        });
        assertFalse(FxTestSupport.callOnFx(label::isManaged));
        FxTestSupport.runOnFx(() -> {
            settings.setStatusBarWordCount(true);
            bar.refresh();
        });
        assertTrue(FxTestSupport.callOnFx(label::isManaged));
        FxTestSupport.runOnFx(() -> bar.setSimpleMode(true));
        assertFalse(FxTestSupport.callOnFx(label::isManaged));
    }

    @Test
    void aLargeDocumentIsCountedOffTheFxThread() throws Exception {
        Settings settings = new Settings();
        String big = "word ".repeat(StatusWordCount.INLINE_CHARS / 5 + 10);
        EditorBuffer md = buffer("big.md", big);
        Runnable[] held = {null};
        // One FX turn for all three steps. The constructor starts a count of its own on a background thread;
        // had its result been delivered between two turns, the label would already be showing here.
        StatusBar bar = FxTestSupport.callOnFx(() -> {
            StatusBar created = new StatusBar(() -> md, new CommandRegistry(), () -> settings);
            created.wordCount.counter = task -> held[0] = task;
            created.attach(md);
            return created;
        });
        assertFalse(FxTestSupport.callOnFx(() -> bar.wordCount.label().isManaged()), "not counted inline");
        held[0].run();
        FxTestSupport.runOnFx(() -> {});
        assertEquals(
                java.text.MessageFormat.format("{0} words", StatusWordCount.INLINE_CHARS / 5 + 10),
                FxTestSupport.callOnFx(() -> bar.wordCount.label().getText()));
    }
}
