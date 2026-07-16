package com.editora.macro;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The capture filters, the slug↔command-id mapping, and what a replay reports. */
class MacroServiceTest {

    private static MacroService service(Path dir) {
        return new MacroService(new ConfigManager(dir));
    }

    /**
     * A saved macro's own {@code macro.run.<slug>} command is a legitimate step — composing macros is the
     * point. The recorder's {@code macro.*} filter (meant for the record/replay/save control commands)
     * swallowed those too, so invoking a macro while recording vanished from the recording with no feedback.
     */
    @Test
    void recordingCapturesAnotherMacrosRunCommandButNotTheControlCommands(@TempDir Path dir) {
        MacroService s = service(dir);
        s.startRecording();
        s.onCommand("macro.startRecording"); // control — never recorded
        s.onCommand("macro.replayLast"); // control
        s.onCommand("palette.show"); // the act of opening the palette, not an action
        s.onCommand("macro.run.build"); // another macro — IS an action
        s.onCommand("edit.copy");
        s.stopRecording();
        assertEquals(
                List.of(MacroStep.command("macro.run.build"), MacroStep.command("edit.copy")),
                s.saveLast("composed").steps());
    }

    /** Replay is never itself recorded, whatever the hooks are fed. */
    @Test
    void nothingIsRecordedWhileReplaying(@TempDir Path dir) {
        MacroService s = service(dir);
        s.startRecording();
        s.onCommand("edit.copy");
        s.stopRecording();
        assertNotNull(s.saveLast("m"));

        s.startRecording(); // recording AND replaying at once
        s.run("m", 1, id -> s.onCommand(id), t -> s.onTypedChar('z'), k -> s.onKey("DOWN"));
        s.stopRecording();
        assertNull(s.saveLast("captured"), "nothing recorded → nothing to save");
    }

    /** Distinct names can collide on one macro.run.<slug> id; the shadowed macro becomes unreachable. */
    @Test
    void slugClashIsDetected(@TempDir Path dir) {
        MacroService s = service(dir);
        s.startRecording();
        s.onTypedChar('x');
        s.stopRecording();
        assertNotNull(s.saveLast("my macro"));

        assertEquals(MacroService.commandIdFor("my macro"), MacroService.commandIdFor("my-macro"));
        assertTrue(s.slugClash("my-macro"), "different name, same command id");
        assertNull(s.saveLast("my-macro"), "must refuse rather than shadow the existing macro");
        assertEquals(1, s.saved().size());

        assertFalse(s.slugClash("my macro"), "re-saving the same macro is not a clash");
        assertFalse(s.slugClash("other"));
    }

    /** Symbol-only names all fall back to the "macro" slug — the same collision, less obviously. */
    @Test
    void symbolOnlyNamesCollideOnTheFallbackSlug(@TempDir Path dir) throws IOException {
        MacroService s = service(dir);
        s.startRecording();
        s.onTypedChar('x');
        s.stopRecording();
        assertEquals("macro.run.macro", MacroService.commandIdFor("!!!"));
        assertEquals("macro.run.macro", MacroService.commandIdFor("???"));
        assertNotNull(s.saveLast("!!!"));
        assertNull(s.saveLast("???"), "would register macro.run.macro twice");
    }

    /** A replay dropped by the re-entrancy guard must not report success. */
    @Test
    void runReportsFalseWhenTheGuardDropsANestedReplay(@TempDir Path dir) {
        MacroService s = service(dir);
        s.startRecording();
        s.onCommand("edit.copy");
        s.stopRecording();
        assertNotNull(s.saveLast("outer"));

        List<String> log = new ArrayList<>();
        List<Boolean> nestedResult = new ArrayList<>();
        boolean ok = s.run(
                "outer",
                1,
                id -> {
                    log.add(id);
                    nestedResult.add(s.run("outer", 1, log::add, t -> {}, k -> {}));
                },
                t -> {},
                k -> {});
        assertTrue(ok, "the outer replay ran");
        assertEquals(List.of("edit.copy"), log, "the nested replay was dropped");
        assertEquals(List.of(false), nestedResult, "...and reported that it did nothing");
        assertFalse(s.run("no such macro", 1, log::add, t -> {}, k -> {}));
    }
}
