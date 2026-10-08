package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Export &amp; Print" settings card says what each row applies to — the page size is for PDFs only,
 * the other rows for printing too — and the page footer can be turned off from the card and the palette.
 */
@Tag("fx")
class ExportPrintSettingsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void labels(Node root, List<String> out) {
        if (root instanceof Label label && label.getText() != null) {
            out.add(label.getText());
        }
        if (root instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> labels(c, out));
        }
    }

    @Test
    void theCardNamesWhatEachRowAppliesToAndTheFooterRowIsWired() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
                window.show(new Stage());
                Stage stage = FxTestSupport.field(window, "stage");
                try {
                    java.util.Map<?, Region> pages = FxTestSupport.field(window, "pages");
                    List<String> texts = new ArrayList<>();
                    pages.values().forEach(page -> labels(page, texts));
                    assertEquals("PDF page size", tr("settings.pdf.pageSize"), "the row no longer reads as paper size");
                    for (String key : new String[] {
                        "settings.pdf.pageSize",
                        "settings.pdf.pageSize.desc",
                        "settings.pdf.lineNumbers.desc",
                        "settings.pdf.pageFooter",
                        "settings.pdf.pageFooter.desc"
                    }) {
                        assertTrue(texts.contains(tr(key)), key + " is not shown on any settings page");
                    }
                    assertTrue(tr("settings.pdf.pageSize.desc").contains("Page Setup"), "says what printing uses");
                    // Line numbers and highlighting share one sentence: shown once per row.
                    assertEquals(
                            2,
                            texts.stream()
                                    .filter(tr("settings.pdf.lineNumbers.desc")::equals)
                                    .count());

                    CheckBox footer = FxTestSupport.field(window, "pdfPageFooterCheck");
                    assertTrue(footer.isSelected(), "on by default");
                    footer.setSelected(false);
                    assertFalse(fx.shared.getSettings().isPdfPageFooter(), "the row writes the setting");

                    // The palette toggle flips the same field and keeps the open window in step.
                    com.editora.command.CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
                    registry.get("view.togglePageFooter").orElseThrow().run();
                    assertTrue(fx.shared.getSettings().isPdfPageFooter());
                    assertTrue(footer.isSelected(), "the Settings window follows the command");
                } finally {
                    stage.close();
                }
            });
        }
    }
}
