package com.editora.vfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;
import com.editora.process.TerminalChannel;
import com.editora.process.TerminalSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terminals on an SSH host, against the in-process server: a shell starts in the folder asked for and answers
 * what it is typed, a terminal takes one of the connection's long-lived slots and gives it back, and the end of a
 * shell says whether the connection went. The server runs commands without a real tty, so the PTY itself (size,
 * SIGWINCH, the hang-up on a drop) is the real-server probe's to check (RealSshdProbeFxTest).
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class SshTerminalFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture fixture;
    private Path remoteDir;
    private final List<TerminalChannel> opened = new ArrayList<>();

    @BeforeEach
    void connect() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        fixture = EmbeddedSftpFixture.startWithShell(base);
        remoteDir = fixture.remotePath("project dir");
        Files.createDirectories(remoteDir);
    }

    @AfterEach
    void close() throws Exception {
        opened.forEach(TerminalChannel::close);
        fixture.close();
        ExecTargets.setRemoteEnabled(null);
    }

    @Test
    void aShellStartsInTheFolderAndAnswersWhatItIsTyped() throws Exception {
        TerminalChannel shell = open();
        type(shell, "pwd -P; echo $((6 * 7)) $TERM_PROGRAM\n");

        String out = readUntil(shell, "42 Editora");
        assertTrue(
                out.contains(fixture.serverPath("project dir").toRealPath().toString()),
                () -> "started in the project folder: " + out);
    }

    @Test
    void aResizeIsSentWithoutDisturbingTheShell() throws Exception {
        TerminalChannel shell = open();
        shell.resize(132, 50);
        type(shell, "echo still here\n");
        readUntil(shell, "still here");
    }

    @Test
    void eachTerminalTakesALongLivedSlotAndGivesItBack() throws Exception {
        ExecTarget target = ExecTargets.forPath(remoteDir);
        for (int i = 0; i < SshExecTarget.MAX_LONG_LIVED; i++) {
            open();
        }
        IOException refused = assertThrows(IOException.class, this::open);
        assertTrue(refused.getMessage().contains("each terminal on it is one"), refused.getMessage());

        TerminalChannel first = opened.removeFirst();
        first.close();
        first.waitFor();
        TerminalChannel again = target.startTerminal(new TerminalSpec(remoteDir, 80, 24));
        opened.add(again);
        assertTrue(again.isOpen(), "a closed terminal's slot is free again");
    }

    @Test
    void aShellThatExitsDidNotLoseItsConnection() throws Exception {
        TerminalChannel shell = open();
        type(shell, "exit 3\n");
        assertTrue(waitFor(shell), "the channel closes when the shell exits");
        assertFalse(shell.lostConnection());
        assertEquals(3, shell.waitFor());
    }

    @Test
    void aTerminalEditoraClosedDidNotLoseItsConnection() throws Exception {
        TerminalChannel shell = open();
        shell.close();
        assertTrue(waitFor(shell));
        assertFalse(shell.lostConnection());
    }

    @Test
    void aDroppedConnectionIsReportedAsSuch() throws Exception {
        TerminalChannel shell = open();
        type(shell, "echo up\n");
        readUntil(shell, "up");

        fixture.dropConnection();

        assertTrue(waitFor(shell), "the channel closes with the connection");
        assertTrue(shell.lostConnection(), "so the terminal offers to reconnect instead of closing its tab");
    }

    // ---------------------------------------------------------------- helpers

    private TerminalChannel open() throws IOException {
        TerminalChannel shell = ExecTargets.forPath(remoteDir).startTerminal(new TerminalSpec(remoteDir, 80, 24));
        opened.add(shell);
        return shell;
    }

    private static void type(TerminalChannel shell, String text) throws IOException {
        shell.input().write(text.getBytes(StandardCharsets.UTF_8));
        shell.input().flush();
    }

    /** Reads the shell's output until it contains {@code needle}; fails after 20 s. */
    private static String readUntil(TerminalChannel shell, String needle) throws Exception {
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = shell.output().read(buf)) > 0) {
                    synchronized (seen) {
                        seen.write(buf, 0, n);
                    }
                    if (text(seen).contains(needle)) {
                        return;
                    }
                }
            } catch (IOException ended) {
                // the channel closed
            }
        });
        reader.join(20_000);
        String out = text(seen);
        assertTrue(out.contains(needle), () -> "expected \"" + needle + "\" in: " + out);
        return out;
    }

    private static String text(ByteArrayOutputStream bytes) {
        synchronized (bytes) {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    private static boolean waitFor(TerminalChannel shell) throws InterruptedException {
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                shell.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.join(TimeUnit.SECONDS.toMillis(20));
        return !waiter.isAlive();
    }
}
