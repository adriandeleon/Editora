package com.editora.ui;

import java.util.List;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.lsp.LspServerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings → Language Servers and Debugging show one language at a time under a "Language" picker; the cards every
 * language shares stay put, a search shows every language with a hit, and clearing it lands on what it found.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class)
class SettingsLanguagePickerFxTest {

    private SettingsRig rig;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        rig = SettingsRig.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        rig.close();
    }

    private SettingsLanguagePicker picker(String page) {
        Object owner = FxTestSupport.field(rig.window, page);
        SettingsLanguagePicker picker = FxTestSupport.field(owner, "languages");
        assertNotNull(picker, page + " has a language picker");
        return picker;
    }

    private static Map<String, VBox> holders(SettingsLanguagePicker picker) {
        return FxTestSupport.field(picker, "holders");
    }

    private static List<String> shown(SettingsLanguagePicker picker) {
        return holders(picker).entrySet().stream()
                .filter(e -> e.getValue().isVisible() && e.getValue().isManaged())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static boolean shownUnder(Node node, Node root) {
        for (Node n = node; n != null && n != root.getParent(); n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    @Test
    void theLanguageServersPageShowsThePickedServerOnly() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.page("LSP");
            SettingsLanguagePicker languages = picker("lspPage");
            assertEquals(30, languages.combo().getItems().size(), "one entry per server");
            assertEquals("java", languages.picked(), "the first server is picked");
            assertEquals(List.of("java"), shown(languages));
            assertEquals(
                    tr("settings.lsp.lang.go"), languages.combo().getConverter().toString("go"));

            languages.combo().setValue("go");
            assertEquals(List.of("go"), shown(languages));
            // The Python card (interpreter, format on save) sits with the Python server, not above the picker.
            assertTrue(SettingsRig.texts(holders(languages).get("python")).contains(tr("settings.lsp.python.title")));
            assertFalse(SettingsRig.texts(holders(languages).get("go")).contains(tr("settings.lsp.python.title")));
        });
    }

    @Test
    void aSearchShowsEveryLanguageWithAHitAndClearingItLandsOnTheFirst() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            rig.page("LSP");
            SettingsLanguagePicker languages = picker("lspPage");
            languages.combo().setValue("go");
            TextField search = rig.control("searchField");

            search.setText("lemminx");
            assertEquals(List.of("xml", LspServerRegistry.MAVEN_POM_SERVER_ID), shown(languages));
            assertFalse(
                    shownUnder(languages.combo(), rig.page("LSP")),
                    "the picker means nothing while a search lists every language");

            search.setText("");
            assertEquals("xml", languages.picked(), "Go had no hit: the pick follows the search");
            assertEquals(List.of("xml"), shown(languages));
            assertTrue(shownUnder(languages.combo(), rig.page("LSP")));

            search.setText("tinymist");
            search.setText("");
            assertEquals("typst", languages.picked());
            languages.combo().setValue("java");
            search.setText("jdtls");
            search.setText("");
            assertEquals("java", languages.picked(), "a pick with a hit stays");
        });
    }

    @Test
    void theDebuggingPageKeepsTheSharedCardsAndPutsGoroutinesWithGo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("DEBUG");
            SettingsLanguagePicker languages = picker("debugPage");
            assertEquals("java", languages.picked());
            assertEquals(List.of("java"), shown(languages));
            assertTrue(SettingsRig.texts(page).contains(tr("settings.debug.typeRenderers")), "shared: stays on top");
            List<String> order = SettingsRig.texts(page);
            assertTrue(
                    order.indexOf(tr("settings.languagePicker")) < order.indexOf(tr("settings.debug.typeRenderers")),
                    "the picker sits above the shared cards, in sight");
            VBox go = holders(languages).get("go");
            assertTrue(SettingsRig.texts(go).contains(tr("settings.debug.goroutines")), "Go's own card is with Go");

            languages.show("go");
            assertEquals(List.of("go"), shown(languages));
            languages.show("no-such-language");
            assertEquals("go", languages.picked(), "an unknown id leaves the pick");
        });
    }

    @Test
    void theBuildToolsMavenLinkOpensTheMavenServer() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region buildTools = rig.page("BUILD_TOOLS");
            Hyperlink link = (Hyperlink) SettingsRig.button(buildTools, tr("settings.maven.pomLspLink"));
            link.fire();
            assertEquals("LSP", rig.openPage());
            assertEquals(
                    LspServerRegistry.MAVEN_POM_SERVER_ID, picker("lspPage").picked());
        });
    }

    @Test
    void aDoctorRowsSettingsOpensItsLanguagesCard() throws Exception {
        // Doctor's Settings… on the PowerShell server row opened Language Servers on whatever the picker held.
        FxTestSupport.runOnFx(() -> {
            rig.window.showDoctorTarget("lsp:powershell", rig.owner);
            assertEquals("LSP", rig.openPage());
            assertEquals("powershell", picker("lspPage").picked());
            assertEquals(List.of("powershell"), shown(picker("lspPage")));

            rig.window.showDoctorTarget("debug:go", rig.owner);
            assertEquals("DEBUG", rig.openPage());
            assertEquals("go", picker("debugPage").picked());

            rig.window.showDoctorTarget("lsp:no-such-server", rig.owner);
            assertEquals("LSP", rig.openPage(), "an unknown language still opens the page");
            assertEquals("powershell", picker("lspPage").picked(), "and leaves the pick");
        });
    }
}
