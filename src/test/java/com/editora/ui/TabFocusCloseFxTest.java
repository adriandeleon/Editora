package com.editora.ui;

import java.time.Duration;

import javafx.scene.Node;
import javafx.scene.input.MouseEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing the focused tab or a right-click on a tab's header leaves the keyboard in the editor shown (ninth review AP1,
 * AP2). Apart from {@link TabFocusFxTest}: each case gets a window of its own.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabFocusCloseFxTest {

    private FxWindowFixture fx;
    private CommandRegistry reg;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            reg = FxTestSupport.field(fx.controller, "registry");
            reg.run("file.new");
            reg.run("file.new");
        });
        settle();
    }

    @AfterAll
    void tearDown() throws Exception {
        fx.dispose();
    }

    private void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            FxTestSupport.drainFx();
        }
    }

    private EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    /** The active buffer, called on the FX thread. */
    private EditorBuffer activeNow() {
        return (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
    }

    /** {@link #focusOwner()}, called on the FX thread. */
    private Node focusOwnerNow() {
        return ((javafx.stage.Stage) FxTestSupport.field(fx.controller, "stage"))
                .getScene()
                .getFocusOwner();
    }

    /** The window's focus owner (its scene, not an editor's, which a split moves between groups). */
    private Node focusOwner() throws Exception {
        return FxTestSupport.callOnFx(() -> ((javafx.stage.Stage) FxTestSupport.field(fx.controller, "stage"))
                .getScene()
                .getFocusOwner());
    }

    /** Closing the focused tab leaves the keyboard in the editor shown, not on the toolbar (ninth review AP1). */
    @Test
    void closingTheFocusedTabFocusesTheNextEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            reg.run("file.new");
            reg.run("file.new");
        });
        settle();
        FxTestSupport.runOnFx(() -> activeNow().getFocusedArea().requestFocus());
        settle();
        EditorBuffer before = active();
        FxTestSupport.runOnFx(() -> reg.run("buffer.close"));
        settle();
        EditorBuffer now = active();
        assertTrue(now != null && now != before, "another tab is shown");
        // JavaFX moves it on a later pulse
        Await.holdsWithin(() -> onFx(() -> now.getFocusedArea() == focusOwnerNow()), Duration.ofSeconds(2));
        assertSame(now.getFocusedArea(), focusOwner());
    }

    /** A right-click on a tab's header leaves the keyboard in the editor (ninth review AP2). */
    @Test
    void aRightClickOnAHeaderKeepsTheEditorFocused() throws Exception {
        EditorBuffer before = active();
        Node header = FxTestSupport.callOnFx(
                () -> before.getArea().getScene().getRoot().lookup(".tab"));
        FxTestSupport.runOnFx(() -> {
            header.getScene().getRoot().lookup(".tab-pane").requestFocus(); // where JavaFX puts it on a press
            for (var type : java.util.List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                header.fireEvent(new MouseEvent(
                        type,
                        5,
                        5,
                        5,
                        5,
                        javafx.scene.input.MouseButton.SECONDARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        true,
                        false,
                        false,
                        null));
            }
        });
        settle();
        assertSame(active().getFocusedArea(), focusOwner());
    }

    /** Focus Next Editor Group puts the keyboard in that group's editor, not its tab's container (tenth review AO1). */
    @Test
    void focusNextEditorGroupFocusesItsEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            reg.run("file.new");
            activeNow().getFocusedArea().requestFocus();
            reg.run("view.splitEditorRight");
        });
        settle();
        try {
            EditorBuffer before = active();
            FxTestSupport.runOnFx(() -> reg.run("view.focusNextGroup"));
            settle();
            EditorBuffer now = active();
            assertTrue(now != null && now != before, "the other group's tab");
            // a split settles over pulses
            Await.holdsWithin(() -> onFx(() -> now.getFocusedArea() == focusOwnerNow()), Duration.ofSeconds(2));
            assertSame(now.getFocusedArea(), focusOwner());
        } finally {
            FxTestSupport.runOnFx(() -> reg.run("view.unsplitEditorGroups"));
            settle();
        }
    }

    /**
     * Closing the last file tab with the Welcome tab left puts the keyboard on the Welcome page, not on the toolbar's
     * New button, where a Space opened a new buffer (tenth review AP1).
     */
    @Test
    void closingTheLastFileTabBesideWelcomeLeavesTheToolbar() throws Exception {
        FxWindowFixture own = FxWindowFixture.create();
        try {
            CommandRegistry r = FxTestSupport.callOnFx(() -> FxTestSupport.field(own.controller, "registry"));
            FxTestSupport.runOnFx(() -> {
                r.run("buffer.closeAll");
                r.run("view.welcome");
                r.run("file.new"); // the Welcome tab and one file
            });
            settle();
            EditorBuffer file = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(own.controller, "activeBuffer", new Class<?>[] {}));
            javafx.scene.Scene scene =
                    FxTestSupport.callOnFx(() -> file.getArea().getScene());
            FxTestSupport.runOnFx(() -> {
                file.getFocusedArea().requestFocus();
                r.run("buffer.close");
            });
            settle();
            Node[] owner = new Node[1];
            // JavaFX moves focus on a later pulse
            Await.holdsWithin(
                    () -> {
                        try {
                            return onWelcomePage(owner[0] = onFx(scene::getFocusOwner));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    },
                    Duration.ofSeconds(2));
            Node focused = owner[0];
            assertTrue(
                    focused != null && !"newButton".equals(focused.getId()),
                    "not the toolbar's New button: " + focused);
            assertTrue(onWelcomePage(focused), "on the Welcome page: " + focused);
            assertTrue(
                    focused instanceof WelcomePane, "the page, not its first entry (eleventh review AP1): " + focused);
        } finally {
            own.dispose();
        }
    }

    private static boolean onWelcomePage(@org.jspecify.annotations.Nullable Node node) throws Exception {
        return node != null
                && FxTestSupport.callOnFx(() -> {
                    for (Node n = node; n != null; n = n.getParent()) {
                        if (n.getStyleClass().contains("welcome-pane")) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    /**
     * Closing the focused Welcome tab from the keyboard puts the keyboard in the editor shown, not on the toolbar's New
     * button: the page's link that had the focus left with its tab (eleventh review AM1).
     */
    @Test
    void closingAFocusedPageTabFocusesTheEditorShown() throws Exception {
        FxWindowFixture own = FxWindowFixture.create();
        try {
            CommandRegistry r = FxTestSupport.callOnFx(() -> FxTestSupport.field(own.controller, "registry"));
            FxTestSupport.runOnFx(() -> {
                r.run("buffer.closeAll");
                r.run("file.new");
                r.run("view.welcome"); // the Welcome tab, its first entry focused
            });
            settle();
            javafx.scene.Scene scene = FxTestSupport.callOnFx(
                    () -> ((javafx.stage.Stage) FxTestSupport.field(own.controller, "stage")).getScene());
            FxTestSupport.runOnFx(() -> {
                javafx.scene.Node page = scene.getRoot().lookup(".welcome-pane");
                page.requestFocus(); // hands the focus to an entry
            });
            settle();
            FxTestSupport.runOnFx(() -> r.run("buffer.close"));
            settle();
            Node[] owner = new Node[1];
            Await.holdsWithin(
                    () -> (owner[0] = onFx(scene::getFocusOwner)) != null
                            && owner[0].getStyleClass().contains("editor-area"),
                    Duration.ofSeconds(2));
            Node focused = owner[0];
            assertTrue(
                    focused != null && focused.getStyleClass().contains("editor-area"),
                    "the file's editor: " + focused);
        } finally {
            own.dispose();
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
