package com.editora.ui;

import java.util.List;

import javafx.scene.control.Tab;

import com.editora.build.OutputStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the tab-per-tool routing of {@link BuildOutputPanel}: each build tool that runs gets its own console
 * tab (titled with the tool name), a second run of the same tool reuses its tab, the running tool's tab is
 * auto-selected, and {@code appendOutput} routes to the owner's console — so two concurrent builds stream into
 * separate tabs instead of interleaving.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildOutputPanelFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Distinct owner tokens standing in for two different BuildCoordinators. */
    private static final Object MAVEN = new Object();

    private static final Object NPM = new Object();

    private static List<String> tabTitles(BuildOutputPanel p) {
        return p.getTabs().stream().map(Tab::getText).toList();
    }

    @Test
    void eachToolGetsItsOwnTabAndTheRunningOneIsSelected() throws Exception {
        List<String> titles = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            p.started(MAVEN, "Maven", "mvn clean", OutputStyle.passthrough(), () -> {});
            p.started(NPM, "npm", "npm run build", OutputStyle.passthrough(), () -> {});
            // The most recently started build's tab is the selected one.
            assertEquals("npm", p.getSelectionModel().getSelectedItem().getText());
            return tabTitles(p);
        });
        assertEquals(List.of("Maven", "npm"), titles, "one tab per tool, titled by tool name");
    }

    @Test
    void rerunningTheSameToolReusesItsTab() throws Exception {
        int tabCount = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            p.started(MAVEN, "Maven", "mvn clean", OutputStyle.passthrough(), () -> {});
            Tab first = p.getTabs().get(0);
            p.started(MAVEN, "Maven", "mvn package", OutputStyle.passthrough(), () -> {});
            assertSame(first, p.getTabs().get(0), "same tab instance reused for a second Maven run");
            return p.getTabs().size();
        });
        assertEquals(1, tabCount, "re-running a tool must not add a second tab");
    }

    @Test
    void outputRoutesToTheOwningToolsConsoleNotTheOther() throws Exception {
        String[] texts = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            p.started(MAVEN, "Maven", "mvn clean", OutputStyle.passthrough(), () -> {});
            p.started(NPM, "npm", "npm run build", OutputStyle.passthrough(), () -> {});
            p.appendOutput(MAVEN, "compiling maven sources", false);
            p.appendOutput(NPM, "bundling npm assets", false);
            BuildToolPanel maven = (BuildToolPanel) p.getTabs().get(0).getContent();
            BuildToolPanel npm = (BuildToolPanel) p.getTabs().get(1).getContent();
            return new String[] {consoleText(maven), consoleText(npm)};
        });
        org.junit.jupiter.api.Assertions.assertTrue(
                texts[0].contains("maven") && !texts[0].contains("npm assets"), "Maven tab has only Maven output");
        org.junit.jupiter.api.Assertions.assertTrue(
                texts[1].contains("npm assets") && !texts[1].contains("maven sources"), "npm tab has only npm output");
    }

    /** Reads the RichTextFX console text out of a {@link BuildToolPanel} via its private {@code output} field. */
    private static String consoleText(BuildToolPanel panel) {
        org.fxmisc.richtext.CodeArea output = FxTestSupport.field(panel, "output");
        return output.getText();
    }
}
