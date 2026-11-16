package com.editora.ui;

import java.util.List;

import javafx.scene.control.ButtonBar;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The question before F5 in another tab ends the live debug launch, answered through the hook with no window: which
 * answer ends the launch, which runs both, and that Cancel and a closed question do neither.
 */
@Tag("fx")
class DebugRetargetPromptFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void stopAndDebugIsTheOnlyAnswerThatEndsTheLiveLaunch() throws Exception {
        String question = DebugRetargetPrompt.question(false, "dialog.debug.retargetPaused", "Server", "Client.java");
        FxTestSupport.runOnFx(() -> {
            try (DialogAnswers answers = DialogAnswers.pressing(ButtonBar.ButtonData.CANCEL_CLOSE)) {
                assertEquals(DebugCoordinator.Retarget.CANCEL, DebugRetargetPrompt.ask(null, question, false));
                Dialogs.Asked asked = answers.only();
                assertEquals(tr("dialog.debug.retargetTitle"), asked.title());
                assertEquals(question, asked.content());
                assertEquals(
                        List.of(
                                new Dialogs.Answer(
                                        tr("dialog.debug.retargetStop"), ButtonBar.ButtonData.OK_DONE, false, true),
                                new Dialogs.Answer(
                                        tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE, true, false)),
                        asked.answers(),
                        "stopping is red and never the default; Cancel is");

                answers.nowClosing();
                assertEquals(DebugCoordinator.Retarget.CANCEL, DebugRetargetPrompt.ask(null, question, false));
                answers.nowPressing(tr("dialog.debug.retargetStop"));
                assertEquals(DebugCoordinator.Retarget.STOP, DebugRetargetPrompt.ask(null, question, false));
            }
        });
    }

    @Test
    void whereBothCanRunDebugBothIsTheDefaultAndStoppingStaysRed() throws Exception {
        String question = DebugRetargetPrompt.question(true, "dialog.debug.retargetPaused", "Server", "Client.java");
        FxTestSupport.runOnFx(() -> {
            try (DialogAnswers answers = DialogAnswers.pressing(tr("dialog.debug.retargetBoth"))) {
                assertEquals(DebugCoordinator.Retarget.BOTH, DebugRetargetPrompt.ask(null, question, true));
                Dialogs.Asked asked = answers.only();
                assertEquals(tr("dialog.debug.retargetBothTitle"), asked.title());
                assertEquals(question, asked.content());
                assertEquals(
                        List.of(
                                new Dialogs.Answer(
                                        tr("dialog.debug.retargetBoth"), ButtonBar.ButtonData.OK_DONE, true, false),
                                new Dialogs.Answer(
                                        tr("dialog.debug.retargetStop"), ButtonBar.ButtonData.OTHER, false, true),
                                new Dialogs.Answer(
                                        tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE, false, false)),
                        asked.answers());

                answers.nowPressing(tr("dialog.debug.retargetStop"));
                assertEquals(DebugCoordinator.Retarget.STOP, DebugRetargetPrompt.ask(null, question, true));
                answers.nowPressing(ButtonBar.ButtonData.CANCEL_CLOSE);
                assertEquals(DebugCoordinator.Retarget.CANCEL, DebugRetargetPrompt.ask(null, question, true));
                answers.nowClosing();
                assertEquals(DebugCoordinator.Retarget.CANCEL, DebugRetargetPrompt.ask(null, question, true));
            }
        });
    }
}
