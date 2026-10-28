package com.editora.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A copy installed before the pin is a warning in Settings, as it is in Doctor: amber, short, with the whole text in
 * the tooltip — red with its version cut out of the middle read as a fault (XML/pom review 3, A26).
 */
@Tag("fx")
class ToolStatusRowFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aPrePinCopyIsAnAmberPillWithTheWholeStoryInItsTooltip() throws Exception {
        String shortText = tr("settings.lsp.pinUnknownShort", "lemminx", "0.31.2");
        String whole = tr("settings.lsp.pinUnknown", "lemminx", "0.31.2");
        FxTestSupport.runOnFx(() -> {
            ToolStatusRow row = new ToolStatusRow("lemminx").install(() -> {});
            row.showWarning(shortText, whole);
            assertTrue(row.pill().getStyleClass().contains("settings-git-warn"));
            assertFalse(row.pill().getStyleClass().contains("settings-git-missing"));
            assertEquals(shortText, row.pill().getText());
            assertEquals(whole, row.pill().getTooltip().getText());
            assertFalse(row.installButton().isDisabled(), "Install… updates it");
            assertTrue(shortText.contains("0.31.2"), "the version is in the short text");
            assertTrue(shortText.length() < whole.length());
        });
    }
}
