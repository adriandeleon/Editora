package com.editora.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.ButtonBar;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.mcp.McpBridge;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression (HN dry run, 2026-11-27): an MCP agent's {@code execute_command} ran any command but plugin and trust
 * ones without asking, Run, build and git push included. Against a real window, through the bridge the MCP server
 * talks to: the user is asked first, Don't Run runs nothing, Always Allow is remembered, and turning the question off
 * runs at once.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpCommandApprovalFxTest {

    private static final String ID = "test.agentCommand";

    private FxWindowFixture fx;
    private McpBridge mcp;
    private Settings settings;
    private final List<String> ran = new CopyOnWriteArrayList<>();
    private final List<Dialogs.Asked> asked = new CopyOnWriteArrayList<>();

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        mcp = fx.controller;
        settings = fx.controller.config.getSettings();
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> registry.register(Command.of(ID, "Agent Thing", () -> ran.add(ID))));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @AfterEach
    void reset() throws Exception {
        Dialogs.answerWith(null);
        FxTestSupport.runOnFx(() -> {
            settings.setMcpCommandsNeedApproval(true);
            settings.getMcpAllowedCommands().clear();
        });
        ran.clear();
        asked.clear();
        fx.controller.mcpBridge.setProposalWaitMillisForTest(WindowMcpBridge.PROPOSAL_WAIT_MILLIS);
    }

    /** Answers the question with the button worded {@code label} (null: closes it), recording what it asked. */
    private void answer(String label) {
        Dialogs.answerWith(a -> {
            asked.add(a);
            return label == null ? null : a.answer(label);
        });
    }

    /** What execute_command does: asks, then runs unless denied. Returns the denial, null when it ran. */
    private String execute() throws Exception {
        String denied = CompletableFuture.supplyAsync(() -> mcp.commandDenial(ID, "Claude Code"))
                .get(30, TimeUnit.SECONDS);
        if (denied == null) {
            mcp.executeCommand(ID);
        }
        return denied;
    }

    @Test
    void newSettingsAskBeforeEditsAndCommands() {
        Settings fresh = new Settings();
        assertTrue(fresh.isMcpEditsNeedApproval(), "agent edits are proposed first");
        assertTrue(fresh.isMcpCommandsNeedApproval(), "agent commands are asked about first");
    }

    @Test
    void theUserIsAskedAndDontRunRunsNothing() throws Exception {
        answer(tr("dialog.mcpCommand.decline"));
        String denied = execute();
        assertNotNull(denied, "declined");
        assertTrue(denied.contains(ID), denied);
        assertEquals(List.of(), ran, "nothing ran");
        assertEquals(1, asked.size());
        Dialogs.Asked q = asked.get(0);
        assertTrue(q.header().contains("Agent Thing") && q.header().contains("Claude Code"), q.header());
        assertTrue(q.content().contains(ID), q.content());
        Dialogs.Answer preset = q.answers().stream()
                .filter(Dialogs.Answer::isDefault)
                .findFirst()
                .orElseThrow();
        assertEquals(ButtonBar.ButtonData.CANCEL_CLOSE, preset.role(), "Enter runs nothing: Don't Run is the default");
    }

    @Test
    void runRunsItOnceAndAsksAgainNextTime() throws Exception {
        answer(tr("dialog.mcpCommand.run"));
        assertNull(execute());
        assertNull(execute());
        assertEquals(List.of(ID, ID), ran);
        assertEquals(2, asked.size(), "Run is for this once");
    }

    @Test
    void alwaysAllowIsRememberedForThatCommand() throws Exception {
        answer(tr("dialog.mcpCommand.always"));
        assertNull(execute());
        assertTrue(FxTestSupport.callOnFx(() -> settings.getMcpAllowedCommands().contains(ID)));
        answer(tr("dialog.mcpCommand.decline")); // would decline, but is not asked
        assertNull(execute());
        assertEquals(List.of(ID, ID), ran);
        assertEquals(1, asked.size(), "asked the first time only");
    }

    @Test
    void closingTheQuestionRunsNothing() throws Exception {
        answer(null);
        assertNotNull(execute());
        assertEquals(List.of(), ran);
    }

    @Test
    void noAnswerInTimeRunsNothing() throws Exception {
        fx.controller.mcpBridge.setProposalWaitMillisForTest(200);
        // A question nobody answers: shown for real, and closed when the wait runs out.
        String denied = execute();
        assertNotNull(denied);
        assertTrue(denied.contains("did not answer in time"), denied);
        assertEquals(List.of(), ran);
    }

    @Test
    void withTheQuestionOffItRunsAtOnce() throws Exception {
        FxTestSupport.runOnFx(() -> settings.setMcpCommandsNeedApproval(false));
        answer(tr("dialog.mcpCommand.decline"));
        assertNull(execute());
        assertEquals(List.of(ID), ran);
        assertEquals(List.of(), asked, "not asked");
    }

    @Test
    void aCommandThatDoesNotExistIsNotAskedAbout() throws Exception {
        answer(tr("dialog.mcpCommand.decline"));
        assertNull(CompletableFuture.supplyAsync(() -> mcp.commandDenial("no.such.command", "Claude Code"))
                .get(30, TimeUnit.SECONDS));
        assertEquals(List.of(), asked, "the run reports the unknown command instead");
    }
}
