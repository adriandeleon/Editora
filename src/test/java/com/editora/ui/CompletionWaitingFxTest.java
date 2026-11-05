package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.completion.Completion;
import com.editora.completion.CompletionResult;
import com.editora.completion.CompletionSource;
import com.editora.editor.EditorBuffer;
import com.editora.editor.completion.CompletionPopup;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a 20,000–49,000-line Python file Pyright took 4–13 s to answer {@code os.}, and the completion popup showed
 * nothing at all meanwhile (Python review 2 E103). After about 300 ms it now says "Waiting for Pyright…" (with the
 * buffer's own rows, when there are any), and the server's answer replaces it.
 */
@Tag("fx")
class CompletionWaitingFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private AsyncTestScope scope;
    private EditorBuffer buffer;
    private Stage stage;
    private final List<Consumer<CompletionResult>> pending = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        scope = new AsyncTestScope();
        FxTestSupport.runOnFx(() -> {
            buffer = new EditorBuffer();
            buffer.setLanguageOverride("python");
            buffer.setContent("import os\n");
            buffer.setLspActive(true);
            buffer.setLspTriggerChars(java.util.Set.of('.'));
            buffer.setCompletionDocEnabled(false);
            buffer.setLspCompletionSource(CompletionSource.named(() -> "Pyright", (line, col, kind, trigger, cb) -> {
                pending.add(cb);
                return () -> {};
            }));
            stage = new Stage();
            stage.setScene(new Scene(new StackPane(buffer.getNode()), 800, 600));
            stage.show();
            buffer.getArea().moveTo(buffer.text().length());
            buffer.getArea().requestFocus();
        });
        scope.onClose(() -> FxTestSupport.runOnFx(() -> {
            buffer.dispose();
            stage.close();
        }));
        scope.awaitFx();
    }

    @AfterEach
    void close() throws Exception {
        scope.close();
    }

    private CompletionPopup popup() {
        return (CompletionPopup) FxTestSupport.call(
                FxTestSupport.field(buffer, "completionActions"), "completionPopup", new Class<?>[] {});
    }

    private @Nullable String waitingText() throws Exception {
        return FxTestSupport.callOnFx(() -> (String) FxTestSupport.call(popup(), "waitingText", new Class<?>[] {}));
    }

    private void waitForWaitingLine() throws Exception {
        for (int i = 0; i < 100 && waitingText() == null; i++) {
            Thread.sleep(20);
        }
    }

    @Test
    void aSlowServerIsNamedUntilItsListArrives() throws Exception {
        FxTestSupport.runOnFx(() -> buffer.typeString("os."));
        waitForWaitingLine();
        assertEquals("Waiting for Pyright…", waitingText());
        assertTrue(FxTestSupport.callOnFx(buffer::completionShowing), "the popup is up while the server thinks");
        assertFalse(FxTestSupport.callOnFx(() -> popup().hasRows()), "no buffer word follows a dot");

        FxTestSupport.runOnFx(() -> pending.getLast()
                .accept(new CompletionResult(List.of(Completion.lsp("getcwd", "getcwd", "function")), false)));
        for (int i = 0; i < 100 && !FxTestSupport.callOnFx(() -> popup().hasRows()); i++) {
            Thread.sleep(20);
        }
        assertNull(waitingText(), "the answer replaces the waiting line");
        assertEquals("getcwd", FxTestSupport.callOnFx(() -> popup().selected().label()));
    }

    @Test
    void keysKeepTheirMeaningWhileOnlyTheWaitingLineShows() throws Exception {
        FxTestSupport.runOnFx(() -> buffer.typeString("os."));
        waitForWaitingLine();
        FxTestSupport.runOnFx(() -> buffer.pressKey("ENTER"));
        scope.awaitFx();
        assertFalse(FxTestSupport.callOnFx(buffer::completionShowing), "Enter closed the waiting line");
        assertEquals("import os\nos.\n", FxTestSupport.callOnFx(buffer::text), "and was a newline");
        FxTestSupport.runOnFx(() -> pending.getFirst()
                .accept(new CompletionResult(List.of(Completion.lsp("getcwd", "getcwd", "function")), false)));
        scope.awaitFx();
        assertFalse(FxTestSupport.callOnFx(buffer::completionShowing), "a late answer does not reopen it");
    }
}
