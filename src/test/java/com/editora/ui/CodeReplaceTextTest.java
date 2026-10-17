package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The words of a hot code replace, and when the call stack offers Restart Frame. */
class CodeReplaceTextTest {

    @Test
    void replacedClassesAreNamedBySimpleNameAndTheRestCounted() {
        assertEquals("", CodeReplaceText.classList(List.of()));
        assertEquals("Main, Main$Inner", CodeReplaceText.classList(List.of("demo.Main", "demo.Main$Inner")));
        assertEquals(
                "A, B, C, D " + tr("debug.hcr.andMore", 2),
                CodeReplaceText.classList(List.of("p.A", "p.B", "p.C", "p.D", "p.E", "F")));
    }

    @Test
    void onlyProblemsArePrintedAndJavaDebugsOwnJargonIsSaidPlainly() {
        assertNull(CodeReplaceText.notice("STARTING", "Start hot code replacement procedure..."));
        assertNull(CodeReplaceText.notice("END", "Completed hot code replace"));
        assertNull(CodeReplaceText.notice("BUILD_COMPLETE", "Build completed."));
        assertNull(CodeReplaceText.notice("ERROR", "  "));
        assertEquals("Add method not implemented", CodeReplaceText.notice("ERROR", " Add method not implemented "));
        assertEquals(
                tr("debug.hcr.obsoleteMethods"), CodeReplaceText.notice("WARNING", "JVM contains obsolete methods"));
    }

    @Test
    void aFailureTheAdapterAlreadyExplainedIsNotExplainedTwice() {
        assertTrue(CodeReplaceText.saidAlready("Failed to redefine classes: Add method", "Add method"));
        assertTrue(CodeReplaceText.saidAlready("", ""), "no reason at all: only the restart advice");
        assertFalse(CodeReplaceText.saidAlready("JVM is not suspendable", ""));
        assertFalse(CodeReplaceText.saidAlready("JVM is not suspendable", "Add method"));
    }

    @Test
    void restartFrameIsOfferedForAnyFrameButTheBottomOneOfAPausedSession() {
        Path f = Path.of("A.java");
        DapModels.StackFrameInfo top = new DapModels.StackFrameInfo(1, "a", f, 1, 1);
        DapModels.StackFrameInfo mid = new DapModels.StackFrameInfo(2, "b", f, 2, 1);
        DapModels.StackFrameInfo bottom = new DapModels.StackFrameInfo(3, "main", f, 3, 1);
        List<DapModels.StackFrameInfo> frames = List.of(top, mid, bottom);

        assertTrue(DebugCoordinator.canRestartFrame(true, true, top, frames));
        assertTrue(DebugCoordinator.canRestartFrame(true, true, mid, frames));
        assertFalse(DebugCoordinator.canRestartFrame(true, true, bottom, frames));
        assertFalse(DebugCoordinator.canRestartFrame(false, true, top, frames), "running: no frames to restart");
        assertFalse(DebugCoordinator.canRestartFrame(true, false, top, frames), "the adapter cannot");
        assertFalse(DebugCoordinator.canRestartFrame(true, true, null, frames));
    }
}
