package com.editora.macro;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What is recorded, what "the last macro" is, and how macros are saved. */
class MacroServiceTest {

    private static MacroService service(Path dir) {
        return new MacroService(new ConfigManager(dir));
    }

    private static MacroService recorded(Path dir, String text) {
        MacroService s = service(dir);
        s.startRecording();
        s.onText(text, false);
        s.stopRecording();
        return s;
    }

    @Test
    void theControlCommandsAndThePaletteAreNotRecorded(@TempDir Path dir) {
        MacroService s = service(dir);
        s.startRecording();
        s.onCommand("macro.startRecording"); // control — never recorded
        s.onCommand("macro.replayLast"); // control
        s.onCommand("palette.show"); // the act of opening the palette, not an action
        s.onCommand("macro.run.build"); // recorded by recordMacroRun when the run starts, not here
        s.onCommand("edit.copy");
        s.stopRecording();
        assertEquals(List.of(MacroStep.command("edit.copy")), s.last().steps());
    }

    /** Composing macros is the point: running a saved macro while recording is a step, once per run. */
    @Test
    void runningASavedMacroWhileRecordingIsAStep(@TempDir Path dir) {
        MacroService s = recorded(dir, "A");
        Macro inner = s.saveLastAs("inner", null);
        s.startRecording();
        s.onText("x", false);
        s.recordMacroRun(inner, 2);
        s.stopRecording();
        assertEquals(
                List.of(
                        MacroStep.text("x"),
                        MacroStep.command("macro.run.inner"),
                        MacroStep.command("macro.run.inner")),
                s.last().steps());
    }

    /**
     * M2: Enter in a picker is recorded as a key, and replaying that key runs the picked command again — so
     * the command it ran must not be recorded as well, or the replay would run it twice.
     */
    @Test
    void aCommandThatAKeyStepCausedIsNotRecordedAsWell(@TempDir Path dir) {
        MacroService s = service(dir);
        s.startRecording();
        s.keySeen();
        s.onCommand("file.quickOpen"); // a chord: the user's own
        s.keySeen();
        s.onKey("ENTER", true); // Enter in the picker…
        s.onCommand("file.openPicked"); // …ran this
        s.keySeen();
        s.onCommand("edit.copy"); // the next chord is the user's own again
        s.stopRecording();
        assertEquals(
                List.of(
                        MacroStep.command("file.quickOpen"),
                        MacroStep.key("ENTER", true),
                        MacroStep.command("edit.copy")),
                s.last().steps());
    }

    /** M9: starting and stopping by accident must not cost the macro recorded before. */
    @Test
    void anEmptyRecordingKeepsThePreviousMacro(@TempDir Path dir) {
        MacroService s = recorded(dir, "keep");
        s.startRecording();
        assertEquals(0, s.stopRecording());
        assertTrue(s.hasLast());
        assertEquals(List.of(MacroStep.text("keep")), s.last().steps());
    }

    /** M17: a cancelled recording is discarded and the previous macro is still the last one. */
    @Test
    void aCancelledRecordingIsDiscarded(@TempDir Path dir) {
        MacroService s = recorded(dir, "keep");
        s.saveLastAsPlaceholder();
        s.startRecording();
        s.onText("junk", false);
        s.cancelRecording();
        assertFalse(s.isRecording());
        assertEquals(List.of(MacroStep.text("keep")), s.last().steps());
        assertEquals(List.of(MacroStep.text("keep")), s.saved().get(0).steps(), "the stored one is untouched too");
    }

    /** M9: after a restart, or in a second window, "replay last" plays the stored last recording. */
    @Test
    void theLastMacroFallsBackToTheStoredOne(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        MacroService first = new MacroService(config);
        assertFalse(first.hasLast());
        first.startRecording();
        first.onText("hello", false);
        first.stopRecording();
        first.saveLastAsPlaceholder();

        MacroService second = new MacroService(config); // another window on the same store
        assertTrue(second.hasLast());
        assertEquals(List.of(MacroStep.text("hello")), second.last().steps());
    }

    /** M16: the unnamed slot is one entry found by id — whatever language its label is shown in. */
    @Test
    void theUnnamedSlotIsReusedAndHasNoStoredName(@TempDir Path dir) {
        MacroService s = recorded(dir, "one");
        Macro a = s.saveLastAsPlaceholder();
        assertEquals("", a.name());
        assertEquals("unnamed-macro", a.id());
        s.startRecording();
        s.onText("two", false);
        s.stopRecording();
        Macro b = s.saveLastAsPlaceholder();
        assertEquals(a.id(), b.id());
        assertEquals(1, s.saved().size());
        assertEquals(List.of(MacroStep.text("two")), s.saved().get(0).steps());
        assertTrue(s.isPlaceholder(s.saved().get(0)));
    }

    /** M8: no name collapses onto another's id, and case variants are separate macros. */
    @Test
    void savingGivesEveryNameItsOwnId(@TempDir Path dir) {
        MacroService s = recorded(dir, "x");
        Macro build = s.saveLastAs("Build", null);
        Macro lower = s.saveLastAs("build", null);
        Macro ja = s.saveLastAs("日本語", null);
        Macro zh = s.saveLastAs("中文", null);
        assertEquals(4, s.saved().size());
        assertEquals(4, s.saved().stream().map(Macro::id).distinct().count());
        assertEquals("macro.run.build", MacroService.commandIdFor(build));
        assertEquals("macro.run.build-2", MacroService.commandIdFor(lower));
        assertNotEquals(ja.id(), zh.id());
        assertNull(s.saveLastAs("  ", null), "a blank name saves nothing");
    }

    /** Replacing a macro keeps its id, so the key bound to it now runs the new steps. */
    @Test
    void savingOverAMacroKeepsItsId(@TempDir Path dir) {
        MacroService s = recorded(dir, "old");
        Macro first = s.saveLastAs("Build", null);
        s.startRecording();
        s.onText("new", false);
        s.stopRecording();
        Macro second = s.saveLastAs("Build", s.findByName("Build"));
        assertEquals(first.id(), second.id());
        assertEquals(1, s.saved().size());
        assertEquals(List.of(MacroStep.text("new")), s.findById(first.id()).steps());
    }

    /**
     * Naming the last recording drops the unnamed slot — it has a real name now — but only when the slot
     * still holds that recording. Another window may have recorded over it since (M16).
     */
    @Test
    void namingTheLastMacroDropsTheUnnamedSlotOnlyWhenItIsTheSameRecording(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        MacroService a = new MacroService(config);
        a.startRecording();
        a.onText("from A", false);
        a.stopRecording();
        a.saveLastAsPlaceholder();
        assertNotNull(a.saveLastAs("Mine", null));
        assertEquals(1, a.saved().size(), "the slot held this recording: gone");

        a.saveLastAsPlaceholder();
        MacroService b = new MacroService(config);
        b.startRecording();
        b.onText("from B", false);
        b.stopRecording();
        b.saveLastAsPlaceholder(); // B's recording is in the slot now
        assertNotNull(a.saveLastAs("Mine too", null));
        assertEquals(3, a.saved().size(), "B's unnamed recording survives A's save");
        assertEquals(
                List.of(MacroStep.text("from B")),
                config.getMacroStore().placeholder().steps());
    }

    @Test
    void deleteRemovesById(@TempDir Path dir) {
        MacroService s = recorded(dir, "x");
        Macro m = s.saveLastAs("gone", null);
        assertTrue(s.delete(m.id()));
        assertFalse(s.delete(m.id()));
        assertTrue(s.saved().isEmpty());
    }
}
