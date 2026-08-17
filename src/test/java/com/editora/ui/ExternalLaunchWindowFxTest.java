package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file-manager launch handed to a running Editora gets a <b>window of its own</b>, and does not take over
 * the one already in use.
 *
 * <p>Before the single-instance handoff, such a click started its own process and therefore its own window.
 * The handoff was meant to stop duplicating the <em>process</em>, not to change what the click does — but it
 * landed the file as a tab in whatever window was in front, taking over work in progress and, for the "Expert
 * Mode" launcher entry, restyling that window's chrome too.
 *
 * <p>The exception pinned here is just as important: if the file is <em>already</em> open, the window holding
 * it is reused rather than a second one opened. Two independent buffers over one file loses edits — save one
 * and the other is silently stale — and re-clicking a file you already have open is an ordinary thing to do.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalLaunchWindowFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aForwardedLaunchOpensItsOwnWindowInsteadOfTakingOverTheCurrentOne() throws Exception {
        Path dir = Files.createTempDirectory("editora-external-window");
        Path working = Files.writeString(dir.resolve("working-on.txt"), "in progress\n");
        Path clicked = Files.writeString(dir.resolve("clicked.txt"), "from the file manager\n");

        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(working, 0, 0)), true, c -> {});
        try {
            assertEquals(1, windowCount(fx), "precondition: one window");

            deliver(fx, clicked);

            assertEquals(2, windowCount(fx), "the launch should have opened a window of its own");
            assertTrue(hasFileOpen(fx, clicked), "the clicked file should be open somewhere");
        } finally {
            fx.dispose();
        }
    }

    @Test
    void relaunchingAFileThatIsAlreadyOpenReusesItsWindowRatherThanDuplicatingTheBuffer() throws Exception {
        Path dir = Files.createTempDirectory("editora-external-window");
        Path clicked = Files.writeString(dir.resolve("clicked.txt"), "from the file manager\n");

        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(clicked, 0, 0)), true, c -> {});
        try {
            assertEquals(1, windowCount(fx));
            assertTrue(hasFileOpen(fx, clicked), "precondition: the file is already open");

            deliver(fx, clicked);

            assertEquals(1, windowCount(fx), "a file already open must not get a second window and buffer");
        } finally {
            fx.dispose();
        }
    }

    /** Applies an external launch the way {@code App.openForwardedLaunch} does. */
    private static void deliver(FxWindowFixture fx, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> fx.windowManager.openExternalLaunchInNewWindow(
                List.of(new MainController.OpenTarget(file, 0, 0)), false, true, false));
    }

    private static int windowCount(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<?> windows = FxTestSupport.field(fx.windowManager, "windows");
            return windows.size();
        });
    }

    private static boolean hasFileOpen(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<?> windows = FxTestSupport.field(fx.windowManager, "windows");
            for (Object holder : windows) {
                MainController c = FxTestSupport.field(holder, "controller");
                if (c != null && c.hasFileOpen(file)) {
                    return true;
                }
            }
            return false;
        });
    }
}
