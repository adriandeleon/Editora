package com.editora.ui;

import com.editora.dap.LocalProcesses;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** What Attach to Process says of each program it lists. */
class AttachToProcessTextTest {

    @Test
    void aListeningProgramSaysWhereAndAnotherSaysWhatToStartItWith() {
        var listening = new LocalProcesses.Target(7, LocalProcesses.Kind.PYTHON, "app.py", "localhost", 5678);
        assertEquals(
                tr("debug.attachProcess.listening", tr("debug.attachProcess.kind.python"), "localhost:5678"),
                DebugCoordinator.processDetail(listening));
        var idle = new LocalProcesses.Target(8, LocalProcesses.Kind.JAVA, "Main", "localhost", 0);
        assertEquals(
                tr(
                        "debug.attachProcess.notListening",
                        tr("debug.attachProcess.kind.java"),
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"),
                DebugCoordinator.processDetail(idle));
        assertEquals("--inspect", DebugCoordinator.startFlag(LocalProcesses.Kind.NODE));
    }
}
