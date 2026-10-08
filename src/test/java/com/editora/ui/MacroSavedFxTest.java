package com.editora.ui;

import java.util.List;
import java.util.concurrent.TimeUnit;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.macro.Macro;
import com.editora.macro.MacroService;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The macro commands around a recording: what each says when there is nothing to act on, naming and saving
 * the last recording (and replacing a macro of the same name only on a second Save), running and deleting a
 * saved macro from its picker, and replaying a given number of times — through the registry, the real prompt
 * and the real pickers of a window.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroSavedFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private MacroCoordinator macros;
    private MacroService service;
    private EditorBuffer buffer;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        buffer = FxTestSupport.callOnFx(() -> {
            registry = FxTestSupport.field(fx.controller, "registry");
            macros = FxTestSupport.field(fx.controller, "macroCoordinator");
            service = FxTestSupport.field(macros, "service");
            EditorBuffer b = new EditorBuffer();
            b.setContent("");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        fx.dispose();
    }

    @BeforeEach
    void clean() throws Exception {
        FxTestSupport.runOnFx(() -> {
            if (macros.isRecording()) {
                registry.run("macro.cancelRecording");
            }
            OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
            if (overlay.isShowing()) {
                overlay.hide();
            }
            for (Macro saved : List.copyOf(service.saved())) {
                macros.delete(saved);
            }
            buffer.setContent("");
            buffer.getArea().requestFocus();
        });
        settle();
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private String status() throws Exception {
        return SaveDecisionsFxTest.lastMessage(fx);
    }

    private String text() throws Exception {
        return FxTestSupport.callOnFx(() -> buffer.getArea().getText());
    }

    /** Lets a replay — which delivers its steps over several turns of the FX thread — run to its end. */
    private void settle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (FxTestSupport.callOnFx(macros::isReplaying)) {
            assertTrue(System.nanoTime() < deadline, "the replay finished; status: " + status());
            FxTestSupport.drainFx();
        }
        for (int i = 0; i < 4; i++) {
            FxTestSupport.drainFx();
        }
    }

    /** Records typing {@code typed} into the editor as the last macro (the buffer keeps the typed text). */
    private void record(String typed) throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = buffer.getArea();
            area.requestFocus();
            registry.run("macro.startRecording");
            for (char c : typed.toCharArray()) {
                KeyCode code = KeyCode.getKeyCode(String.valueOf(Character.toUpperCase(c)));
                area.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
                area.fireEvent(new KeyEvent(
                        KeyEvent.KEY_TYPED, String.valueOf(c), "", KeyCode.UNDEFINED, false, false, false, false));
            }
            registry.run("macro.stopRecording");
        });
        settle();
    }

    /** The macros saved under a name (the store also keeps the unnamed last recording). */
    private List<String> named() throws Exception {
        return FxTestSupport.callOnFx(() -> service.saved().stream()
                .map(Macro::name)
                .filter(name -> !name.isBlank())
                .toList());
    }

    private void forgetLastMacro() throws Exception {
        // A new, empty recording that is cancelled keeps the previous one; start from a service with none —
        // neither this window's last recording nor the unnamed one the store keeps for "replay last".
        FxTestSupport.runOnFx(() -> {
            for (Macro saved : List.copyOf(service.saved())) {
                macros.delete(saved);
            }
            try {
                java.lang.reflect.Field last = MacroService.class.getDeclaredField("lastMacro");
                last.setAccessible(true);
                last.set(service, null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void everyCommandSaysSoWhenThereIsNothingToActOn() throws Exception {
        forgetLastMacro();
        run("macro.replayLast");
        assertEquals(tr("status.macro.none"), status());
        run("macro.replayLastN");
        assertEquals(tr("status.macro.none"), status());
        assertFalse(FxPrompts.showing(fx.controller), "no count is asked for a macro that does not exist");
        run("macro.nameAndSave");
        assertFalse(FxPrompts.showing(fx.controller));
        run("macro.runSaved");
        assertEquals(tr("status.macro.noSaved"), status());
        run("macro.deleteSaved");
        assertEquals(tr("status.macro.noSaved"), status());
        run("macro.cancelRecording");
        assertEquals(tr("status.macro.notRecording"), status());
    }

    @Test
    void recordingIsStartedOnceToggledAndCancelled() throws Exception {
        run("macro.startRecording");
        assertEquals(tr("status.macro.recording"), status());
        assertTrue(FxTestSupport.callOnFx(macros::isRecording));
        run("macro.startRecording");
        assertEquals(tr("status.macro.alreadyRecording"), status());

        run("macro.toggleRecording");
        assertFalse(FxTestSupport.callOnFx(macros::isRecording), "the one-key toggle stops a recording");
        run("macro.toggleRecording");
        assertTrue(FxTestSupport.callOnFx(macros::isRecording), "and starts one");
        run("macro.cancelRecording");
        assertFalse(FxTestSupport.callOnFx(macros::isRecording));
        assertTrue(status().startsWith(tr("status.macro.recordingCancelled")), status());
    }

    @Test
    void theLastRecordingIsSavedUnderANameAndReplacesAnotherOnlyOnASecondSave() throws Exception {
        record("ab");
        run("macro.nameAndSave");
        FxPrompts.submit(fx.controller, "  ", tr("dialog.save"));
        assertEquals(tr("palette.macro.nameEmpty"), FxPrompts.error(fx.controller));
        assertTrue(FxPrompts.showing(fx.controller), "the form stays open with the reason");

        FxPrompts.submit(fx.controller, " greet ", tr("dialog.save"));
        assertFalse(FxPrompts.showing(fx.controller));
        assertEquals(tr("status.macro.saved", "greet"), status());
        assertEquals(List.of("greet"), named());

        record("xyz");
        run("macro.nameAndSave");
        FxPrompts.submit(fx.controller, "greet", tr("dialog.save"));
        assertEquals(tr("palette.macro.nameReplace", "greet"), FxPrompts.error(fx.controller));
        assertEquals(
                List.of(com.editora.macro.MacroStep.text("ab")),
                FxTestSupport.callOnFx(() -> service.findByName("greet").steps()),
                "not replaced yet");

        FxPrompts.submit(fx.controller, "greet", tr("dialog.save")); // the same name again: yes, replace
        assertEquals(tr("status.macro.replaced", "greet"), status());
        assertEquals(List.of("greet"), named(), "still one macro of that name");
        assertEquals(
                List.of(com.editora.macro.MacroStep.text("xyz")),
                FxTestSupport.callOnFx(() -> service.findByName("greet").steps()));
    }

    @Test
    void namingWhileStillRecordingEndsTheRecordingFirst() throws Exception {
        record("a");
        FxTestSupport.runOnFx(() -> {
            registry.run("macro.startRecording");
            buffer.getArea()
                    .fireEvent(
                            new KeyEvent(KeyEvent.KEY_TYPED, "q", "", KeyCode.UNDEFINED, false, false, false, false));
            registry.run("macro.nameAndSave");
        });
        assertFalse(FxTestSupport.callOnFx(macros::isRecording));
        assertTrue(FxPrompts.showing(fx.controller));
        FxPrompts.cancel(fx.controller);
        assertEquals(List.of(), named(), "cancelling saves nothing");
    }

    @Test
    void aSavedMacroIsRunFromItsPicker() throws Exception {
        record("hi");
        FxTestSupport.runOnFx(() -> {
            service.saveLastAs("hello", null);
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
            buffer.setContent("");
        });
        run("macro.runSaved");
        assertEquals(List.of("hello"), named());
        FxPrompts.choose(
                fx.controller, FxTestSupport.callOnFx(() -> service.saved().indexOf(service.findByName("hello"))));
        settle();
        assertEquals("hi", text());
        assertEquals(tr("status.macro.ran", "hello", 1), status());

        run("macro.runSaved");
        FxPrompts.escape(fx.controller);
        settle();
        assertEquals("hi", text(), "Escape runs nothing");
    }

    @Test
    void aSavedMacroIsDeletedFromItsPickerOnlyAfterTheQuestionIsConfirmed() throws Exception {
        record("hi");
        FxTestSupport.runOnFx(() -> {
            service.saveLastAs("doomed", null);
            FxTestSupport.invoke(fx.controller, "refreshSavedMacroCommands");
        });

        int row = FxTestSupport.callOnFx(() -> service.saved().indexOf(service.findByName("doomed")));
        run("macro.deleteSaved");
        FxPrompts.choose(fx.controller, row);
        FxTestSupport.drainFx(); // the question is shown once the picker has taken its own card down
        assertTrue(
                FxPrompts.labels(fx.controller).contains(tr("settings.macro.deleteConfirm", "doomed")),
                FxPrompts.labels(fx.controller).toString());
        FxPrompts.cancel(fx.controller);
        assertEquals(List.of("doomed"), named(), "Cancel keeps it");

        run("macro.deleteSaved");
        FxPrompts.choose(fx.controller, row);
        FxTestSupport.drainFx();
        FxPrompts.submit(fx.controller, null, tr("settings.macro.delete"));
        assertEquals(List.of(), named());
        assertEquals(tr("status.macro.deleted", "doomed"), status());
        assertTrue(FxTestSupport.callOnFx(() -> registry.get("macro.run.doomed").isEmpty()), "its command is gone too");

        // Deleting what is already gone changes nothing and says nothing.
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(fx.controller, "setStatus", String.class, "untouched"));
        Macro gone = new Macro("doomed", "doomed", List.of());
        FxTestSupport.runOnFx(() -> macros.delete(gone));
        assertEquals("untouched", status());
    }

    @Test
    void theLastMacroIsReplayedTheNumberOfTimesAskedFor() throws Exception {
        record("ab");
        FxTestSupport.runOnFx(() -> buffer.setContent(""));
        run("macro.replayLastN");
        assertEquals("1", FxPrompts.text(fx.controller));
        FxPrompts.answer(fx.controller, "3");
        settle();
        assertEquals("ababab", text());
        assertEquals(tr("status.macro.replayed", 3), status());

        FxTestSupport.runOnFx(() -> buffer.setContent(""));
        run("macro.replayLast");
        settle();
        assertEquals("ab", text());
        assertEquals(tr("status.macro.replayed", 1), status());
    }

    @Test
    void replayingWhileStillRecordingEndsTheRecordingAndPlaysIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            buffer.setContent("");
            buffer.getArea().requestFocus();
            registry.run("macro.startRecording");
            buffer.getArea()
                    .fireEvent(
                            new KeyEvent(KeyEvent.KEY_TYPED, "z", "", KeyCode.UNDEFINED, false, false, false, false));
            registry.run("macro.replayLast");
        });
        settle();
        assertFalse(FxTestSupport.callOnFx(macros::isRecording));
        assertEquals("zz", text(), "typed once while recording, once more by the replay");
    }

    @Test
    void anUnnamedMacroIsShownUnderALabelOfItsOwn() {
        assertEquals(tr("macro.unnamedName"), MacroCoordinator.displayName(null));
        assertEquals(tr("macro.unnamedName"), MacroCoordinator.displayName(new Macro("id", " ", List.of())));
        assertEquals("named", MacroCoordinator.displayName(new Macro("id", "named", List.of())));
    }
}
