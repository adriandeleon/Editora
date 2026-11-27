package com.editora.ui;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (r/JavaFX dry run, 2026-11-27): Go to File ({@code nav.gotoFile}, IntelliJ's Ctrl+Shift+N, VS Code's
 * Ctrl+P) opens Search Everywhere scoped to files in a project window, and the Find File prompt without one.
 */
@Tag("fx")
class GoToFileFxTest {

    private FxWindowFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.<OverlayHost>field(fx.controller, "overlayHost")
                    .hide());
            fx.dispose();
        }
    }

    private SearchEverywherePopup popup() {
        return FxTestSupport.field(FxTestSupport.field(fx.controller, "chrome"), "searchEverywherePopup");
    }

    private void run(String id) {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFxUnchecked(() -> registry.run(id));
    }

    @Test
    void inAProjectItSearchesOnlyFilesWithTheCaretAfterTheSigil() throws Exception {
        fx.useProject();
        javafx.stage.Stage stage = FxTestSupport.field(fx.controller, "stage");
        FxTestSupport.runOnFxUnchecked(stage::requestFocus); // focused, so the field really gains focus
        run("nav.gotoFile");
        assertTrue(popup().isShown(), "Search Everywhere opens");
        javafx.scene.control.TextField input = FxTestSupport.field(popup(), "input");
        // Let the focus-gain land: a TextField selects all its text when it gains focus, a pulse or two later.
        for (int i = 0; i < 5; i++) {
            FxTestSupport.runOnFxUnchecked(() -> {});
        }
        assertTrue(FxTestSupport.callOnFx(input::isFocused), "the field has the focus, as in a real window");
        // On a first open in a window the focus owner can reach the field after the open placed the caret, and the
        // TextField selects all then (the r/JavaFX re-review saw the # replaced): a late select-all is undone too.
        FxTestSupport.runOnFxUnchecked(input::selectAll);
        for (int i = 0; i < 3; i++) {
            FxTestSupport.runOnFxUnchecked(() -> {});
        }
        FxTestSupport.runOnFxUnchecked(() -> {
            assertEquals("#", input.getText(), "scoped to files");
            assertEquals(1, input.getCaretPosition(), "the caret after #, so the name typed next keeps it");
            assertEquals("", input.getSelectedText(), "# is not selected, or the first key would replace it");
        });
    }

    @Test
    void withoutAProjectItIsTheFindFilePrompt() throws Exception {
        boolean[] findFile = {false};
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFxUnchecked(
                () -> registry.register(com.editora.command.Command.of("file.find", () -> findFile[0] = true)));
        run("nav.gotoFile");
        assertFalse(popup().isShown(), "no project files to search");
        assertTrue(findFile[0], "falls back to Find File");
    }
}
