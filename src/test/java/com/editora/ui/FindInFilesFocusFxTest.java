package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

            // A node has the focus only while its window does, and on macOS the window gets it a moment later.
            awaitFx("the window focused", () -> c.stage.isFocused(), c);

            FxTestSupport.runOnFxUnchecked(toEditor);
            FxTestSupport.runOnFxUnchecked(press);
            awaitFx("opened with the cursor in it", () -> search.panel().isFocusWithin(), c);

            FxTestSupport.runOnFxUnchecked(toEditor);
            awaitFx("the cursor back in the editor", () -> !search.panel().isFocusWithin(), c);
            FxTestSupport.runOnFxUnchecked(press); // open already, the cursor elsewhere: back into the query
            awaitFx("focused, not closed", () -> search.panel().isFocusWithin(), c);
            assertTrue(FxTestSupport.callOnFx(() -> search.panel().getScene() != null), "still open");

            FxTestSupport.runOnFxUnchecked(press); // from inside: the toggle closes it
            awaitFx(
                    "pressed from inside, it closes",
                    () -> search.panel().getScene() == null || !search.panel().isVisible(),
                    c);
        }
    }

    /** Waits up to 10 s for {@code condition} (read on the FX thread); a failure names the window's focus. */
    private static void awaitFx(String what, java.util.function.BooleanSupplier condition, MainController c)
            throws Exception {
        if (!com.editora.testkit.Await.holdsWithin(
                () -> {
                    try {
                        return FxTestSupport.callOnFx(condition::getAsBoolean);
                    } catch (Exception e) {
                        return false;
                    }
                },
                java.time.Duration.ofSeconds(10))) {
            fail(what + " (window focused: " + FxTestSupport.callOnFx(() -> c.stage.isFocused()) + ", focus owner: "
                    + FxTestSupport.callOnFx(
                            () -> String.valueOf(c.stage.getScene().getFocusOwner())) + ")");
        }
    }
}
