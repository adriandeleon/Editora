package com.editora.ui;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A library file opened by Go to Definition is put into View mode after its tab is shown. The status bar said
 * "Editable" beside the View Mode banner until something else refreshed it (Python review 2 E107): it now follows
 * the attached buffer's editability.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusBarViewModeFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void viewModeSetAfterAttachShowsReadOnly() throws Exception {
        String[] texts = FxTestSupport.callOnFx(() -> {
            Settings settings = new Settings();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("def getcwd() -> str: ...\n");
            StatusBar sb = new StatusBar(() -> buffer, new CommandRegistry(), () -> settings);
            sb.attach(buffer);
            Label readOnly = FxTestSupport.field(sb, "readOnly");
            String before = readOnly.getText();
            buffer.setViewMode(true); // what LspCoordinator does for a library file, after the tab is shown
            String during = readOnly.getText();
            buffer.setViewMode(false); // Enable Editing
            return new String[] {before, during, readOnly.getText()};
        });
        assertEquals(Messages.tr("statusbar.editable"), texts[0]);
        assertEquals(Messages.tr("statusbar.readOnly"), texts[1], "View mode shows Read-Only at once");
        assertEquals(Messages.tr("statusbar.editable"), texts[2]);
    }
}
