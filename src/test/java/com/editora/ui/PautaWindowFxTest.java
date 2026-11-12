package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;

import com.editora.AppInfo;
import com.editora.PautaConformance;
import com.editora.PautaConformance.Entry;
import com.editora.PautaConformance.Kind;
import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Pauta window (pauta-project.dev, the Pauta window template): what it shows comes from the entries it is
 * given, its links and its copy button run commands, and the commands and the Help row that open it exist.
 */
@Tag("fx")
class PautaWindowFxTest {

    private static final String ISSUE = "https://github.com/adriandeleon/Editora/issues/212";
    private static final String DECISION =
            "https://github.com/adriandeleon/Editora/blob/master/docs/decisions/0007-example.md";

    private static final List<Entry> SAMPLE = List.of(
            new Entry("AAA-02", "SHOULD", "https://pauta-project.dev/x/#aaa-02", Kind.DEVIATION, "Second.", DECISION),
            new Entry("BBB-01", "SHOULD", "https://pauta-project.dev/x/#bbb-01", Kind.PENDING, "Third.", ISSUE),
            new Entry("CCC-09", "MUST", "https://pauta-project.dev/x/#ccc-09", Kind.PENDING, "First.", ISSUE),
            new Entry("DDD-04", "MUST", "https://pauta-project.dev/x/#ddd-04", Kind.NOT_APPLICABLE, "Last.", null));

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void itShowsTheEditionTheConformanceTheCountsAndEveryExceptionPendingFirst() throws Exception {
        FxTestSupport.runOnFx(() -> {
            PautaWindow window = new PautaWindow(null, id -> {}, url -> {}, SAMPLE);
            DialogPane pane = window.dialog().getDialogPane();
            List<String> texts = SettingsRig.texts(pane);
            assertTrue(texts.contains(tr("pauta.edition", AppInfo.PAUTA_EDITION)), texts.toString());
            assertTrue(texts.contains(tr("pauta.conformance.partial")), "a pending MUST is Partial: " + texts);
            assertTrue(texts.contains(tr("pauta.conformance.partial.desc", AppInfo.NAME, 1L)), texts.toString());
            assertTrue(texts.contains(PautaWindow.counts(SAMPLE)), texts.toString());
            assertEquals(
                    tr("pauta.count.pending", 2) + " · " + tr("pauta.count.deviation", 1) + " · "
                            + tr("pauta.count.notApplicable", 1),
                    PautaWindow.counts(SAMPLE));

            // Group headers and rule IDs, in the order the list draws them: pending (the MUST first), the
            // deviation, then not applicable.
            List<String> drawn = new ArrayList<>();
            for (Node child : window.list().getChildren()) {
                if (child instanceof Label header) {
                    drawn.add(header.getText());
                } else {
                    drawn.add(((Hyperlink) child.lookup(".pauta-rule")).getText());
                }
            }
            assertEquals(
                    List.of(
                            tr("pauta.kind.pending"),
                            "CCC-09",
                            "BBB-01",
                            tr("pauta.kind.deviation"),
                            "AAA-02",
                            tr("pauta.kind.notApplicable"),
                            "DDD-04"),
                    drawn);
            // A pending row links its issue by number, a deviation its decision record by number.
            List<String> links = SettingsRig.all(window.list(), Hyperlink.class).stream()
                    .map(Hyperlink::getText)
                    .toList();
            assertTrue(links.contains("#212"), links.toString());
            assertTrue(links.contains(tr("pauta.decision", "0007")), links.toString());
        });
    }

    @Test
    void everyRuleOpensItsOwnAddressAndTheOtherLinksRunCommands() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> commands = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            PautaWindow window = new PautaWindow(null, commands::add, urls::add, SAMPLE);
            DialogPane pane = window.dialog().getDialogPane();
            for (Hyperlink link : SettingsRig.all(window.list(), Hyperlink.class)) {
                link.fire();
            }
            assertEquals(
                    List.of(
                            "https://pauta-project.dev/x/#ccc-09",
                            ISSUE,
                            "https://pauta-project.dev/x/#bbb-01",
                            ISSUE,
                            "https://pauta-project.dev/x/#aaa-02",
                            DECISION,
                            "https://pauta-project.dev/x/#ddd-04"),
                    urls);
            assertTrue(commands.isEmpty(), "a rule's link opens its address directly");

