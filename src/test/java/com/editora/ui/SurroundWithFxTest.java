package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Surround With is titled for what it does, takes whole lines, and Esc gives the caret back (third review ET2). */
@Tag("fx")
class SurroundWithFxTest {

    @TempDir
    Path dir;

    @Test
    void surroundWithIsTitledTakesWholeLinesAndEscPutsTheCaretBack() throws Exception {
        FxTestSupport.bootToolkit();
        Path file = Files.writeString(
                dir.resolve("A.java"),
                "class A {\n    void go() {\n        int a = 1;\n        int b = 2;\n    }\n}\n");
        try (FxWindowFixture fx = FxWindowFixture.create()) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(file, 2));
            EditorBuffer[] b = new EditorBuffer[1];
            Await.holdsWithin(
                    () -> onFx(() ->
                            (b[0] = c.activeBuffer()) != null && !b[0].isLoading() && file.equals(b[0].getPath())),
                    Duration.ofSeconds(5));
            EditorBuffer buffer = b[0];
            String text = FxTestSupport.callOnFx(buffer::getContent);
            int aStart = text.indexOf("int a");
            int bStart = text.indexOf("int b");
            CommandRegistry registry = FxTestSupport.field(c, "registry");

            // A selection from inside one line into the next: the whole lines it touches (AT5).
            FxTestSupport.runOnFx(() -> buffer.getFocusedArea().selectRange(aStart + 2, bStart + 3));
            FxTestSupport.runOnFx(() -> registry.run("snippets.surround"));
            FxTestSupport.drainFx();
            String selected =
                    FxTestSupport.callOnFx(() -> buffer.getFocusedArea().getSelectedText());
            assertEquals("        int a = 1;\n        int b = 2;\n", selected);
            QuickOpen<?> palette = FxTestSupport.field(FxTestSupport.field(c, "navigation"), "snippetPalette");
            Label title = FxTestSupport.field(palette, "header");
            assertEquals(tr("nav.snippets.surroundTitle"), FxTestSupport.callOnFx(title::getText));

            FxTestSupport.runOnFx(palette::hide); // Esc
            FxTestSupport.drainFx();
            int[] after = FxTestSupport.callOnFx(() -> new int[] {
                buffer.getFocusedArea().getAnchor(), buffer.getFocusedArea().getCaretPosition()
            });
            assertEquals(aStart + 2, after[0], "the selection the user had, not the widened one");
            assertEquals(bStart + 3, after[1]);

            FxTestSupport.runOnFx(() -> buffer.getFocusedArea().moveTo(aStart));
            FxTestSupport.runOnFx(() -> registry.run("snippets.insert"));
            FxTestSupport.drainFx();
            assertEquals(
                    tr("nav.snippets.title"), FxTestSupport.callOnFx(title::getText), "Insert Snippet keeps its own");
            FxTestSupport.runOnFx(palette::hide);
        }
    }

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
