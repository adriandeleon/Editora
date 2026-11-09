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

    @Test
    void aGoProgramIsAttachedByItsProcessId() {
        var go = new LocalProcesses.Target(9, LocalProcesses.Kind.GO, "server", "", 0);
        assertEquals(
                tr("debug.attachProcess.byPid", tr("debug.attachProcess.kind.go")), DebugCoordinator.processDetail(go));
        assertEquals("", DebugCoordinator.startFlag(LocalProcesses.Kind.GO), "nothing to start it with");
    }

    @Test
    void delveBuildsWithTheGoTheGoBuildToolRuns() {
        java.nio.file.Path go = java.nio.file.Path.of("/opt/go/bin/go").toAbsolutePath();
        assertEquals(
                java.util.List.of(go.getParent().toString()),
                DebugCoordinator.goToolDirs(java.util.List.of(go.toString(), "-x")));
        assertEquals(java.util.List.of(), DebugCoordinator.goToolDirs(java.util.List.of("go")), "found on the PATH");
        assertEquals(java.util.List.of(), DebugCoordinator.goToolDirs(java.util.List.of()));
    }

    @Test
    void aNativeProgramIsAttachedByCodelldb() {
        var app = new LocalProcesses.Target(9, LocalProcesses.Kind.NATIVE, "app", "", 0);
        assertEquals(
                tr("debug.attachProcess.byPidLldb", tr("debug.attachProcess.kind.native")),
                DebugCoordinator.processDetail(app));
        var rust = new LocalProcesses.Target(9, LocalProcesses.Kind.RUST, "shelf", "", 0);
        assertEquals(
                tr("debug.attachProcess.byPidLldb", tr("debug.attachProcess.kind.rust")),
                DebugCoordinator.processDetail(rust));
        assertEquals("", DebugCoordinator.startFlag(LocalProcesses.Kind.NATIVE));
    }
}
