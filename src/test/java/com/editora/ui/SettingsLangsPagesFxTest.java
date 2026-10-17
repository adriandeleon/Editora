package com.editora.ui;

import java.util.Map;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Languages &amp; Tools and AI pages of Settings, as the user sees them. */
@Tag("fx")
class SettingsLangsPagesFxTest {

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

    /** True when some note on {@code page} says to turn on {@code switchTitle} and is showing. */
    private static boolean showsDependsNote(Region page, String switchTitle) {
        String text = tr("settings.dependsOn", switchTitle);
        return SettingsRig.all(page, Label.class).stream().anyMatch(l -> text.equals(l.getText()) && l.isVisible());
    }

    /**
     * Everything under an off master switch greys out, and the page says which switch turns it back on — it
     * used to be only the top enable box (AI), or nothing at all (LSP commands and Install buttons, Mermaid).
     */
    @Test
    void settingsUnderAnOffMasterSwitchAreGreyedWithANote() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.settings.setAiEnabled(false);
            rig.settings.setLspSupport(false);
            rig.settings.setMermaidSupport(false);
            rig.settings.setHttpClientSupport(false);
            rig.show();
            CheckBox ai = rig.control("aiMasterCheck");
            Map<String, TextField> agentCommands = rig.control("agentCommandFields");
            ComboBox<String> provider = rig.control("aiProviderCombo");
            TextField endpoint = rig.control("aiEndpointField");
            Region agentPage = rig.page("AGENT");
            assertTrue(agentCommands.get("claude").isDisabled(), "agent command");
            assertTrue(provider.isDisabled() && endpoint.isDisabled(), "AI Actions rows");
            assertTrue(showsDependsNote(agentPage, ai.getText()));

            CheckBox lsp = rig.control("lspCheck");
            Map<String, TextField> lspCommands = rig.control("lspCommandFields");
            Map<String, javafx.scene.control.Button> installs = rig.control("installButtons");
            Region lspPage = rig.page("LSP");
            assertTrue(lspCommands.get("rust").isDisabled(), "server command");
            assertTrue(installs.get("server:rust").isDisabled(), "Install button");
            assertTrue(showsDependsNote(lspPage, lsp.getText()));

            TextField mmdc = rig.control("mmdcPathField");
            assertTrue(mmdc.isDisabled(), "mmdc path");
            CheckBox scripts = rig.control("httpScriptsCheck");
            assertTrue(scripts.isDisabled(), "HTTP scripts follow the HTTP client");

