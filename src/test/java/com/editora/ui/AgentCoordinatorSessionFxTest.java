package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.agent.FakeAcpAgent;
import com.editora.config.AgentSessionHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent chat end to end, against {@link FakeAcpAgent} — a child JVM that speaks the protocol and never
 * reaches a model: starting a session on the first prompt, what a turn shows, stopping and failing, the
 * model/mode/agent/resume pickers, the pop-out window, and the file requests an agent makes during a turn.
 */
@Tag("fx")
class AgentCoordinatorSessionFxTest {

    private static final long WAIT_SECONDS = 30;

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // ---- a prompt turn ----------------------------------------------------------------------------------

    @Test
    void theFirstPromptStartsASessionAndShowsWhatTheAgentSends(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.prompt("say hi #stream");

            List<String> lines = rig.transcript();
            assertEquals("❯ say hi #stream", lines.get(0));
            assertEquals("Hello, world.", lines.get(1).strip(), "neighbouring chunks are one message");
            assertEquals("⚙ ls -la", lines.get(2));
            assertEquals("⚙ ✗ " + tr("agent.toolFailed"), lines.get(3), "only a failed tool update adds a line");
            assertEquals("Done.", lines.get(4).strip());
            assertEquals(5, lines.size(), "thoughts and unknown updates are not shown: " + lines);

            assertEquals(List.of("☑ Read the file", "◐ Change it", "☐ Run the tests"), rig.plan());
            assertEquals(tr("agent.clientLabel", "Claude Code"), rig.label("agentLabel"));
            assertEquals(tr("agent.modelLabel", "Model One"), rig.label("modelLabel"));
            assertEquals(tr("agent.modeLabel", "Code"), rig.label("modeLabel"), "the agent changed mode mid-turn");
            assertEquals(tr("agent.idle"), rig.label("status"));

            Remembered r = rig.ops.remembered.get(0);
            assertEquals("sess-1", r.sessionId());
            assertEquals(root.toString(), r.cwd());
            assertEquals("say hi #stream", r.label());
            assertEquals("claude", r.agentId());
        }
    }

    @Test
    void laterPromptsReuseTheSession(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.prompt("one #echo");
            rig.prompt("two #nostop");
            assertEquals(2, rig.ops.remembered.size());
            assertEquals("sess-1", rig.ops.remembered.get(1).sessionId());
            assertEquals("two #nostop", rig.ops.remembered.get(1).label());
            assertEquals(
                    List.of("❯ one #echo", "PROMPT[[one #echo]]", "❯ two #nostop"),
                    rig.transcript().stream().map(String::strip).toList());
        }
    }

    @Test
    void theActiveBufferIsSentAsContextButNotMergedIntoTheEchoedPrompt(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            Path file = Files.createDirectories(root.resolve("src")).resolve("a.txt");
            rig.host.active = rig.buffer(file, "one\ntwo\nthree\n");
            FxTestSupport.runOnFx(() -> rig.host.active.getArea().selectRange(2, 0, 2, 5));

            rig.prompt("explain #echo");

            String header = tr("agent.context.header", Path.of("src", "a.txt").toString(), 3)
                    + tr("agent.context.selected", "three");
            List<String> lines = rig.transcript();
            assertEquals("❯ explain #echo", lines.get(0));
            assertEquals(header, lines.get(1));
            assertEquals("PROMPT[[" + header + "⏎⏎explain #echo]]", lines.get(2).strip());

            // Turned off, nothing is prefixed.
            rig.host.settings.setAgentIncludeContext(false);
            rig.prompt("again #echo");
            assertEquals("PROMPT[[again #echo]]", rig.transcript().get(4).strip());
        }
    }

    @Test
    void aBufferOutsideTheSessionFolderIsNamedByItsFullPathAndAnUnsavedOneByItsTitle(
            @TempDir Path root, @TempDir Path elsewhere) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            Path outside = elsewhere.resolve("other.txt");
            rig.host.active = rig.buffer(outside, "x");
            rig.prompt("#echo");
            assertEquals(
                    tr("agent.context.header", outside.toString(), 1),
                    rig.transcript().get(1));

            EditorBuffer untitled = rig.buffer(null, "draft");
            String title = FxTestSupport.callOnFx(untitled::getTitle);
            rig.host.active = untitled;
            rig.prompt("#echo");
            assertEquals(tr("agent.context.header", title, 1), rig.transcript().get(4));

            // A remote buffer has a path, but not one the agent could resolve.
            EditorBuffer remote = rig.buffer(root.resolve("r.txt"), "r");
            String remoteTitle = FxTestSupport.callOnFx(remote::getTitle);
            rig.host.active = remote;
            rig.host.local = false;
            rig.prompt("#echo");
            assertEquals(
                    tr("agent.context.header", remoteTitle, 1), rig.transcript().get(7));
        }
    }

    @Test
    void withoutAProjectTheSessionRunsInTheActiveFilesFolderElseTheHomeFolder(@TempDir Path dir) throws Exception {
        try (Rig rig = new Rig(null, "ok")) {
            rig.prompt("#nostop");
            assertEquals(
                    System.getProperty("user.home"), rig.ops.remembered.get(0).cwd());
        }
        try (Rig rig = new Rig(null, "ok")) {
            rig.host.active = rig.buffer(dir.resolve("f.txt"), "x");
            rig.prompt("#nostop");
            assertEquals(
                    dir.toAbsolutePath().toString(), rig.ops.remembered.get(0).cwd());
        }
    }

    @Test
    void stoppingATurnCancelsItAndKeepsTheSession(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            FxTestSupport.runOnFx(rig.coordinator::stopTurn); // nothing is running: nothing happens
            assertEquals(List.of(), rig.transcript());

            rig.send("long job #hang");
            rig.awaitRemembered(1); // the session exists and the prompt is on its way
            assertEquals(tr("agent.running"), rig.label("status"));
            FxTestSupport.runOnFx(rig.coordinator::stopTurn);
            rig.awaitIdle();

            List<String> lines = rig.transcript();
            assertEquals(tr("agent.turnCancelled"), lines.get(lines.size() - 1));
            assertEquals("working", lines.get(1).strip());

            rig.prompt("still there #echo");
            assertEquals("sess-1", rig.ops.remembered.get(1).sessionId());
        }
    }

    @Test
    void thePromptFieldSendsOnEnterAndTheSendButtonBecomesStopWhileATurnRuns(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            javafx.scene.control.TextArea input = FxTestSupport.field(rig.panel, "input");
            Button send = FxTestSupport.field(rig.panel, "sendButton");

            // Nothing typed, or Shift+Enter (a new line): nothing is sent.
            FxTestSupport.runOnFx(() -> {
                rig.key(input, KeyCode.ENTER, false);
                input.setText("  \n ");
                rig.key(input, KeyCode.ENTER, false);
                input.setText("typed #echo");
                rig.key(input, KeyCode.ENTER, true);
                rig.key(input, KeyCode.ESCAPE, false); // idle: Esc is not "stop"
            });
            assertEquals(List.of(), rig.transcript());
            assertEquals("typed #echo", FxTestSupport.callOnFx(input::getText));

            rig.idle.clear();
            FxTestSupport.runOnFx(() -> rig.key(input, KeyCode.ENTER, false));
            rig.awaitIdle();
            assertEquals("", FxTestSupport.callOnFx(input::getText), "the field is cleared once sent");
            assertEquals("PROMPT[[typed #echo]]", rig.lastLine().strip());

            // While a turn runs the same button stops it, and so does Esc in the field.
            assertEquals(tr("agent.send"), FxTestSupport.callOnFx(send::getText));
            FxTestSupport.runOnFx(() -> {
                input.setText("#hang one");
                send.fire();
            });
            rig.awaitRemembered(2);
            assertEquals(tr("agent.stop"), FxTestSupport.callOnFx(send::getText));
            FxTestSupport.runOnFx(() -> {
                input.setText("ignored while busy");
                rig.key(input, KeyCode.ENTER, false);
            });
            rig.idle.clear();
            FxTestSupport.runOnFx(send::fire);
            rig.awaitIdle();
            assertEquals(tr("agent.turnCancelled"), rig.lastLine());
            assertEquals(tr("agent.send"), FxTestSupport.callOnFx(send::getText));

            FxTestSupport.runOnFx(() -> {
                input.setText("#hang two");
                send.fire();
            });
            rig.awaitRemembered(3);
            rig.idle.clear();
            FxTestSupport.runOnFx(() -> rig.key(input, KeyCode.ESCAPE, false));
            rig.awaitIdle();
            assertEquals(tr("agent.turnCancelled"), rig.lastLine());
            assertEquals(
                    3,
                    rig.transcript().stream().filter(l -> l.startsWith("❯ ")).count(),
                    "the busy Enter sent nothing");
        }
    }

    @Test
    void aTurnTheAgentRefusesIsShownAndReported(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.prompt("#fail");
            assertEquals("✗ model overloaded", rig.lastLine());
            assertEquals(tr("status.agent.failed", "model overloaded"), rig.host.lastStatus());

            rig.prompt("#fail-bare");
            assertEquals("✗ agent error", rig.lastLine(), "an error without a message still says something");
        }
    }

    @Test
    void anAgentThatDiesMidTurnIsReportedAndTheNextPromptStartsAnother(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.send("#die");
            String exited = rig.awaitLine(line -> line.startsWith("(agent exited"));
            assertNotNull(exited);
            assertTrue(
                    rig.transcript().contains("✗ Agent process exited"),
                    rig.transcript().toString());
            assertEquals(tr("agent.idle"), rig.label("status"));

            rig.prompt("back #echo");
            assertEquals("PROMPT[[back #echo]]", rig.lastLine().strip());
            assertEquals(2, rig.ops.remembered.size());
        }
    }

    // ---- sessions that do not start ---------------------------------------------------------------------

    @Test
    void aCommandThatCannotBeStartedIsNamed(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            String missing = root.resolve("no-such-agent").toString();
            rig.host.settings.setAgentCommand(missing);
            rig.prompt("hello");
            String message = tr("status.agent.startFailed", missing);
            assertEquals("✗ " + message, rig.lastLine());
            assertEquals(tr("status.agent.failed", message), rig.host.lastStatus());
            assertEquals(List.of(), rig.ops.remembered, "no session, nothing to resume");
        }
    }

    @Test
    void aCodexAdapterThatCannotBeStartedExplainsItsSetup(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.host.settings.setAgentClient("codex");
            rig.host.settings.setCodexAgentCommand(root.resolve("no-codex").toString());
            rig.prompt("hello");
            assertEquals("✗ " + tr("status.ai.codexSetup"), rig.lastLine());
        }
    }

    @Test
    void theLocalModelAgentNeedsAModelBeforeAnythingIsStarted(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.host.settings.setAgentClient("lmstudio");
            rig.host.settings.setLmstudioAgentCommand(FakeAcpAgent.commandLine("ok"));
            rig.host.settings.setAiLmStudioModel("");
            rig.prompt("hello");
            assertEquals("✗ " + tr("status.agent.lmstudioModelRequired"), rig.lastLine());

            // With a model configured the same command starts and answers.
            rig.host.settings.setAiLmStudioModel("local-model");
            rig.prompt("hello #echo");
            assertEquals("PROMPT[[hello #echo]]", rig.lastLine().strip());
            assertEquals("lmstudio", rig.ops.remembered.get(0).agentId());
        }
    }

    @Test
    void anAgentThatCreatesNoSessionOrRefusesOneIsReported(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "nosession")) {
            rig.prompt("hello");
            assertEquals("✗ " + tr("status.agent.noSession"), rig.lastLine());
            assertEquals(List.of(), rig.ops.remembered);
        }
        try (Rig rig = new Rig(root, "newfail")) {
            rig.prompt("hello");
            assertEquals("✗ not logged in", rig.lastLine());
        }
        try (Rig rig = new Rig(root, "exit")) {
            rig.prompt("hello");
            assertTrue(
                    rig.transcript().stream().anyMatch(l -> l.startsWith("✗ Agent process")),
                    rig.transcript().toString());
        }
    }

    @Test
    void aDisabledAgentDoesNothingButSaySo(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.host.settings.setAgentSupport(false);
            assertFalse(rig.coordinator.isEnabled());
            FxTestSupport.runOnFx(() -> rig.coordinator.sendPrompt("hello"));
            assertEquals(tr("status.agent.disabled"), rig.host.lastStatus());
            assertEquals(List.of(), rig.transcript());

            rig.host.statuses.clear();
            FxTestSupport.runOnFx(() -> {
                rig.coordinator.newSession();
                rig.coordinator.pickModel();
                rig.coordinator.pickMode();
                rig.coordinator.pickAgentClient();
                rig.coordinator.resumeSessionPicker();
                rig.coordinator.toggleToolWindow();
            });
            // Each command answers with the same explanation; none of them acts.
            assertEquals(
                    java.util.Collections.nCopies(6, tr("status.agent.disabled")), new ArrayList<>(rig.host.statuses));
            assertNull(rig.picker());
            assertEquals(0, rig.ops.toggles.get());

            // The master AI switch and Simple mode turn it off as well.
            rig.host.settings.setAgentSupport(true);
            assertTrue(rig.coordinator.isEnabled());
            rig.host.settings.setAiEnabled(false);
            assertFalse(rig.coordinator.isEnabled());
            rig.host.settings.setAiEnabled(true);
            rig.host.simple = true;
            assertFalse(rig.coordinator.isEnabled());
        }
    }

    // ---- model and mode ---------------------------------------------------------------------------------

    @Test
    void theModelAndModePickersSwitchTheRunningSession(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            assertEquals(tr("status.agent.noModels"), rig.host.lastStatus(), "no session yet");
            FxTestSupport.runOnFx(rig.coordinator::pickMode);
            assertEquals(tr("status.agent.noModes"), rig.host.lastStatus());

            rig.prompt("#nostop");
            assertEquals(tr("agent.modelLabel", "Model One"), rig.label("modelLabel"));
            assertEquals(tr("agent.modeLabel", "Ask"), rig.label("modeLabel"));

            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            assertEquals(List.of("Model One", "Model Two", "Broken"), rig.pickerRows());
            rig.choose(1);
            rig.awaitLabel("modelLabel", tr("agent.modelLabel", "Model Two"));

            FxTestSupport.runOnFx(rig.coordinator::pickMode);
            assertEquals(List.of("Ask", "Code", "Locked"), rig.pickerRows());
            rig.choose(1);
            rig.awaitLabel("modeLabel", tr("agent.modeLabel", "Code"));

            // A switch the agent refuses leaves the header alone and is reported.
            rig.host.statuses.clear();
            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            rig.choose(2);
            assertEquals(tr("status.agent.failed", "no such model"), rig.host.awaitStatus());
            assertEquals(tr("agent.modelLabel", "Model Two"), rig.label("modelLabel"));
            FxTestSupport.runOnFx(rig.coordinator::pickMode);
            rig.choose(2);
            assertEquals(tr("status.agent.failed", "mode is locked"), rig.host.awaitStatus());
            assertEquals(tr("agent.modeLabel", "Code"), rig.label("modeLabel"));
        }
    }

    @Test
    void aSessionWithoutCatalogsHasNothingToPick(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "bare")) {
            rig.prompt("#nostop");
            assertEquals(tr("agent.modelLabel", "—"), rig.label("modelLabel"));
            assertEquals(tr("agent.modeLabel", "—"), rig.label("modeLabel"));
            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            assertEquals(tr("status.agent.noModels"), rig.host.lastStatus());
            assertNull(rig.picker());
        }
    }

    @Test
    void modelsOfferedAsConfigOptionsAreSwitchedThroughThem(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "configopts")) {
            rig.prompt("#nostop");
            assertEquals(tr("agent.modelLabel", "Model One"), rig.label("modelLabel"));
            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            assertEquals(List.of("Model One", "Model Two"), rig.pickerRows());
            rig.choose(1);
            rig.awaitLabel("modelLabel", tr("agent.modelLabel", "Model Two"));
            FxTestSupport.runOnFx(rig.coordinator::pickMode);
            rig.choose(1);
            rig.awaitLabel("modeLabel", tr("agent.modeLabel", "Code"));
        }
    }

    @Test
    void aConfigChangeTheAgentAnnouncesUpdatesTheHeaderForItsOwnSessionOnly(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.prompt("#config-other");
            assertEquals(tr("agent.modelLabel", "Model One"), rig.label("modelLabel"));
            assertEquals(tr("agent.modeLabel", "Ask"), rig.label("modeLabel"));

            rig.prompt("#config");
            assertEquals(tr("agent.modelLabel", "Model Two"), rig.label("modelLabel"));
            assertEquals(tr("agent.modeLabel", "Code"), rig.label("modeLabel"));
        }
    }

    // ---- which agent, and resuming ----------------------------------------------------------------------

    @Test
    void pickingAnotherAgentSavesTheChoiceAndEndsTheSession(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.prompt("#nostop");
            FxTestSupport.runOnFx(rig.coordinator::pickAgentClient);
            assertEquals(
                    com.editora.agent.AcpAgentRegistry.all().stream()
                            .map(com.editora.agent.AcpAgentRegistry.AgentDef::displayName)
                            .toList(),
                    rig.pickerRows());
            rig.choose(1); // Gemini CLI

            assertEquals("gemini", rig.host.settings.getAgentClient());
            assertEquals(1, rig.host.saves.get());
            assertEquals(tr("status.agent.switched", "Gemini CLI"), rig.host.lastStatus());
            assertEquals(tr("agent.clientLabel", "Gemini CLI"), rig.label("agentLabel"));
            assertEquals(List.of(), rig.transcript(), "the old conversation is gone with its agent");
            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            assertEquals(tr("status.agent.noModels"), rig.host.lastStatus(), "the session was ended");

            // Choosing the agent already in use, or none, changes nothing.
            rig.host.statuses.clear();
            FxTestSupport.runOnFx(() -> {
                rig.coordinator.switchAgentClient(com.editora.agent.AcpAgentRegistry.AgentDef.GEMINI);
                rig.coordinator.switchAgentClient(null);
            });
            assertEquals(1, rig.host.saves.get());
            assertEquals(List.of(), new ArrayList<>(rig.host.statuses));
        }
    }

    @Test
    void resumingAPastSessionRestartsItsAgentWithThatSession(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            FxTestSupport.runOnFx(rig.coordinator::resumeSessionPicker);
            assertEquals(tr("status.agent.noHistory"), rig.host.lastStatus());

            rig.host.settings.setGeminiAgentCommand(FakeAcpAgent.commandLine("ok"));
            long now = java.time.Instant.now().getEpochSecond();
            FxTestSupport.runOnFx(() -> rig.ops.history.setAll(
                    new AgentSessionHistory.Entry("old-9", root.toString(), "Fix the parser", now - 7200, "gemini"),
                    new AgentSessionHistory.Entry("old-3", root.toString(), " ", now - 60, "claude")));

            String savedDefault = rig.host.settings.getAgentClient();
            rig.prompt("first #nostop");
            FxTestSupport.runOnFx(rig.coordinator::resumeSessionPicker);
            assertEquals(List.of("Fix the parser", tr("agent.untitledSession")), rig.pickerRows());
            rig.host.statuses.clear();
            rig.choose(0);
            assertEquals(tr("status.agent.resumed", "Fix the parser"), rig.host.awaitStatus());

            // The session's own agent runs it, for this conversation only: the saved default is unchanged.
            assertEquals(tr("agent.clientLabel", "Gemini CLI"), rig.label("agentLabel"));
            assertEquals(savedDefault, rig.host.settings.getAgentClient());
            assertEquals(0, rig.host.saves.get());
            assertEquals(List.of(), rig.transcript(), "a resumed chat starts with an empty transcript");
            Remembered resumed = rig.ops.remembered.get(rig.ops.remembered.size() - 1);
            assertEquals("old-9", resumed.sessionId());
            assertEquals("Fix the parser", resumed.label());
            assertEquals("gemini", resumed.agentId());

            // The next prompt goes to the resumed session.
            rig.prompt("go on #echo");
            assertEquals(
                    "old-9",
                    rig.ops.remembered.get(rig.ops.remembered.size() - 1).sessionId());

            // Resuming the session that is already running starts nothing.
            int before = rig.ops.remembered.size();
            FxTestSupport.runOnFx(rig.coordinator::resumeSessionPicker);
            rig.host.statuses.clear();
            rig.choose(0);
            assertEquals(tr("status.agent.resumed", "Fix the parser"), rig.host.lastStatus());
            assertEquals(before, rig.ops.remembered.size());
            assertEquals(2, rig.transcript().size(), "the running conversation is left alone");
        }
    }

    @Test
    void aSessionTheAgentNoLongerKnowsIsReportedAndLeavesNoSession(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "resumefail")) {
            FxTestSupport.runOnFx(() -> rig.ops.history.setAll(
                    new AgentSessionHistory.Entry("gone-1", root.toString(), "Old chat", 1_000L, "claude")));
            FxTestSupport.runOnFx(rig.coordinator::resumeSessionPicker);
            rig.choose(0);
            assertEquals(tr("status.agent.failed", "unknown session"), rig.host.awaitStatus());
            assertEquals(List.of(), rig.ops.remembered);
            assertEquals(tr("agent.idle"), rig.label("status"));
            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            assertEquals(tr("status.agent.noModels"), rig.host.lastStatus());
        }
    }

    @Test
    void newSessionClearsTheChatAndForgetsTheSession(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.prompt("one #echo");
            FxTestSupport.runOnFx(rig.coordinator::newSession);
            assertEquals(tr("status.agent.newSession"), rig.host.lastStatus());
            assertEquals(List.of(), rig.transcript());
            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            assertEquals(tr("status.agent.noModels"), rig.host.lastStatus());

            // The agent the editor itself stopped is not reported as having exited, in the new chat or at all.
            rig.prompt("two #echo");
            assertEquals(
                    List.of("❯ two #echo", "PROMPT[[two #echo]]"),
                    rig.transcript().stream().map(String::strip).toList());
            rig.prompt("three #echo");
            assertEquals(3, rig.ops.remembered.size());
            assertEquals(4, rig.transcript().size());
        }
    }

    @Test
    void turningTheFeatureOffEndsTheSession(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            rig.prompt("one #nostop");
            rig.host.settings.setFontFamily("Monospaced");
            rig.host.settings.setFontSize(20);
            FxTestSupport.runOnFx(rig.coordinator::applySupport); // still on: only the font follows the settings
            rig.prompt("two #nostop");
            assertEquals(2, rig.ops.remembered.size());
            Label line = (Label) FxTestSupport.callOnFx(
                    () -> rig.transcriptBox().getChildren().get(1));
            assertEquals(20.0, line.getFont().getSize(), 0.01);

            rig.host.settings.setAgentSupport(false);
            FxTestSupport.runOnFx(rig.coordinator::applySupport);
            rig.host.settings.setAgentSupport(true);
            FxTestSupport.runOnFx(rig.coordinator::pickModel);
            assertEquals(tr("status.agent.noModels"), rig.host.lastStatus(), "the session went with the feature");
        }
    }

    // ---- the pop-out window -----------------------------------------------------------------------------

    @Test
    void thePanelPopsOutIntoItsOwnWindowAndBack(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            Button detach = FxTestSupport.field(rig.panel, "detachButton");
            FxTestSupport.runOnFx(rig.coordinator::toggleToolWindow);
            assertEquals(1, rig.ops.toggles.get());

            FxTestSupport.runOnFx(detach::fire);
            assertTrue(rig.coordinator.isDetached());
            assertEquals(1, rig.ops.closed.get(), "the docked slot gives the panel up first");
            assertEquals(List.of(false), rig.ops.available);
            Stage floating =
                    FxTestSupport.callOnFx(() -> (Stage) rig.panel.getScene().getWindow());
            assertTrue(FxTestSupport.callOnFx(floating::isShowing));
            assertEquals(tr("toolwindow.agent"), FxTestSupport.callOnFx(floating::getTitle));
            assertFalse(FxTestSupport.callOnFx(
                    () -> floating.getScene().getStylesheets().isEmpty()));

            // While floating, the tool-window command brings that window forward instead of docking.
            FxTestSupport.runOnFx(rig.coordinator::toggleToolWindow);
            assertEquals(1, rig.ops.toggles.get());

            // No main window to return to (it is closing): the panel is released but not re-docked.
            FxTestSupport.runOnFx(detach::fire);
            assertFalse(rig.coordinator.isDetached());
            assertFalse(FxTestSupport.callOnFx(floating::isShowing));
            assertNull(FxTestSupport.callOnFx(rig.panel::getScene));
            assertEquals(List.of(false, false), rig.ops.available);
            assertEquals(List.of(), rig.ops.opened);
        }
    }

    @Test
    void closingTheFloatingWindowDocksThePanelAgainWhileTheMainWindowLives(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            Stage main = FxTestSupport.callOnFx(() -> {
                Stage s = new Stage();
                s.setScene(new Scene(new StackPane(), 200, 100));
                s.show();
                return s;
            });
            rig.host.window = main;
            try {
                Button detach = FxTestSupport.field(rig.panel, "detachButton");
                FxTestSupport.runOnFx(detach::fire);
                Stage floating = FxTestSupport.callOnFx(
                        () -> (Stage) rig.panel.getScene().getWindow());
                assertEquals(main, FxTestSupport.callOnFx(floating::getOwner));

                FxTestSupport.runOnFx(floating::hide); // the user closes the floating window
                assertFalse(rig.coordinator.isDetached());
                assertEquals(List.of(false, true), rig.ops.available);
                assertEquals(List.of(true), rig.ops.opened, "re-docked, with the focus");

                // Detached again, but the feature is switched off before it comes back: it stays out of the dock.
                FxTestSupport.runOnFx(detach::fire);
                rig.host.settings.setAgentSupport(false);
                FxTestSupport.runOnFx(detach::fire);
                assertEquals(List.of(false, true, false, false), rig.ops.available);
                assertEquals(List.of(true), rig.ops.opened);
            } finally {
                FxTestSupport.runOnFx(main::hide);
            }
        }
    }

    // ---- paths the agent mentions, files it asks for -----------------------------------------------------

    @Test
    void aPathInAReplyOpensWhenItExistsAndSaysSoWhenItDoesNot(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            Path file = Files.writeString(
                    Files.createDirectories(root.resolve("src")).resolve("Main.java"), "x");
            FxTestSupport.runOnFx(() ->
                    FxTestSupport.invokeWith(rig.coordinator, "openPathCandidate", String.class, "src/Main.java"));
            assertEquals(List.of(file), rig.ops.openedPaths);

            FxTestSupport.runOnFx(() ->
                    FxTestSupport.invokeWith(rig.coordinator, "openPathCandidate", String.class, "src/Gone.java"));
            assertEquals(List.of(file), rig.ops.openedPaths);
            assertEquals(tr("status.agent.pathNotFound", "src/Gone.java"), rig.host.lastStatus());
        }
    }

    @Test
    void theAgentReadsAndWritesFilesOfTheSessionFolderThroughTheEditor(@TempDir Path root) throws Exception {
        Path realRoot = root.toRealPath();
        try (Rig rig = new Rig(realRoot, "ok")) {
            Path notes = Files.writeString(realRoot.resolve("notes.txt"), "hello from disk");
            rig.prompt("#read " + notes);
            assertEquals(
                    "ANSWER:{\"content\":\"hello from disk\"}", rig.lastLine().strip());

            rig.prompt("#read " + realRoot.resolve("missing.txt"));
            assertTrue(rig.lastLine().strip().startsWith("REFUSED[-32603]:"), rig.lastLine());

            Path created = realRoot.resolve("out").resolve("new.txt");
            rig.prompt("#write " + created);
            assertEquals("ANSWER:null", rig.lastLine().strip());
            assertEquals("written by the agent\n", Files.readString(created));
            FxTestSupport.drainFx();
            assertEquals(List.of(created), rig.ops.backgroundOpened, "a file nobody had open gets a tab");
            assertEquals(1, rig.ops.treeRefreshes.get());

            // A request the editor does not implement is refused, not left hanging.
            rig.prompt("#terminal");
            assertTrue(rig.lastLine().strip().startsWith("REFUSED[-32601]:"), rig.lastLine());
        }
    }

    // ---- which agents are installed ---------------------------------------------------------------------

    @Test
    void detectionProbesTheConfiguredCommandOnceUntilInvalidated(@TempDir Path root) throws Exception {
        try (Rig rig = new Rig(root, "ok")) {
            assertTrue(rig.detect("claude"), "an absolute path to an executable");

            // Cached: a changed command is not seen until the cache is cleared.
            rig.host.settings.setAgentCommand(root.resolve("nope").toString());
            assertTrue(rig.detect("claude"));
            rig.coordinator.invalidateDetection();
            assertFalse(rig.detect("claude"));

            rig.host.settings.setGeminiAgentCommand("editora-no-such-agent-binary --acp");
            assertFalse(rig.detect("gemini"), "a bare name that is not on PATH");
        }
    }

    // ---- the rig ----------------------------------------------------------------------------------------

    private record Remembered(String sessionId, String cwd, String label, String agentId) {}

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
        final AtomicInteger saves = new AtomicInteger();
        final OverlayHost overlay = new OverlayHost();
        volatile String last;
        volatile EditorBuffer active;
        volatile boolean local = true;
        volatile boolean simple;
        volatile Window window;

        Host() {
            settings.setAiEnabled(true);
            settings.setAgentSupport(true);
        }

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public boolean simpleModeActive() {
            return simple;
        }

        @Override
        public void setStatus(String message) {
            last = message;
            statuses.add(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public boolean isLocalBuffer(EditorBuffer buffer) {
            return local;
        }

        @Override
        public void requestSave() {
            saves.incrementAndGet();
        }

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }

        @Override
        public Window window() {
            return window;
        }

        String lastStatus() {
            return last;
        }

        /** The next status message, waiting for it if it has not been set yet. */
        String awaitStatus() throws InterruptedException {
            String status = statuses.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(status, "no status message arrived");
            return status;
        }
    }

    private static final class Ops implements AgentCoordinator.Ops {
        final Path root;
        final List<Remembered> remembered = new java.util.concurrent.CopyOnWriteArrayList<>();
        final BlockingQueue<Remembered> rememberedEvents = new LinkedBlockingQueue<>();
        final ObservableList<AgentSessionHistory.Entry> history = FXCollections.observableArrayList();
        final AtomicInteger toggles = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger treeRefreshes = new AtomicInteger();
        final List<Boolean> available = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Boolean> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Path> openedPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Path> backgroundOpened = new java.util.concurrent.CopyOnWriteArrayList<>();

        Ops(Path root) {
            this.root = root;
        }

        @Override
        public Path projectRoot() {
            return root;
        }

        @Override
        public EditorBuffer bufferForPath(String path) {
            return null;
        }

        @Override
        public void toggleToolWindow() {
            toggles.incrementAndGet();
        }

        @Override
        public void openToolWindow(boolean focus) {
            opened.add(focus);
        }

        @Override
        public void closeToolWindow() {
            closed.incrementAndGet();
        }

        @Override
        public void setToolWindowAvailable(boolean nowAvailable) {
            available.add(nowAvailable);
        }

        @Override
        public void refreshProjectTree() {
            treeRefreshes.incrementAndGet();
        }

        @Override
        public void openBackgroundBuffer(Path target) {
            backgroundOpened.add(target);
        }

        @Override
        public void openPath(Path file) {
            openedPaths.add(file);
        }

        @Override
        public void rememberSession(String sessionId, String cwd, String label, long updatedAt, String agentId) {
            Remembered r = new Remembered(sessionId, cwd, label, agentId);
            remembered.add(r);
            rememberedEvents.add(r);
        }

        @Override
        public ObservableList<AgentSessionHistory.Entry> sessionHistory() {
            return history;
        }
    }

    /** A coordinator with its panel, a host and window services that record what they are asked. */
    private static final class Rig implements AutoCloseable {
        final Host host = new Host();
        final Ops ops;
        final AgentCoordinator coordinator;
        final AgentPanel panel;
        final StackPane overlayRoot = new StackPane();
        private final BlockingQueue<Boolean> idle = new LinkedBlockingQueue<>();
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final List<EditorBuffer> buffers = new ArrayList<>();

        Rig(Path root, String scenario) throws Exception {
            ops = new Ops(root);
            host.settings.setAgentCommand(FakeAcpAgent.commandLine(scenario));
            coordinator = new AgentCoordinator(host, ops);
            panel = FxTestSupport.callOnFx(() -> {
                host.overlay.install(overlayRoot);
                AgentPanel p = coordinator.panel();
                Button stop = FxTestSupport.field(p, "stopButton");
                stop.disableProperty().addListener((obs, was, disabled) -> {
                    if (disabled) {
                        idle.add(Boolean.TRUE); // a turn ended
                    }
                });
                VBox box = FxTestSupport.field(p, "transcriptBox");
                box.getChildren().addListener((ListChangeListener<Node>) change -> {
                    while (change.next()) {
                        for (Node added : change.getAddedSubList()) {
                            if (added instanceof Label label) {
                                lines.add(label.getText());
                            }
                        }
                    }
                });
                return p;
            });
        }

        VBox transcriptBox() {
            return FxTestSupport.field(panel, "transcriptBox");
        }

        EditorBuffer buffer(Path path, String content) throws Exception {
            EditorBuffer b = FxTestSupport.callOnFx(() -> {
                EditorBuffer created = new EditorBuffer();
                if (path != null) {
                    created.setPath(path);
                }
                created.setContent(content);
                created.markClean();
                return created;
            });
            buffers.add(b);
            return b;
        }

        /** Starts a turn without waiting for it. */
        void send(String text) throws Exception {
            idle.clear();
            FxTestSupport.runOnFx(() -> coordinator.sendPrompt(text));
        }

        /** Runs a whole turn. */
        void prompt(String text) throws Exception {
            send(text);
            awaitIdle();
        }

        void awaitIdle() throws Exception {
            assertNotNull(idle.poll(WAIT_SECONDS, TimeUnit.SECONDS), "the turn did not end");
            FxTestSupport.drainFx();
        }

        void awaitRemembered(int count) throws Exception {
            while (ops.remembered.size() < count) {
                assertNotNull(ops.rememberedEvents.poll(WAIT_SECONDS, TimeUnit.SECONDS), "no session was recorded");
            }
        }

        /** The first plain transcript line matching {@code wanted}, waiting for it to be appended. */
        String awaitLine(Predicate<String> wanted) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (true) {
                String line = lines.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (line == null) {
                    throw new AssertionError("the transcript line never appeared; transcript: " + transcript());
                }
                if (wanted.test(line)) {
                    FxTestSupport.drainFx();
                    return line;
                }
            }
        }

        /** Waits for a header label to read {@code text}. */
        void awaitLabel(String field, String text) throws Exception {
            BlockingQueue<String> seen = new LinkedBlockingQueue<>();
            Label label = FxTestSupport.field(panel, field);
            javafx.beans.value.ChangeListener<String> listener = (obs, was, now) -> seen.add(now);
            String current = FxTestSupport.callOnFx(() -> {
                label.textProperty().addListener(listener);
                return label.getText();
            });
            try {
                while (!text.equals(current)) {
                    current = seen.poll(WAIT_SECONDS, TimeUnit.SECONDS);
                    assertNotNull(current, field + " never became \"" + text + "\"");
                }
            } finally {
                FxTestSupport.runOnFx(() -> label.textProperty().removeListener(listener));
            }
        }

        String label(String field) throws Exception {
            Label label = FxTestSupport.field(panel, field);
            return FxTestSupport.callOnFx(label::getText);
        }

        /** The transcript entries, each as its visible text. */
        List<String> transcript() throws Exception {
            return FxTestSupport.callOnFx(this::entries);
        }

        private List<String> entries() {
            List<String> out = new ArrayList<>();
            for (Node entry : transcriptBox().getChildren()) {
                StringBuilder sb = new StringBuilder();
                collect(entry, sb);
                out.add(sb.toString());
            }
            return out;
        }

        String lastLine() throws Exception {
            List<String> all = transcript();
            return all.get(all.size() - 1);
        }

        List<String> plan() throws Exception {
            VBox box = FxTestSupport.field(panel, "planBox");
            return FxTestSupport.callOnFx(() -> {
                List<String> out = new ArrayList<>();
                for (Node n : box.getChildren()) {
                    out.add(((Label) n).getText());
                }
                return box.isVisible() ? out : List.of();
            });
        }

        /** The picker card on show, or null. */
        Node picker() throws Exception {
            return FxTestSupport.callOnFx(() -> overlayRoot.lookup(".command-palette"));
        }

        /** The picker's rows, by the name each is listed under. */
        List<String> pickerRows() throws Exception {
            Node card = picker();
            assertNotNull(card, "no picker is showing");
            return FxTestSupport.callOnFx(() -> {
                List<String> out = new ArrayList<>();
                for (Object item : ((ListView<?>) card.lookup(".list-view")).getItems()) {
                    out.add(
                            switch (item) {
                                case com.editora.agent.AcpJson.ModelInfo model -> model.name();
                                case com.editora.agent.AcpJson.ModeInfo mode -> mode.name();
                                case com.editora.agent.AcpAgentRegistry.AgentDef agent -> agent.displayName();
                                case AgentSessionHistory.Entry entry -> AgentCoordinator.displayLabel(entry);
                                default -> String.valueOf(item);
                            });
                }
                return out;
            });
        }

        /** Selects row {@code index} of the picker on show and presses Enter in its query field. */
        void choose(int index) throws Exception {
            Node card = picker();
            assertNotNull(card, "no picker is showing");
            FxTestSupport.runOnFx(() -> {
                ListView<?> list = (ListView<?>) card.lookup(".list-view");
                list.getSelectionModel().select(index);
                TextField input = (TextField) card.lookup(".text-field");
                Event.fireEvent(
                        input, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            });
        }

        /** FX thread: a key press delivered to {@code target}. */
        void key(Node target, KeyCode code, boolean shift) {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
        }

        boolean detect(String agentId) throws Exception {
            BlockingQueue<Boolean> result = new LinkedBlockingQueue<>();
            coordinator.detect(agentId, result::add);
            Boolean found = result.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(found, "the probe never reported");
            return found;
        }

        @Override
        public void close() throws Exception {
            coordinator.shutdown();
            FxTestSupport.runOnFx(() -> {
                host.overlay.hide();
                for (EditorBuffer b : buffers) {
                    b.dispose();
                }
            });
            FxTestSupport.drainFx();
        }
    }

    private static void collect(Node node, StringBuilder out) {
        if (node instanceof Text text) {
            out.append(text.getText());
        } else if (node instanceof Labeled labeled) {
            out.append(labeled.getText());
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }
}
