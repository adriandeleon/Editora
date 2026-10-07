package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saves that used to change bytes the user never touched, or wrote something other than the document,
 * while reporting an ordinary "Saved": through the real load and the real {@code file.save}.
 */
@Tag("fx")
class SaveFidelityFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    // --- S5/E7: half a character ---------------------------------------------------------------------

    @Test
    void aSplitEmojiIsNotSavedAsQuestionMarks(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            byte[] original = "ab😀cd\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path file = Files.write(dir.resolve("emoji.txt"), original);
            EditorBuffer buffer = load(fx, file);

            // An edit lands between the two halves of the emoji (offset 3).
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(3, "X"));
            save(async, fx);

            assertEquals(hex(original), hex(Files.readAllBytes(file)), "no '?' and no byte-order mark on disk");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "the buffer still holds text that is not saved");
            assertStatus(fx, tr("status.save.cannotEncodeSurrogate", "emoji.txt", "1", "U+D83D"));
            assertEquals("UTF-8", statusSegment(fx, "encoding"), "the file's encoding is not switched");
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static FileWorkflowCoordinator workflows(FxWindowFixture fx) {
        return FxTestSupport.field(fx.controller, "fileWorkflows");
    }

    private static EditorBuffer load(FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = workflows(fx);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            workflows.loadInto(buffer, file);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    /** {@code file.save} on the active tab, then the write worker and its FX acknowledgment. */
    private static void save(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        ExecutorService worker = FxTestSupport.field(workflows(fx), "autoSaveExecutor");
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        async.awaitWorker(worker);
        async.awaitFx();
    }

    private static String statusSegment(FxWindowFixture fx, String field) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            status.refresh();
            return FxTestSupport.<Label>field(status, field).getText();
        });
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }

    /** The status bar's message; the bar shortens a long one, so that is compared by what is shown of it. */
    private static void assertStatus(FxWindowFixture fx, String expected) throws Exception {
        String shown = echo(fx);
        String visible =
                shown.endsWith("…") ? shown.substring(0, shown.length() - 1).stripTrailing() : shown;
        assertTrue(
                shown.endsWith("…") ? visible.length() > 40 && expected.startsWith(visible) : expected.equals(shown),
                "status: " + shown + "\nexpected: " + expected);
    }

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
