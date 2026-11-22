package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ctrl/Cmd-hover links in a real buffer: holding the shortcut key over a symbol the language server can resolve
 * underlines it and shows a hand, letting go takes both away, a word with no definition is asked about once, and an
 * answer that comes back after the mouse moved on is dropped.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(com.editora.ui.OffFxInPlace.class)
class DefinitionLinksFxTest {

    private static final boolean MAC =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    private static final String TEXT = "class A {\n    void run() { list.add(item); }\n}\n";

    private @Nullable EditorBuffer buffer;
    private @Nullable Stage stage;

    /** The probe's questions, as (line, column), and the answers it was told to hold back. */
    private final List<int[]> asked = new ArrayList<>();

    private final List<Consumer<Boolean>> held = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        onFx(() -> {
            if (buffer != null) {
                buffer.dispose();
            }
            if (stage != null) {
                stage.close();
            }
        });
    }

    @Test
    void holdingTheShortcutOverAResolvableSymbolUnderlinesItUntilTheKeyIsLetGo() throws Exception {
        open((line, column, found) -> found.accept(true));
        int list = TEXT.indexOf("list");
        onFx(() -> assertNull(buffer.definitionLinkOverlayForTest(), "no overlay until a link shows"));

        onFx(() -> move(list + 1, true));
        await(() -> links().shownForTest() != null);
        onFx(() -> {
            DefinitionLinks.Span span = links().shownForTest();
            assertNotNull(span);
            assertEquals(list, span.start());
            assertEquals(list + "list".length(), span.end(), "the whole identifier is the link");
            assertEquals(Cursor.HAND, buffer.getArea().getCursor());
            DefinitionLinkOverlay overlay = buffer.definitionLinkOverlayForTest();
            assertNotNull(overlay, "the first link attaches the overlay");
            assertTrue(overlay.showingForTest(buffer.getArea()));
            assertEquals(1, asked.size());
            assertEquals(1, asked.get(0)[0], "asked about the line the mouse is on");

            move(list + 3, true); // still on the same word: nothing new to ask
            assertEquals(1, asked.size());

            release();
            assertNull(links().shownForTest(), "letting go of the key ends the link");
            assertFalse(buffer.definitionLinkOverlayForTest().showingForTest(buffer.getArea()));
            assertNotEquals(Cursor.HAND, buffer.getArea().getCursor());
        });
    }

    @Test
    void withoutTheShortcutNothingIsAskedOrShown() throws Exception {
        open((line, column, found) -> found.accept(true));
        onFx(() -> {
            move(TEXT.indexOf("list") + 1, false);
            assertFalse(links().settlingForTest(), "no question is on its way");
            assertTrue(asked.isEmpty());
            assertNull(links().shownForTest());
        });
    }

    @Test
    void aWordWithNoDefinitionIsAskedAboutOnceAndNotUnderlined() throws Exception {
        open((line, column, found) -> found.accept(false));
        int item = TEXT.indexOf("item");
        onFx(() -> move(item, true));
        await(() -> asked.size() == 1);
        onFx(() -> {
            move(item + 2, true);
            assertFalse(links().settlingForTest(), "no second question is on its way");
            assertEquals(1, asked.size(), "the answer for the word is kept while the mouse stays on it");
            assertNull(links().shownForTest());
            assertNotEquals(Cursor.HAND, buffer.getArea().getCursor());
        });
    }

    @Test
    void anAnswerForAWordTheMouseHasLeftIsDropped() throws Exception {
        open((line, column, found) -> held.add(found));
        onFx(() -> move(TEXT.indexOf("list") + 1, true));
        await(() -> held.size() == 1);
        onFx(() -> {
            move(TEXT.indexOf("list") + 1, false); // the key let go before the server answered
            held.get(0).accept(true);
            assertNull(links().shownForTest());
        });
    }

    @Test
    void anHtmlLinkIsItsAttributeValue() throws Exception {
        String html = "<p><a href=\"about.html\">About</a></p>\n";
        AtomicReference<DefinitionLinks.@Nullable Span> answer = new AtomicReference<>();
        onFx(() -> {
            EditorBuffer b = new EditorBuffer();
            buffer = b;
            b.getArea().replaceText(html);
            b.setLanguageOverride("html");
            int inside = html.indexOf("about") + 2;
            b.probeDefinitionLink(b.getArea(), inside, answer::set);
        });
        DefinitionLinks.Span span = answer.get();
        assertNotNull(span);
        assertTrue(span.link());
        assertEquals("about.html", html.substring(span.start(), span.end()));
    }

    private void open(EditorBuffer.DefinitionProbe answers) throws Exception {
        FxToolkit.registerPrimaryStage();
        onFx(() -> {
            EditorBuffer b = new EditorBuffer();
            buffer = b;
            b.getArea().replaceText(TEXT);
            b.setLanguageOverride("java");
            b.setLspActive(true);
            b.setDefinitionProbe((line, column, found) -> {
                asked.add(new int[] {line, column});
                answers.probe(line, column, found);
            });
            Stage s = new Stage();
            stage = s;
            s.setScene(new Scene(b.getNode(), 700, 300));
            s.show();
            b.getArea().layout();
            assertTrue(b.isLspActive(), "the test needs a buffer a language server would serve");
        });
    }

    private DefinitionLinks links() {
        return buffer.definitionLinksForTest();
    }

    /** A mouse move over the middle of the character at {@code offset}, the shortcut key held or not. */
    private void move(int offset, boolean shortcut) {
        Bounds at = buffer.getArea()
                .getCharacterBoundsOnScreen(offset, offset + 1)
                .orElseThrow(() -> new AssertionError("the character must be on screen"));
        buffer.getArea()
                .fireEvent(new MouseEvent(
                        MouseEvent.MOUSE_MOVED,
                        0,
                        0,
                        at.getCenterX(),
                        at.getCenterY(),
                        MouseButton.NONE,
                        0,
                        false,
                        shortcut && !MAC,
                        false,
                        shortcut && MAC,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        null));
    }

    /** The shortcut key going up, heard by the scene the mouse's view is in. */
    private void release() {
        buffer.getArea()
                .fireEvent(new KeyEvent(
                        KeyEvent.KEY_RELEASED,
                        "",
                        "",
                        MAC ? KeyCode.META : KeyCode.CONTROL,
                        false,
                        false,
                        false,
                        false));
    }

    /** Waits, off the FX thread, until {@code condition} read on the FX thread holds. */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        com.editora.testkit.Await.until(
                () -> {
                    boolean[] met = {false};
                    try {
                        onFx(() -> met[0] = condition.getAsBoolean());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return met[0];
                },
                "the link");
    }

    private static void onFx(Runnable task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            task.run();
            return;
        }
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out on the FX thread");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }
}
