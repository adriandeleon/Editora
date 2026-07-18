package com.editora.ui;

import java.util.List;

import javafx.scene.control.ListView;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #532: the command palette must <b>show</b> commands that can't run in the current context
 * (a disabled feature) rather than hiding them — grayed out and skipped by the selection cursor, not filtered
 * away entirely.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandPaletteDisabledFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabledCommandsAreShownButTheCursorSkipsThem() throws Exception {
        CommandRegistry reg = new CommandRegistry();
        reg.register(Command.of("git.disabled", "Git Thing", () -> {})); // disabled feature
        reg.register(Command.of("edit.enabled", "Edit Thing", () -> {})); // enabled

        CommandPalette palette = FxTestSupport.callOnFx(
                () -> new CommandPalette(reg, new KeymapManager(), c -> !c.id().startsWith("git."))); // git.* disabled

        // Populate the results with an empty query.
        FxTestSupport.runOnFx(() -> FxTestSupport.call(palette, "filter", new Class<?>[] {String.class}, ""));

        // Both commands are listed — the disabled one is shown, not hidden.
        List<Command> items = FxTestSupport.field(palette, "items");
        assertEquals(2, items.size(), "the disabled command is still listed");
        assertTrue(items.stream().anyMatch(c -> c.id().equals("git.disabled")), "grayed, but present");

        // The cursor lands on the enabled command, skipping the leading disabled one.
        ListView<Command> list = FxTestSupport.field(palette, "list");
        Command selected = FxTestSupport.callOnFx(() -> list.getSelectionModel().getSelectedItem());
        assertEquals("edit.enabled", selected.id(), "the cursor never rests on a grayed-out command");
    }
}
