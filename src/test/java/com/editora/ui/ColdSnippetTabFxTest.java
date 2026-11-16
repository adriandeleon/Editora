package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.editor.SnippetLoading;
import com.editora.snippet.SnippetManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Tab pressed in a language whose snippet files are still being read off the FX thread (PERF-01, Pauta 6.0.1)
 * is not lost: it waits for them and expands the trigger when the buffer is as it was; changed meanwhile, it is
 * dropped; with no trigger to expand, it is the Tab it would have been.
 */
@Tag("fx")
class ColdSnippetTabFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A buffer wired as {@code SnippetCoordinator.wireBuffer} wires one, its manager's file work held. */
    private record Rig(EditorBuffer buffer, SnippetManager manager, List<Runnable> held) {}

    private static Rig rig(Path config, String language, String content) throws Exception {
        SnippetManager manager = new SnippetManager(new ConfigManager(config));
        List<Runnable> held = new CopyOnWriteArrayList<>();
        manager.fileWork = held::add; // the files are read when the test says
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride(language);
            b.setSnippetProvider(manager::byTabTrigger);
            b.setSnippetLoading(new SnippetLoading() {
                @Override
                public boolean loaded(String lang) {
                    return manager.isLoaded(lang);
                }

                @Override
                public void whenLoaded(String lang, Runnable then) {
                    manager.whenLoaded(lang, then);
                }
            });
            b.setContent(content);
            b.getNode();
            b.getArea().moveTo(content.length());
            return b;
        });
        return new Rig(buffer, manager, held);
    }

    private static void tab(EditorBuffer b) {
        b.getArea().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
    }

    /** Runs the held file work (on this thread, off the FX thread) and lets what it hands back land. */
    private static void land(Rig rig) throws Exception {
        while (!rig.held().isEmpty()) {
            rig.held().remove(0).run();
        }
        FxTestSupport.drainFx();
    }

    @Test
    void aTabBeforeTheSnippetsAreLoadedExpandsWhenTheyLand(@TempDir Path config) throws Exception {
        Rig rig = rig(config, "shell", "#!/bin/sh\nif");
        FxTestSupport.runOnFx(() -> {
            assertFalse(rig.manager().isLoaded("shell"));
            tab(rig.buffer());
            assertEquals("#!/bin/sh\nif", rig.buffer().getArea().getText(), "held: nothing yet, not a tab character");
        });
        assertFalse(rig.held().isEmpty(), "the load was asked for");
        land(rig);
        FxTestSupport.runOnFx(() -> {
            assertTrue(rig.manager().isLoaded("shell"));
            assertEquals(
                    "#!/bin/sh\nif [ condition ]; then\n    \nfi",
                    rig.buffer().getArea().getText());
        });
    }

    @Test
    void aTabWhoseBufferChangedBeforeTheSnippetsLandedIsDropped(@TempDir Path config) throws Exception {
        Rig rig = rig(config, "shell", "#!/bin/sh\nif");
        FxTestSupport.runOnFx(() -> {
            tab(rig.buffer());
            rig.buffer().typeString("x"); // typed on before the load landed
        });
        land(rig);
        FxTestSupport.runOnFx(() -> assertEquals(
                "#!/bin/sh\nifx", rig.buffer().getArea().getText(), "not expanded, and no Tab put somewhere else"));
    }

    @Test
    void aTabWithNoTriggerToExpandIsTheTabItWouldHaveBeen(@TempDir Path config) throws Exception {
        Rig rig = rig(config, "shell", "zzqnotatrigger");
        FxTestSupport.runOnFx(() -> tab(rig.buffer()));
        land(rig);
        FxTestSupport.runOnFx(() -> {
            String text = rig.buffer().getArea().getText();
            assertTrue(text.startsWith("zzqnotatrigger") && text.length() > "zzqnotatrigger".length(), text);
            assertTrue(text.substring("zzqnotatrigger".length()).isBlank(), "an indent was inserted: " + text);
        });
        // Once loaded, a Tab decides at once.
        FxTestSupport.runOnFx(() -> {
            rig.buffer().getArea().replaceText("#!/bin/sh\nif");
            rig.buffer().getArea().moveTo(rig.buffer().getArea().getLength());
            tab(rig.buffer());
            assertEquals(
                    "#!/bin/sh\nif [ condition ]; then\n    \nfi",
                    rig.buffer().getArea().getText());
        });
    }
}
