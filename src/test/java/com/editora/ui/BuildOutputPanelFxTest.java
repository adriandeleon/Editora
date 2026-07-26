package com.editora.ui;

import java.util.List;

import javafx.scene.control.Tab;

import com.editora.build.OutputStyle;
import com.editora.process.CommandLog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** Owner tokens standing in for the Git / GitHub CLI transcripts. */
    private static final Object GIT = new Object();

    private static final Object GH = new Object();

    private static CommandLog.Entry entry(String... argv) {
        return new CommandLog.Entry(List.of(argv), 0, "ok\n", "", 7);
    }

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

    @Test
    void aLoggedCommandGetsItsOwnTabWithTheEchoOutputAndFooter() throws Exception {
        String text = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            assertFalse(p.hasTabs(), "nothing has run yet");
            p.logCommand(GIT, "Git", entry("git", "push"));
            assertTrue(p.hasTabs());
            assertEquals(List.of("Git"), tabTitles(p));
            return consoleText((BuildToolPanel) p.getTabs().get(0).getContent());
        });
        assertTrue(text.contains("$ git push"), "the command is echoed");
        assertTrue(text.contains("ok"), "its output is shown");
        assertTrue(text.contains("exit 0"), "and its exit code");
    }

    /** The whole point of a transcript: a second command must add to it, not replace it. */
    @Test
    void aSecondCommandAppendsInsteadOfClearing() throws Exception {
        String text = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            p.logCommand(GIT, "Git", entry("git", "add", "-A"));
            p.logCommand(GIT, "Git", entry("git", "commit", "-m", "x"));
            assertEquals(1, p.getTabs().size(), "same owner keeps one tab");
            return consoleText((BuildToolPanel) p.getTabs().get(0).getContent());
        });
        assertTrue(text.contains("$ git add -A"), "the earlier command is still there");
        assertTrue(text.contains("$ git commit -m x"), "and the later one was appended");
    }

    /**
     * Unlike {@code started}, logging must not steal the selection — a git command running behind a build
     * the user is watching would otherwise yank them off the build's tab.
     */
    @Test
    void loggingDoesNotStealTheSelectionFromARunningBuild() throws Exception {
        String selected = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            p.logCommand(GIT, "Git", entry("git", "status"));
            p.started(MAVEN, "Maven", "mvn test", OutputStyle.passthrough(), () -> {});
            p.logCommand(GIT, "Git", entry("git", "fetch"));
            return p.getSelectionModel().getSelectedItem().getText();
        });
        assertEquals("Maven", selected);
    }

    /** Git, GitHub and a streaming CI log are three different owners — so three tabs that can't clobber each other. */
    @Test
    void gitGithubAndBuildsCoexistInSeparateTabs() throws Exception {
        List<String> titles = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            p.logCommand(GIT, "Git", entry("git", "push"));
            p.logCommand(GH, "GitHub", entry("gh", "pr", "list"));
            p.started(MAVEN, "Maven", "mvn test", OutputStyle.passthrough(), () -> {});
            BuildToolPanel git = (BuildToolPanel) p.getTabs().get(0).getContent();
            assertTrue(consoleText(git).contains("$ git push"), "the Maven run left the Git transcript alone");
            assertFalse(consoleText(git).contains("gh pr list"), "gh output went to its own tab");
            return tabTitles(p);
        });
        assertEquals(List.of("Git", "GitHub", "Maven"), titles);
    }

    /** Reads the RichTextFX console text out of a {@link BuildToolPanel} via its private {@code output} field. */
    private static String consoleText(BuildToolPanel panel) {
        org.fxmisc.richtext.CodeArea output = FxTestSupport.field(panel, "output");
        return output.getText();
    }
}
