package com.editora.editor;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import com.editora.editor.FoldRegions.Region;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class FoldPreviewFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void previewReusesEditorStyleSpansAndLiveThemeStylesheets() throws Exception {
        runOnFx(() -> {
            CodeArea area = new CodeArea("if (ready) {\n  return value;\n}");
            StyleSpansBuilder<Collection<String>> styles = new StyleSpansBuilder<>();
            styles.add(List.of("keyword"), 2);
            styles.add(List.of(), area.getLength() - 2);
            area.setStyleSpans(0, styles.create());
            Scene scene = new Scene(area);
            scene.getStylesheets().add("test-editor-theme.css");

            FoldManager manager = new FoldManager(area);
            VBox preview = (VBox) manager.foldPreviewGraphic(new Region(0, 2));

            assertEquals(List.of("test-editor-theme.css"), preview.getStylesheets());
            HBox firstLine = (HBox) preview.getChildren().get(0);
            Text keyword = (Text) firstLine.getChildren().get(0);
            assertEquals("if", keyword.getText());
            assertTrue(keyword.getStyleClass().containsAll(List.of("text", "keyword")));
            assertEquals(3, preview.getChildren().size());
        });
    }

    private static void runOnFx(Runnable task) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
