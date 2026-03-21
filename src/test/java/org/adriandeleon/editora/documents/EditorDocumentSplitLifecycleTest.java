package org.adriandeleon.editora.documents;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import org.adriandeleon.editora.languages.PlainTextLanguageService;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorDocumentSplitLifecycleTest {
    private static final AtomicBoolean JAVA_FX_STARTED = new AtomicBoolean();

    @BeforeAll
    static void startJavaFx() throws Exception {
        Assumptions.assumeTrue(canStartJavaFxToolkit(),
                "Skipping JavaFX split lifecycle test because no GUI display is available");
        if (JAVA_FX_STARTED.compareAndSet(false, true)) {
            CountDownLatch startupLatch = new CountDownLatch(1);
            try {
                Platform.startup(startupLatch::countDown);
                assertTrue(startupLatch.await(5, TimeUnit.SECONDS), "JavaFX toolkit did not start");
            } catch (IllegalStateException exception) {
                if (!exception.getMessage().contains("Toolkit already initialized")) {
                    throw exception;
                }
            }
        }
    }

    @Test
    void splitUnsplitAndSplitAgainKeepsTextAndMiniMapsStable() throws Exception {
        AtomicReference<EditorDocument> documentReference = new AtomicReference<>();
        AtomicReference<CodeArea> firstSecondaryReference = new AtomicReference<>();
        AtomicReference<CodeArea> secondSecondaryReference = new AtomicReference<>();

        runOnFxThread(() -> {
            CodeArea primary = new CodeArea();
            primary.replaceText("alpha\nbeta\ngamma");
            EditorDocument document = new EditorDocument(
                    "Untitled 1",
                    primary,
                    PlainTextLanguageService.INSTANCE,
                    true
            );
            documentReference.set(document);

            assertTrue(document.splitRight(), "First split should create a secondary editor");
            CodeArea firstSecondary = document.secondaryCodeArea().orElseThrow();
            firstSecondaryReference.set(firstSecondary);
            firstSecondary.replaceText("first split text");
        });
        drainFxQueue();

        EditorDocument document = documentReference.get();
        assertNotNull(document);
        assertEquals("first split text", document.getCodeArea().getText());
        assertEquals("first split text", firstSecondaryReference.get().getText());

        runOnFxThread(() -> {
            assertRightSidedMiniMaps(document);
            assertTrue(document.unsplit(), "Unsplit should close the secondary editor");
            assertFalse(document.hasSplitView(), "Document should no longer be split");
            document.getCodeArea().replaceText("after unsplit");
            assertTrue(document.splitRight(), "Second split should create a fresh secondary editor");
            CodeArea secondSecondary = document.secondaryCodeArea().orElseThrow();
            secondSecondaryReference.set(secondSecondary);
            secondSecondary.replaceText("second split text");
        });
        drainFxQueue();

        assertNotNull(secondSecondaryReference.get());
        assertNotSame(firstSecondaryReference.get(), secondSecondaryReference.get(), "A new secondary editor should be created after unsplitting");
        assertEquals("second split text", document.getCodeArea().getText());
        assertEquals("second split text", secondSecondaryReference.get().getText());

        runOnFxThread(() -> assertRightSidedMiniMaps(document));
    }

    private static void assertRightSidedMiniMaps(EditorDocument document) {
        Node tabContent = document.getTab().getContent();
        assertNotNull(tabContent);
        StackPane editorHost = findDescendantOfType(tabContent, StackPane.class).stream()
                .filter(node -> node.getStyleClass().contains("editor-document-editor-host"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Editor host not found"));
        SplitPane splitPane = findDescendantOfType(editorHost, SplitPane.class).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Split pane not found"));
        assertEquals(2, splitPane.getItems().size(), "Split view should contain two panes");
        for (Node item : splitPane.getItems()) {
            HBox editorPane = assertInstanceOf(HBox.class, item, "Each split item should be an HBox editor pane");
            assertEquals(2, editorPane.getChildren().size(), "Each editor pane should contain editor and minimap");
            Node rightMostChild = editorPane.getChildren().get(1);
            assertTrue(rightMostChild.getStyleClass().contains("editor-mini-map"), "MiniMap should be on the right side of the split pane");
        }
    }

    private static <T extends Node> List<T> findDescendantOfType(Node root, Class<T> type) {
        java.util.ArrayList<T> matches = new java.util.ArrayList<>();
        if (type.isInstance(root)) {
            matches.add(type.cast(root));
        }
        if (root instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                matches.addAll(findDescendantOfType(child, type));
            }
        }
        return List.copyOf(matches);
    }

    private static void drainFxQueue() throws Exception {
        runOnFxThread(() -> {
        });
        runOnFxThread(() -> {
        });
    }

    private static void runOnFxThread(ThrowingRunnable action) throws Exception {
        CountDownLatch finishedLatch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                finishedLatch.countDown();
            }
        });
        assertTrue(finishedLatch.await(5, TimeUnit.SECONDS), "JavaFX action timed out");
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) {
                throw exception;
            }
            if (failure.get() instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(failure.get());
        }
    }

    private static boolean canStartJavaFxToolkit() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("linux")) {
            return true;
        }

        if (Boolean.getBoolean("java.awt.headless")) {
            return false;
        }

        Map<String, String> env = System.getenv();
        return hasText(env.get("DISPLAY"))
                || hasText(env.get("WAYLAND_DISPLAY"))
                || hasText(env.get("MIR_SOCKET"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

