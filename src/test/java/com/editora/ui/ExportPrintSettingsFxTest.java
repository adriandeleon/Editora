package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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

    /**
     * Orientation, margins and code font size: three rows beside "PDF page size", each saying it is for
     * PDFs and not for printing, each writing its setting, each found by the settings search.
     */
    @Test
    void thePdfPageRowsAreShownWiredAndSearchable() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
                window.show(new Stage());
                Stage stage = FxTestSupport.field(window, "stage");
                try {
                    java.util.Map<?, Region> pages = FxTestSupport.field(window, "pages");
                    List<String> texts = new ArrayList<>();
                    pages.values().forEach(page -> labels(page, texts));
                    for (String key : new String[] {
                        "settings.pdf.orientation",
                        "settings.pdf.orientation.desc",
                        "settings.pdf.margins",
                        "settings.pdf.margins.desc",
                        "settings.pdf.codeFontSize",
                        "settings.pdf.codeFontSize.desc"
                    }) {
                        assertTrue(texts.contains(tr(key)), key + " is not shown on any settings page");
                    }
                    assertTrue(tr("settings.pdf.orientation.desc").contains("Page Setup"), "says what printing uses");
                    assertTrue(tr("settings.pdf.margins.desc").contains("Page Setup"), "says what printing uses");
                    assertTrue(tr("settings.pdf.codeFontSize.desc").contains("printing"), "says printing is apart");
                    // The rows follow the page size row, in the same card.
                    int size = texts.indexOf(tr("settings.pdf.pageSize"));
                    assertTrue(size >= 0 && size < texts.indexOf(tr("settings.pdf.orientation")));
                    assertTrue(
                            texts.indexOf(tr("settings.pdf.orientation")) < texts.indexOf(tr("settings.pdf.margins")));
                    assertTrue(
                            texts.indexOf(tr("settings.pdf.margins")) < texts.indexOf(tr("settings.pdf.codeFontSize")));

                    com.editora.config.Settings settings = fx.shared.getSettings();
                    ComboBox<String> orientation = FxTestSupport.field(window, "pdfOrientationCombo");
                    ComboBox<String> margins = FxTestSupport.field(window, "pdfMarginsCombo");
                    ComboBox<Integer> fontSize = FxTestSupport.field(window, "pdfCodeFontSizeCombo");
                    assertEquals("portrait", orientation.getValue(), "today's page by default");
                    assertEquals("normal", margins.getValue());
                    assertEquals(9, fontSize.getValue());
                    assertEquals(com.editora.config.Settings.PDF_ORIENTATIONS, orientation.getItems());
                    assertEquals(com.editora.config.Settings.PDF_MARGINS, margins.getItems());
                    assertEquals(com.editora.config.Settings.PDF_CODE_FONT_SIZES, fontSize.getItems());
                    assertEquals("Landscape", orientation.getConverter().toString("landscape"));
                    assertEquals(
                            tr("settings.pdf.margins.narrow"),
                            margins.getConverter().toString("narrow"));
                    assertEquals("11 pt", fontSize.getConverter().toString(11));

                    orientation.setValue("landscape");
                    margins.setValue("narrow");
                    fontSize.setValue(11);
                    assertEquals("landscape", settings.getPdfOrientation(), "the row writes the setting");
                    assertEquals("narrow", settings.getPdfMargins());
                    assertEquals(11, settings.getPdfCodeFontSize());

                    // The search index is the row's keywords plus its shown text.
                    javafx.scene.control.TextField search = FxTestSupport.field(window, "searchField");
                    for (String[] query : new String[][] {
                        {"orientation", "settings.pdf.orientation"},
                        {"landscape", "settings.pdf.orientation"},
                        {"margin", "settings.pdf.margins"},
                        {"font size", "settings.pdf.codeFontSize"}
                    }) {
                        search.setText(query[0]);
                        List<String> shown = new ArrayList<>();
                        pages.values().forEach(page -> collectVisible(page, shown));
                        assertTrue(shown.contains(tr(query[1])), "searching \"" + query[0] + "\" finds " + query[1]);
                    }
                    search.setText("");
                } finally {
                    stage.close();
                }
            });
        }
    }

    /** Adds the text of every label under {@code node} that a search left shown (visible and managed). */
    private static void collectVisible(Node node, List<String> out) {
        if (!node.isVisible() || !node.isManaged()) {
            return;
        }
        if (node instanceof Label label && label.getText() != null) {
            out.add(label.getText());
        }
        if (node instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collectVisible(c, out));
        }
    }
}
