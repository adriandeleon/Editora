package com.editora.ui;

import java.util.Collection;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Tab;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;

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

    @Test
    void explicitlySelectingATranscriptTabShowsTheGitTabAfterACommand() throws Exception {
        String selected = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            p.logCommand(GIT, "Git", entry("git", "pull"));
            p.started(MAVEN, "Maven", "mvn test", OutputStyle.passthrough(), () -> {});
            p.selectTab(GIT);
            return p.getSelectionModel().getSelectedItem().getText();
        });
        assertEquals("Git", selected);
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

    @Test
    void gitTranscriptColorsStatusAndDiffOutputAndMarksFilePathsAsLinks() throws Exception {
        boolean[] flags = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel p = new BuildOutputPanel();
            p.logCommand(
                    GIT,
                    "Git",
                    new CommandLog.Entry(
                            List.of("git", "diff"), 0, "\tmodified:   src/App.java\n+added line\n", "", 7));
            org.fxmisc.richtext.CodeArea out =
                    FxTestSupport.field((BuildToolPanel) p.getTabs().get(0).getContent(), "output");
            Collection<String> status = out.getStyleOfChar(out.getText().indexOf("modified:"));
            Collection<String> file = out.getStyleOfChar(out.getText().indexOf("src/App.java"));
            Collection<String> added = out.getStyleOfChar(out.getText().indexOf("+added"));
            return new boolean[] {
                status.contains("git-output-modified"), file.contains("console-url"), added.contains("diff-inserted")
            };
        });
        assertTrue(flags[0], "Git status entries use their semantic status color");
        assertTrue(flags[1], "a Git file path is styled as a clickable link");
        assertTrue(flags[2], "unified diff additions use the diff insertion color");
    }

    @Test
    void gitTranscriptColorsDiffstatSummaryAndModesWithoutLosingFileLinks() throws Exception {
        boolean[] flags = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel panel = new BuildOutputPanel();
            panel.logCommand(
                    GIT,
                    "Git",
                    new CommandLog.Entry(
                            List.of("git", "pull", "--ff-only"),
                            0,
                            " src/Added.java | 13 +++++----\n"
                                    + " 2 files changed, 10 insertions(+), 3 deletions(-)\n"
                                    + " create mode 100644 src/New.java\n"
                                    + " delete mode 100644 src/Old.java\n"
                                    + " + 1234...5678 topic -> origin/topic (forced update)\n",
                            "",
                            7));
            org.fxmisc.richtext.CodeArea out =
                    FxTestSupport.field((BuildToolPanel) panel.getTabs().get(0).getContent(), "output");
            String text = out.getText();
            int graph = text.indexOf("| 13 +++++----");
            return new boolean[] {
                out.getStyleOfChar(text.indexOf("src/Added.java")).contains("console-url"),
                out.getStyleOfChar(graph + 2).contains("git-output-count"),
                out.getStyleOfChar(graph + 5).contains("diff-inserted"),
                out.getStyleOfChar(graph + 10).contains("diff-deleted"),
                out.getStyleOfChar(text.indexOf("2 files changed")).contains("git-output-summary"),
                out.getStyleOfChar(text.indexOf("10 insertions")).contains("diff-inserted"),
                out.getStyleOfChar(text.indexOf("3 deletions")).contains("diff-deleted"),
                out.getStyleOfChar(text.indexOf("create mode")).contains("git-output-added")
                        && out.getStyleOfChar(text.indexOf("create mode")).contains("git-output-mode"),
                out.getStyleOfChar(text.indexOf("src/New.java")).contains("console-url"),
                out.getStyleOfChar(text.indexOf("delete mode")).contains("git-output-deleted")
                        && out.getStyleOfChar(text.indexOf("delete mode")).contains("git-output-mode"),
                out.getStyleOfChar(text.indexOf("forced update")).contains("git-output-forced")
            };
        });
        for (int i = 0; i < flags.length; i++) {
            assertTrue(flags[i], "Git transcript semantic span " + i);
        }
    }

    @Test
    void gitDiffstatRendersGreenAndRedUnderEditorTheme() throws Exception {
        Color[] fills = FxTestSupport.callOnFx(() -> {
            BuildOutputPanel panel = new BuildOutputPanel();
            panel.logCommand(
                    GIT, "Git", new CommandLog.Entry(List.of("git", "pull"), 0, " src/Example.java | 4 ++--\n", "", 7));
            panel.selectTab(GIT);
            Stage stage = new Stage();
            try {
                Scene scene = new Scene(new StackPane(panel), 900, 300);
                scene.getStylesheets()
                        .addAll(
                                getClass()
                                        .getResource("/com/editora/styles/app.css")
                                        .toExternalForm(),
                                getClass()
                                        .getResource("/com/editora/styles/syntax.css")
                                        .toExternalForm(),
                                getClass()
                                        .getResource("/com/editora/styles/editor-themes/editora-light.css")
                                        .toExternalForm());
                stage.setScene(scene);
                stage.show();
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                org.fxmisc.richtext.CodeArea out = FxTestSupport.field(
                        (BuildToolPanel) panel.getTabs().get(0).getContent(), "output");
                Text added = out.lookupAll(".diff-inserted").stream()
                        .filter(Text.class::isInstance)
                        .map(Text.class::cast)
                        .findFirst()
                        .orElseThrow();
                Text removed = out.lookupAll(".diff-deleted").stream()
                        .filter(Text.class::isInstance)
                        .map(Text.class::cast)
                        .findFirst()
                        .orElseThrow();
                return new Color[] {(Color) added.getFill(), (Color) removed.getFill()};
            } finally {
                stage.hide();
            }
        });
        assertTrue(fills[0].getGreen() > fills[0].getRed() * 1.5, "additions render green: " + fills[0]);
        assertTrue(fills[1].getRed() > fills[1].getGreen() * 1.5, "deletions render red: " + fills[1]);
    }

    /** Reads the RichTextFX console text out of a {@link BuildToolPanel} via its private {@code output} field. */
    private static String consoleText(BuildToolPanel panel) {
        org.fxmisc.richtext.CodeArea output = FxTestSupport.field(panel, "output");
        return output.getText();
    }
}
