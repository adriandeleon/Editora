package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end guard for the background disk-preparation path used by expensive initial file opens. */
@Tag("fx")
class InitialFileLoadFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void smallLocalOpenStartsWithAShellAndEventuallyInstallsTheCompleteDocument() throws Exception {
        Path dir = Files.createTempDirectory("editora-async-open");
        Path file = dir.resolve("small.txt");
        String content = "small local file\n".repeat(8);
        Files.writeString(file, content);
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            AtomicReference<EditorBuffer> opened = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(
                        FxTestSupport.field(fx.controller, "fileWorkflows"),
                        "openPath",
                        new Class<?>[] {Path.class},
                        file);
                EditorBuffer shell =
                        (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                opened.set(shell);
                assertEquals("", shell.getContent(), "the disk result must not land during the initiating FX task");
                assertTrue(
                        ((java.util.Set<?>) FxTestSupport.field(
                                        FxTestSupport.field(fx.controller, "fileWorkflows"), "loadingBuffers"))
                                .contains(shell),
                        "even a small local text file should start as a loading shell");
                assertTrue(tabHeader(fx.controller, shell).getStyleClass().contains("read-only"));
            });

            EditorBuffer buffer = opened.get();
            assertNotNull(buffer, "the tab shell should be visible immediately");
            assertTrue(waitUntil(() -> content.equals(buffer.getContent())), "background load did not complete");
            assertEquals(content, FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(!FxTestSupport.callOnFx(buffer::isDirty), "a freshly loaded document must stay clean");
            assertTrue(FxTestSupport.callOnFx(buffer::isEditable), "the completed small-file load is editable");
            FxTestSupport.runOnFx(() -> {
                HBox header = tabHeader(fx.controller, buffer);
                TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
                tabs.getScene().getRoot().applyCss();
                tabs.getScene().getRoot().layout();
                assertTrue(
                        !header.getStyleClass().contains("read-only"),
                        "the rebuilt header must drop the loading shell's temporary state");
                Label title = header.getChildren().stream()
                        .filter(Label.class::isInstance)
                        .map(Label.class::cast)
                        .filter(label -> label.getStyleClass().contains("tab-title"))
                        .findFirst()
                        .orElseThrow();
                assertTrue(
                        !title.getFont()
                                .getStyle()
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains("italic"),
                        "the completed editable tab must render upright: " + title.getFont());
            });
            StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
            Label readOnly = FxTestSupport.field(statusBar, "readOnly");
            assertEquals(
                    com.editora.i18n.Messages.tr("statusbar.editable"),
                    FxTestSupport.callOnFx(readOnly::getText),
                    "the status segment must leave the loading shell's temporary read-only state");
        } finally {
            fx.dispose();
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static HBox tabHeader(MainController controller, EditorBuffer buffer) {
        TabPane tabs = FxTestSupport.field(controller, "tabPane");
        Tab tab = tabs.getTabs().stream()
                .filter(candidate -> candidate.getUserData() == buffer)
                .findFirst()
                .orElseThrow();
        return (HBox) tab.getGraphic();
    }

    @Test
    void loadingShellDefersEditorConfigAndAppliesThePreparedResult() throws Exception {
        Path dir = Files.createTempDirectory("editora-async-editorconfig");
        Path config = dir.resolve(".editorconfig");
        Path file = dir.resolve("sample.txt");
        Files.writeString(config, "root = true\n\n[*.txt]\nindent_style = space\nindent_size = 7\n");
        Files.writeString(file, "configured\n");
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            AtomicReference<EditorBuffer> opened = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(
                        FxTestSupport.field(fx.controller, "fileWorkflows"),
                        "openPath",
                        new Class<?>[] {Path.class},
                        file);
                EditorBuffer shell =
                        (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                opened.set(shell);
                assertTrue(shell.getEditorConfigProps().isEmpty(), "the FX-thread shell must not resolve EditorConfig");
            });

            EditorBuffer buffer = opened.get();
            assertTrue(waitUntil(() -> "configured\n".equals(buffer.getContent())), "background load did not complete");
            assertEquals(7, FxTestSupport.callOnFx(buffer::getTabSize));
            assertEquals(
                    7,
                    FxTestSupport.callOnFx(() -> buffer.getEditorConfigProps().indentSize()),
                    "the background-resolved EditorConfig result should be reused");
        } finally {
            fx.dispose();
            Files.deleteIfExists(file);
            Files.deleteIfExists(config);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void pathologicalLongLineUsesSafeProfileBeforeInsertion() throws Exception {
        Path dir = Files.createTempDirectory("editora-long-line-open");
        Path file = dir.resolve("minified.js");
        // The former async-load fixture accidentally exposed this exact shape: one ~320 KiB paragraph
        // monopolized the Linux FX thread and caused cascading suite timeouts. Keep it as an explicit guard.
        String content = "x".repeat(FileWorkflowCoordinator.LONG_LINE_FILE_CHARS * 5);
        Files.writeString(file, content);
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "fileWorkflows"),
                    "openPath",
                    new Class<?>[] {Path.class},
                    file));
            EditorBuffer buffer = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
            assertTrue(waitUntil(() -> content.equals(buffer.getContent())), "background load did not complete");
            assertTrue(
                    FxTestSupport.callOnFx(buffer::isLargeFile), "long-line safety mode should disable heavy features");
            assertTrue(
                    !FxTestSupport.callOnFx(() -> buffer.getArea().isWrapText()),
                    "safe profile must force wrapping off");
            assertTrue(
                    FxTestSupport.callOnFx(() -> buffer.getArea()
                                    .getStyleSpans(0, content.length())
                                    .getSpanCount())
                            > 1,
                    "the giant paragraph should be split into bounded, visually identical text nodes");
        } finally {
            fx.dispose();
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