            SettingsRig.button(pane, tr("pauta.rules", AppInfo.PAUTA_EDITION)).fire();
            SettingsRig.button(pane, tr("pauta.docs")).fire();
            window.copyButton().fire();
            assertEquals(List.of("help.pautaRules", "help.pautaDocs", "help.copyPauta"), commands);
            assertEquals(tr("pauta.copied"), window.copyButton().getText());
            assertFalse(
                    pane.getButtonTypes().stream()
                            .anyMatch(t -> t.getButtonData() != ButtonBar.ButtonData.CANCEL_CLOSE),
                    "Copy as text is in the content: the button bar holds Close alone");
            ButtonType close = pane.getButtonTypes().get(0);
            assertTrue(((Button) pane.lookupButton(close)).isDefaultButton(), "Enter closes, like Esc");
        });
    }

    @Test
    void withNoExceptionsItSaysSoAndTheCopyStillHasTheEdition() throws Exception {
        FxTestSupport.runOnFx(() -> {
            PautaWindow window = new PautaWindow(null, id -> {}, url -> {}, List.of());
            String none = tr("pauta.none", AppInfo.NAME, AppInfo.PAUTA_EDITION);
            assertEquals(List.of(none), SettingsRig.texts(window.list()));
            String markdown = PautaWindow.markdown(List.of());
            assertTrue(markdown.contains(none), markdown);
            assertTrue(markdown.contains(tr("pauta.conformance.complete")), markdown);
            assertTrue(markdown.endsWith(AppInfo.pautaUrl() + "\n"), markdown);
        });
    }

    @Test
    void theCopyIsMarkdownWithOneLinePerException() {
        String markdown = PautaWindow.markdown(SAMPLE);
        assertTrue(
                markdown.startsWith("# " + tr("pauta.edition", AppInfo.PAUTA_EDITION) + ": "
                        + tr("pauta.conformance.partial") + "\n"),
                markdown);
        assertTrue(
                markdown.contains(
                        "- [CCC-09](https://pauta-project.dev/x/#ccc-09) MUST: First. [#212](" + ISSUE + ")\n"),
                markdown);
        assertTrue(markdown.contains("- [DDD-04](https://pauta-project.dev/x/#ddd-04) MUST: Last.\n"), markdown);
        assertEquals(
                SAMPLE.size(), markdown.lines().filter(l -> l.startsWith("- [")).count());
        assertFalse(markdown.contains("latest"), "the edition's own address, never latest/");
    }

    @Test
    void theAppsOwnWindowListsEveryEntryOfPautaJson() throws Exception {
        FxTestSupport.runOnFx(() -> {
            PautaWindow window = new PautaWindow(null, id -> {}, url -> {});
            long rows = window.list().getChildren().stream()
                    .filter(n -> n.getStyleClass().contains("pauta-row"))
                    .count();
            assertEquals(PautaConformance.exceptions().size(), rows);
        });
    }

    @Test
    void theCommandsExistAndTheHelpMenuHasItsRowJustAboveAbout() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            for (String id : List.of("help.pauta", "help.pautaRules", "help.pautaDocs", "help.copyPauta")) {
                assertTrue(registry.get(id).isPresent(), id + " is registered");
            }
        }
        List<String> help = MenuBarModel.menus(false).stream()
                .filter(m -> "menubar.help".equals(m.titleKey()))
                .findFirst()
                .orElseThrow()
                .entries();
        assertEquals("help.pauta", help.get(help.size() - 2), help.toString());
        assertEquals("help.about", help.get(help.size() - 1), help.toString());
        assertNotNull(MenuBarIcons.forCommand("help.pauta"), "the row has an icon");
    }
}
