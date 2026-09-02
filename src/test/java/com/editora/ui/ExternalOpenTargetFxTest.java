package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A file the <b>OS</b> hands us opens exactly as the same string on the command line does.
 *
 * <p>macOS delivers a launcher argument through the {@code openFiles} Apple Event <em>as well as</em> on
 * argv, and the two used to be parsed differently: argv understood {@code Foo.java:42}, the event took the
 * whole thing as a filename. Launching {@code Editora foo.java:42} therefore opened the file <em>and</em>
 * reported "Failed to open: foo.java:42" for a file that had just opened — a failure message about a success,
 * which is the kind of thing a user reasonably reads as the editor being broken.
 *
 * <p>This drives {@code MainController.openExternalFiles}, which is where the event lands, and pins the half
 * that was missing: it took a list of plain paths and so could not express a line at all. The other half —
 * that the delivered string parses into a file and a line, existence deciding first because a colon is legal
 * in a macOS filename — is pinned in {@code AppArgsTest}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalOpenTargetFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void anOsDeliveredPathWithALineOpensThatFileAtThatLine() throws Exception {
        Path dir = Files.createTempDirectory("editora-external-open");
        Path file = Files.writeString(dir.resolve("notes.txt"), "one\ntwo\nthree\nfour\n");

        FxWindowFixture fx = FxWindowFixture.create();
        try {
            AtomicReference<String> title = new AtomicReference<>();
            AtomicInteger caretLine = new AtomicInteger(-1);
            FxTestSupport.runOnFx(() -> {
                // What App.externalTarget makes of the Apple Event's string.
                fx.controller.openExternalFiles(List.of(new MainController.OpenTarget(file, 3, 0)));
            });
            // The jump is queued behind the asynchronous file load, as it is for a command-line target.
            waitUntil(() -> FxTestSupport.callOnFx(() -> {
                EditorBuffer buffer =
                        (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                return buffer != null && buffer.getArea().getCurrentParagraph() + 1 == 3;
            }));
            FxTestSupport.runOnFx(() -> {
                TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
                Tab selected = tabs.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getUserData() instanceof EditorBuffer buffer) {
                    title.set(buffer.title());
                    caretLine.set(buffer.getArea().getCurrentParagraph() + 1);
                }
            });

            assertEquals("notes.txt", title.get(), "the file itself opens, not a name with the suffix on it");
            assertEquals(3, caretLine.get(), "and the caret lands on the line the OS-delivered string named");
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aPlainOsDeliveredPathStillJustOpens() throws Exception {
        Path dir = Files.createTempDirectory("editora-external-open-plain");
        Path file = Files.writeString(dir.resolve("plain.txt"), "only\n");

        FxWindowFixture fx = FxWindowFixture.create();
        try {
            AtomicReference<String> title = new AtomicReference<>();
            FxTestSupport.runOnFx(
                    () -> fx.controller.openExternalFiles(List.of(new MainController.OpenTarget(file, 0, 0))));
            FxTestSupport.runOnFx(() -> {});
            FxTestSupport.runOnFx(() -> {});
            FxTestSupport.runOnFx(() -> {
                TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
                Tab selected = tabs.getSelectionModel().getSelectedItem();
                assertNotNull(selected);
                if (selected.getUserData() instanceof EditorBuffer buffer) {
                    title.set(buffer.title());
                }
            });
            assertEquals("plain.txt", title.get());
        } finally {
            fx.dispose();
        }
    }

    private static void waitUntil(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (condition.call()) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
