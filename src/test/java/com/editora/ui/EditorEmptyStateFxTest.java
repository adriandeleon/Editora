package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing the last tab leaves a hint in the editor area (Open File, Command Palette, Welcome Page) instead of
 * a blank panel, and opening anything hides it again.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditorEmptyStateFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        fx.close();
    }

    @Test
    void closingTheLastTabShowsTheHintAndOpeningATabHidesIt() throws Exception {
        MainController controller = fx.controller;
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(controller, "showWelcome"));
        FxTestSupport.drainFx();
        EditorEmptyState hint = FxTestSupport.field(controller, "editorEmptyState");
        assertNotNull(hint);
        assertFalse(FxTestSupport.callOnFx(hint::isShowing), "hidden while a tab is open");

        FxTestSupport.runOnFx(() -> {
            Tab welcome = FxTestSupport.field(controller, "welcomeTab");
            welcome.getTabPane().getTabs().remove(welcome);
        });
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(hint::isShowing), "shown once the last tab is gone");
        assertTrue(
                FxTestSupport.callOnFx(
                        () -> hint.node().lookupAll(".editor-empty-action").size() >= 3),
                "lists the ways back in");
        Node owner = FxTestSupport.callOnFx(() -> hint.node().getScene().getFocusOwner());
        assertTrue(owner == hint.node(), "focus stays in the editor area, not the toolbar: " + owner);

        FxTestSupport.runOnFx(() -> {
            CommandRegistry registry = FxTestSupport.field(controller, "registry");
            registry.run("view.welcome");
        });
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(hint::isShowing), "hidden again once a tab opens");
    }
}
