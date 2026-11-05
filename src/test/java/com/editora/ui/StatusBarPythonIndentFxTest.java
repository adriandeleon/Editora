package com.editora.ui;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Python file whose tabs and spaces Python rejects says so in the status bar (review 2 A108). */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusBarPythonIndentFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void mixedTabsAndSpacesWarnUntilTheyAreFixed() throws Exception {
        String mixed = "def f():\n    if x:\n\treturn 1\n    return 2\n";
        Object[] made = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("python");
            b.setContent(mixed);
            b.getNode();
            StatusBar sb = new StatusBar(() -> b, new CommandRegistry(), Settings::new);
            sb.attach(b);
            return new Object[] {sb, b};
        });
        StatusBar sb = (StatusBar) made[0];
        EditorBuffer b = (EditorBuffer) made[1];
        Label indent = FxTestSupport.callOnFx(() -> FxTestSupport.field(sb, "indent"));
        FxTestSupport.runOnFx(() -> {
            assertTrue(indent.getText().endsWith("mixed indent"), indent.getText());
            assertTrue(indent.getTooltip().getText().startsWith("Line 3 mixes tabs and spaces"));
            assertTrue(indent.getStyleClass().contains("status-encoding-assumed"), "in the warning colour");
            int tab = b.getArea().getText().indexOf('\t');
            b.getArea().replaceText(tab, tab + 1, "        "); // the user re-indents the line with spaces
        });
        Thread.sleep(400); // the check runs once per typing burst
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertFalse(indent.getText().contains("mixed"), indent.getText());
            assertEquals("Set tab size", indent.getTooltip().getText());
            assertFalse(indent.getStyleClass().contains("status-encoding-assumed"));
        });
    }
}
