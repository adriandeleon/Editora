package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (HN dry run, 2026-11-27): Ctrl+Shift+F opened Find in Files but left the cursor in the editor, so the
 * query went into the file, and pressed again it closed the window. It puts the cursor in the query field, open or
 * not, and closes the window only when pressed from inside it.
 */
@Tag("fx")
class FindInFilesFocusFxTest {

    @Test
    void findInFilesFocusesItsQueryAndClosesOnlyFromInside(@TempDir Path dir) throws Exception {
        FxTestSupport.bootToolkit();
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(dir.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            Path file = Files.writeString(dir.resolve("notes.txt"), "hello\n");
            FxTestSupport.runOnFx(() -> {
                c.stage.requestFocus();
                c.openAndNavigate(file, 1);
            });
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            SearchCoordinator search = c.searchCoordinator;
            Runnable press = () -> registry.run("search.inFiles");
            Runnable toEditor = () -> c.activeBuffer().getArea().requestFocus();

            FxTestSupport.runOnFxUnchecked(toEditor);
            FxTestSupport.runOnFxUnchecked(press);
            FxTestSupport.drainFx();
            assertTrue(FxTestSupport.callOnFx(() -> search.panel().isFocusWithin()), "opened with the cursor in it");

            FxTestSupport.runOnFxUnchecked(toEditor);
            FxTestSupport.runOnFxUnchecked(press); // open already, the cursor elsewhere: back into the query
            FxTestSupport.drainFx();
            assertTrue(FxTestSupport.callOnFx(() -> search.panel().isFocusWithin()), "focused, not closed");
            assertTrue(FxTestSupport.callOnFx(() -> search.panel().getScene() != null), "still open");

            FxTestSupport.runOnFxUnchecked(press); // from inside: the toggle closes it
            FxTestSupport.drainFx();
            assertFalse(
                    FxTestSupport.callOnFx(() ->
                            search.panel().getScene() != null && search.panel().isVisible()),
                    "pressed from inside, it closes");
        }
    }
}
