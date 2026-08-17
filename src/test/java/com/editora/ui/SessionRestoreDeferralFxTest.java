package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session's <b>background</b> files are filled only after the selected file has had a frame to paint —
 * and, crucially, they are still all filled.
 *
 * <p>{@code fillSessionFiles} restores one buffer per pulse, which kept those fills landing on the FX thread
 * inside the very pulses that lay out and render the file the user asked for, pushing back the frame they are
 * waiting for. It now yields a couple of frames after the first (front-loaded) file before continuing.
 *
 * <p>The risk that buys is the one worth pinning: the continuation moved onto an {@code AnimationTimer}, so a
 * bug there would strand every background file empty forever — a far worse outcome than the milliseconds it
 * saves. Hence both directions are asserted: not-yet-filled on the first frame, all-filled once settled.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionRestoreDeferralFxTest {

    private static final String SELECTED_TEXT = "selected-file-content\n";
    private static final String BACKGROUND_TEXT = "background-file-content\n";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void backgroundSessionFilesAreDeferredPastTheFirstFrameButStillAllRestored() throws Exception {
        Path dir = Files.createTempDirectory("editora-session-deferral");
        Path selected = Files.writeString(dir.resolve("selected.txt"), SELECTED_TEXT);
        Path background1 = Files.writeString(dir.resolve("background1.txt"), BACKGROUND_TEXT);
        Path background2 = Files.writeString(dir.resolve("background2.txt"), BACKGROUND_TEXT);
        seedSession(dir, selected, background1, background2);

        List<Integer> filledOnFirstFrame = new ArrayList<>();
        FxWindowFixture fx = FxWindowFixture.create(
                dir,
                false,
                false,
                false,
                List.of(),
                controller ->
                        // Same runnable as the build, so no queued runLater/pulse has executed: nothing is filled yet.
                        filledOnFirstFrame.add(filledBufferCount(controller)));
        try {
            assertEquals(0, filledOnFirstFrame.get(0), "no session file should be filled during the build itself");

            // Settle: every one of the three files must end up with its content, the front-loaded one and
            // the two that now wait on animation frames alike.
            assertTrue(
                    waitUntil(() -> filledBufferCount(fx.controller) == 3),
                    "all 3 session files should be restored; got " + filledBufferCount(fx.controller));
        } finally {
            fx.dispose();
        }
    }

    /** How many open editor buffers currently hold their file's text. */
    private static int filledBufferCount(MainController controller) {
        TabPane tabPane = FxTestSupport.field(controller, "tabPane");
        int filled = 0;
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getUserData() instanceof EditorBuffer buffer
                    && !buffer.getContent().isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    /** Polls {@code condition} on the FX thread until it holds, or ~5 s elapse. */
    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /** Seeds a session whose open files are {@code files}, with the first one active. */
    private static void seedSession(Path dir, Path... files) throws Exception {
        StringBuilder open = new StringBuilder();
        for (Path f : files) {
            if (!open.isEmpty()) {
                open.append(',');
            }
            open.append("{\"path\":\"").append(f.toAbsolutePath()).append("\"}");
        }
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":1,\"openFiles\":[" + open + "],\"activeFile\":\"" + files[0].toAbsolutePath()
                        + "\"}");
    }
}
