package com.editora.ui;

import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.diff.ThreeWayMerge;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The debugger's scans, merge and apply texts are worked out off the FX thread (PERF-01): here in place, so
// each lands before the command returns. HandlersDebugOffFxThreadFxTest runs them where the app does.
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
@Tag("fx")
class MergeViewerPaneFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void choiceUpdatesEditableResultAndSaveAppliesItsExactText() throws Exception {
        var merge = ThreeWayMerge.merge(
                "before\r\nold\r\nafter\r\n", "before\r\nours\r\nafter\r\n", "before\r\ntheirs\r\nafter\r\n");
        AtomicReference<String> applied = new AtomicReference<>();
        MergeViewerPane pane = FxTestSupport.callOnFx(
                () -> new MergeViewerPane("merge", merge.file(), "Monospaced", 13, "\r\n", true, applied::set));
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 650));
            window.show();
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
            return window;
        });
        try {
            TextArea result = FxTestSupport.field(pane, "resultArea");
            assertTrue(result.isEditable());
            assertTrue(result.getText().contains("||||||| common ancestor"));

            Button acceptOurs = button((Parent) pane.node(), "Accept Ours");
            FxTestSupport.runOnFx(acceptOurs::fire);
            assertEquals("before\nours\nafter\n", result.getText());

            FxTestSupport.runOnFx(() -> result.setText("before\r\ncustom\r\nafter\r\n"));
            assertTrue(pane.hasUnsavedChanges());
            Button acceptTheirs = button((Parent) pane.node(), "Accept Theirs");
            FxTestSupport.runOnFx(acceptTheirs::fire);
            assertEquals(
                    "before\ncustom\nafter\n",
                    result.getText(),
                    "a conflict choice must not erase a manually edited resolution draft");
            Button save = button((Parent) pane.node(), "Save resolution");
            FxTestSupport.runOnFx(save::fire);
            assertEquals("before\r\ncustom\r\nafter\r\n", applied.get());
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** An edit typed back to what was generated is no edit: the choices work again. */
    @Test
    void anEditUndoneByHandLetsTheChoicesWorkAgain() throws Exception {
        var merge = ThreeWayMerge.merge("before\nold\nafter\n", "before\nours\nafter\n", "before\ntheirs\nafter\n");
        MergeViewerPane pane = FxTestSupport.callOnFx(
                () -> new MergeViewerPane("merge", merge.file(), "Monospaced", 13, "\n", true, text -> {}));
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 650));
            window.show();
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
            return window;
        });
        try {
            TextArea result = FxTestSupport.field(pane, "resultArea");
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Ours")::fire);
            assertEquals("before\nours\nafter\n", result.getText());
            FxTestSupport.runOnFx(() -> {
                result.insertText(0, "x");
                result.deleteText(0, 1); // typed and deleted again
            });
            assertTrue(pane.hasUnsavedChanges());
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Theirs")::fire);
            assertEquals("before\ntheirs\nafter\n", result.getText(), "the text is what was generated");
            FxTestSupport.runOnFx(() -> result.insertText(0, "x"));
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Ours")::fire);
            assertEquals("xbefore\ntheirs\nafter\n", result.getText(), "a real edit still blocks the choice");
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** A TextArea drops control characters; the resolution must not lose them from untouched lines. */
    @Test
    void applyKeepsControlCharactersOutsideTheEditedLines() throws Exception {
        var file = com.editora.diff.ConflictParser.parse(java.util.List.of(
                "section one",
                "\f",
                "two \u001b[0m esc",
                "<<<<<<< HEAD",
                "ours",
                "=======",
                "theirs",
                ">>>>>>> b",
                "end"));
        AtomicReference<String> applied = new AtomicReference<>();
        MergeViewerPane pane = FxTestSupport.callOnFx(
                () -> new MergeViewerPane("merge", file, "Monospaced", 13, "\n", true, applied::set));
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 650));
            window.show();
            return window;
        });
        try {
            TextArea result = FxTestSupport.field(pane, "resultArea");
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Ours")::fire);
            Button save = button((Parent) pane.node(), "Save resolution");
            FxTestSupport.runOnFx(save::fire);
            assertEquals("section one\n\f\ntwo \u001b[0m esc\nours\nend\n", applied.get());

            // A hand edit of one line leaves the others exact.
            FxTestSupport.runOnFx(() -> result.setText(result.getText().replace("ours", "mine")));
            FxTestSupport.runOnFx(save::fire);
            assertEquals("section one\n\f\ntwo \u001b[0m esc\nmine\nend\n", applied.get());
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** A Result that could not be put together for the last choice is never saved: it is for earlier ones. */
    @Test
    void aFailedComposeTurnsSaveOffUntilTheNextOneSucceeds() throws Exception {
        var merge = ThreeWayMerge.merge("before\nold\nafter\n", "before\nours\nafter\n", "before\ntheirs\nafter\n");
        AtomicReference<String> applied = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        MergeViewerPane pane = failingPane(merge.file(), applied, fail);
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 650));
            window.show();
            return window;
        });
        try {
            Button save = button((Parent) pane.node(), "Save resolution");
            fail.set(true);
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Ours")::fire);
            fail.set(false);
            assertTrue(save.isDisabled(), "the Result shown is stale");
            FxTestSupport.runOnFx(save::fire);
            assertNull(applied.get(), "a stale Result is not saved");
            assertEquals(MergeViewerPane.resultFailedText(new IllegalStateException("boom")), statusText(pane));

            // The close question puts the Result together in place: the one for the choices made.
            boolean saved = FxTestSupport.callOnFx(pane::saveBeforeClose);
            assertTrue(saved);
            assertEquals("before\nours\nafter\n", applied.get());
            assertFalse(save.isDisabled());

            applied.set(null);
            fail.set(true);
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Theirs")::fire);
            fail.set(false);
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Ours")::fire); // composes again, fine
            assertFalse(save.isDisabled());
            FxTestSupport.runOnFx(save::fire);
            assertEquals("before\nours\nafter\n", applied.get());
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /**
     * R4-H1 / R4-H2: after a failed compose, a Result edited by hand is what the quit saves — it is not put
     * together again over the edits — and a save queued behind a compose is dropped when the quit saves in place.
     */
    @Test
    void theQuitSavesAHandEditedResultAsShownAfterAFailedCompose() throws Exception {
        var merge = ThreeWayMerge.merge("before\nold\nafter\n", "before\nours\nafter\n", "before\ntheirs\nafter\n");
        AtomicReference<String> applied = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        MergeViewerPane pane = failingPane(merge.file(), applied, fail);
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage window = new Stage();
            window.setScene(new Scene(new StackPane(pane.node()), 900, 650));
            window.show();
            return window;
        });
        try {
            TextArea result = FxTestSupport.field(pane, "resultArea");
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Ours")::fire);
            fail.set(true);
            FxTestSupport.runOnFx(button((Parent) pane.node(), "Accept Theirs")::fire); // fails: ours still shown
            fail.set(false);
            FxTestSupport.runOnFx(() -> result.setText("before\nmine by hand\nafter\n"));
            int[] queued = {0};
            java.lang.reflect.Field after = MergeViewerPane.class.getDeclaredField("afterComposed");
            after.setAccessible(true);
            FxTestSupport.runOnFx(() -> {
                try {
                    after.set(pane, (Runnable) () -> queued[0]++);
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            });

            boolean saved = FxTestSupport.callOnFx(() -> pane.saveBeforeClose());
            assertTrue(saved);
            assertEquals("before\nmine by hand\nafter\n", applied.get(), "the user's text, not rebuilt");
            assertEquals("before\nmine by hand\nafter\n", result.getText());
            assertNull(FxTestSupport.callOnFx(() -> after.get(pane)), "the queued save is dropped");
            assertEquals(0, queued[0]);
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** A pane whose Result cannot be put together while {@code fail} is set. */
    private static MergeViewerPane failingPane(
            com.editora.diff.ConflictParser.ConflictFile file,
            AtomicReference<String> applied,
            java.util.concurrent.atomic.AtomicBoolean fail)
            throws Exception {
        return FxTestSupport.callOnFx(() -> new MergeViewerPane(
                "merge",
                file,
                "Monospaced",
                13,
                "\n",
                true,
                (text, done) -> {
                    applied.set(text);
                    done.accept(true);
                },
                (f, choices, separator, newline) -> {
                    if (fail.get()) {
                        throw new IllegalStateException("boom");
                    }
                    return MergeViewerPane.resultText(f, choices, separator, newline);
                }));
    }

    private static String statusText(MergeViewerPane pane) throws Exception {
        javafx.scene.control.Label status = FxTestSupport.field(pane, "status");
        return FxTestSupport.callOnFx(status::getText);
    }

    private static Button button(Parent root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing button: " + text));
    }
}