            ai.setSelected(true);
            lsp.setSelected(true);
            ((CheckBox) rig.control("mermaidCheck")).setSelected(true);
            assertFalse(agentCommands.get("claude").isDisabled());
            assertFalse(provider.isDisabled() || endpoint.isDisabled());
            assertFalse(lspCommands.get("rust").isDisabled());
            assertFalse(mmdc.isDisabled());
            assertFalse(showsDependsNote(agentPage, ai.getText()), "the note goes once the switch is on");
        });
    }

    /**
     * Each Debugging card has an Install button keyed on its adapter: the LSP page's button says "Installed"
     * as soon as the server is found, so a missing java-debug or debugpy had no button anywhere in Settings.
     */
    @Test
    void eachDebugAdapterCardOffersItsInstall() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.settings.setDebugSupport(true);
            java.util.List<String> asked = new java.util.ArrayList<>();
            rig.window.setInstallActions(asked::add);
            rig.show();
            Map<String, javafx.scene.control.Button> installs = rig.control("installButtons");
            installs.get("debug:python").fire();
            installs.get("debug:javascript").fire();
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("python", "javascript"), asked);
            assertFalse(tr("settings.debug.note").contains("scripts/"), "no repository folder in the hint");
        });
    }

    /**
     * The AI Agent page leads with what the agent is, marks the card of the client in use, and keeps each
     * client's setup note on that client's card; the LSP page names every server it has a card for.
     */
    @Test
    void theAgentPageMarksTheClientInUseAndTheLspHintNamesEveryServer() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.settings.setAiEnabled(true);
            rig.settings.setAgentClient("codex");
            rig.show();
            Region page = rig.page("AGENT");
            java.util.List<Label> inUse = SettingsRig.all(page, Label.class).stream()
                    .filter(l -> tr("settings.agent.active").equals(l.getText()) && l.isVisible())
                    .toList();
            org.junit.jupiter.api.Assertions.assertEquals(1, inUse.size(), "one card says In use");
            Map<String, TextField> commands = rig.control("agentCommandFields");
            assertTrue(isUnder(inUse.get(0), cardOf(commands.get("codex"))), "the Codex card");
            assertTrue(
                    SettingsRig.all(cardOf(commands.get("codex")), Label.class).stream()
                            .anyMatch(l -> tr("settings.agent.codexHint").equals(l.getText())),
                    "Codex's setup note is on its card");

            Map<String, TextField> lsp = rig.control("lspCommandFields");
            org.junit.jupiter.api.Assertions.assertEquals(
                    tr("settings.lsp.installedPrompt"), lsp.get("maven-pom").getPromptText());
            for (String name : java.util.List.of("Typst", "Astro", "pom.xml")) {
                assertTrue(tr("settings.lsp.hint").contains(name), name);
            }
        });
    }

    /**
     * The Markdown page keeps the pasted-image rows together, has no scroller inside its own scroller, and
     * leaves no blank gap where the project-config note would go.
     */
    @Test
    void theMarkdownPageReadsInOneScrollWithTheImageRowsTogether() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.show();
            Region page = rig.page("MARKDOWN");
            assertTrue(
                    SettingsRig.all(page, javafx.scene.control.ScrollPane.class).isEmpty(), "no nested scroller");
            java.util.List<String> titles = SettingsRig.all(page, Label.class).stream()
                    .filter(l -> l.getStyleClass().contains("settings-row-title"))
                    .map(Label::getText)
                    .toList();
            int folder = titles.indexOf(tr("settings.markdownAssetsFolder"));
            int name = titles.indexOf(tr("settings.markdownImageName"));
            int alt = titles.indexOf(tr("settings.markdownImageAltPrompt"));
            int toc = titles.indexOf(tr("settings.markdownTocLevels"));
            assertTrue(folder < name && name < alt && alt < toc, "image rows together, then the TOC: " + titles);
            Label note = rig.control("markdownLintConfigNoteLabel");
            assertFalse(note.isManaged(), "no blank row while there is no project config");
            assertTrue(note.getParent().getStyleClass().contains("settings-card-body"), "no row around it");
        });
    }

    /** A tool missing for a feature that is off gets the neutral pill; red is for a feature that is on. */
    @Test
    void aMissingToolIsRedOnlyWhileItsFeatureIsOn() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals("settings-git-found", SettingsWindow.pillClass(true, false));
        org.junit.jupiter.api.Assertions.assertEquals("settings-git-neutral", SettingsWindow.pillClass(false, false));
        org.junit.jupiter.api.Assertions.assertEquals("settings-git-missing", SettingsWindow.pillClass(false, true));
        FxTestSupport.runOnFx(() -> {
            rig.settings.setDebugSupport(false);
            rig.show();
            Map<String, ToolStatusRow> pills = rig.control("debugStatusLabels");
            Label python = pills.get("python").pill(); // no adapter manager in the rig: not found
            assertTrue(
                    python.getStyleClass().contains("settings-git-neutral"),
                    python.getStyleClass().toString());
            ((CheckBox) rig.control("debugCheck")).setSelected(true);
            assertTrue(
                    python.getStyleClass().contains("settings-git-missing"),
                    python.getStyleClass().toString());
        });
    }

    /**
     * The Web page chooses the preview browser (it was only ever remembered from the last preview) and says
     * whether the HTTP scripts' node is there; a build tool override naming no program says so.
     */
    @Test
    void theWebAndBuildToolPagesSayWhatTheyWillRun() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.settings.setHttpClientSupport(true);
            rig.settings.setHttpScriptsEnabled(true);
            rig.show();
            rig.page("WEB");
        });
        ComboBox<String> browser = rig.control("htmlBrowserCombo");
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (FxTestSupport.callOnFx(() -> browser.getItems().isEmpty())) { // detected off the FX thread
            if (System.nanoTime() > deadline) {
                throw new AssertionError("no browsers listed");
            }
            FxTestSupport.drainFx();
        }
        FxTestSupport.runOnFx(() -> {
            org.junit.jupiter.api.Assertions.assertEquals(com.editora.web.Browsers.SYSTEM_DEFAULT, browser.getValue());
            browser.setValue(com.editora.web.Browsers.SYSTEM_DEFAULT + "-other");
            org.junit.jupiter.api.Assertions.assertEquals(
                    com.editora.web.Browsers.SYSTEM_DEFAULT + "-other", rig.settings.getHtmlPreviewBrowser());

            SettingsRig.typeAndEnter(rig.control("httpNodeField"), "/no/such/dir/node");
            Label node = rig.<ToolStatusRow>control("httpNodeStatus").pill();
            org.junit.jupiter.api.Assertions.assertEquals(tr("settings.tool.notFound", "node"), node.getText());
            assertTrue(node.getStyleClass().contains("settings-git-missing"));

            Map<com.editora.build.BuildTool, TextField> overrides = rig.control("buildToolCommandFields");
            TextField maven = overrides.get(com.editora.build.BuildTool.MAVEN);
            Map<com.editora.build.BuildTool, ToolStatusRow> commands = rig.control("buildToolCommandStatus");
            Label mvn = commands.get(com.editora.build.BuildTool.MAVEN).pill();
            SettingsRig.typeAndEnter(maven, "/no/such/dir/mvnx");
            org.junit.jupiter.api.Assertions.assertEquals(tr("settings.tool.notFound", "mvnx"), mvn.getText());
            SettingsRig.typeAndEnter(maven, "");
            assertTrue(mvn.getText().startsWith("mvn"), "back to the default command: " + mvn.getText());
        });
    }

    /**
     * Every tool row on these pages is the one kit row (D13): path, then "Detected" with a pill worded
     * "<tool>: found (<path>)" / "<tool>: not found". Diagrams has no installer, so it links to the docs.
     */
    @Test
    void everyToolRowIsWordedAndBuiltTheSameWay() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals(
                "dot: found (/usr/bin/dot)", ToolStatusRow.text("dot", true, "/usr/bin/dot"));
        org.junit.jupiter.api.Assertions.assertEquals("dot: found", ToolStatusRow.text("dot", true, ""));
        org.junit.jupiter.api.Assertions.assertEquals("dot: not found", ToolStatusRow.text("dot", false, "/x"));
        java.util.List<String> opened = new java.util.ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            rig.window.setOpenUrl(opened::add);
            rig.show();
            for (String page :
                    java.util.List.of("LSP", "DEBUG", "MERMAID", "DIAGRAMS", "TYPST", "BUILD_TOOLS", "WEB", "AGENT")) {
                assertFalse(
                        SettingsRig.all(rig.page(page), javafx.scene.layout.HBox.class).stream()
                                .noneMatch(h -> h.getStyleClass().contains("settings-tool-status")),
                        page + " has the kit's tool row");
            }
            java.util.Map<String, ToolStatusRow> debug = rig.control("debugStatusLabels");
            org.junit.jupiter.api.Assertions.assertEquals(
                    tr("settings.tool.notFound", "debugpy"),
                    debug.get("python").pill().getText());
            Region diagrams = rig.page("DIAGRAMS");
            assertTrue(
                    SettingsRig.all(diagrams, javafx.scene.control.Button.class).stream()
                            .noneMatch(b -> tr("settings.install.button").equals(b.getText())),
                    "no installer to offer");
            SettingsRig.button(diagrams, tr("settings.diagram.howToInstall")).fire();
            org.junit.jupiter.api.Assertions.assertEquals(
                    java.util.List.of(com.editora.AppInfo.HOMEPAGE + "/features/diagrams/"), opened);
            ToolStatusRow dot = rig.control("dotStatus");
            dot.show(true, "/usr/bin/dot", true);
            org.junit.jupiter.api.Assertions.assertEquals(
                    "dot: found (/usr/bin/dot)", dot.pill().getText());
            assertTrue(
                    SettingsRig.all(dot.node(), javafx.scene.control.Hyperlink.class).stream()
                            .noneMatch(javafx.scene.Node::isVisible),
                    "the docs link goes once dot is found");
        });
    }

    private static javafx.scene.Node cardOf(javafx.scene.Node n) {
        javafx.scene.Node c = n;
        while (c != null && !c.getStyleClass().contains("settings-card")) {
            c = c.getParent();
        }
        return c;
    }

    private static boolean isUnder(javafx.scene.Node n, javafx.scene.Node ancestor) {
        for (javafx.scene.Node c = n; c != null; c = c.getParent()) {
            if (c == ancestor) {
                return true;
            }
        }
        return false;
    }
}
