package com.editora.ui;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buffer: Reopen with Encoding / Save with Encoding through the real load, reload and save paths: a file in an
 * encoding detection cannot recognise is read correctly once the user names it, saved back in it, and opened in
 * it next time; a chosen encoding outranks {@code .editorconfig}; nothing is changed when the bytes or the text
 * do not fit the chosen encoding.
 */
@Tag("fx")
class EncodingCommandsFxTest {

    /** 日本語 LF in Shift-JIS. Not UTF-8, so detection falls back to a single-byte stand-in. */
    private static final String SJIS = "93fa967b8cea0a";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    @Test
    void reopeningAsShiftJisShowsTheTextSavesShiftJisAndIsRemembered(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("sjis.txt"), hex(SJIS));
            EditorBuffer buffer = load(fx, file);
            assertTrue(FxTestSupport.callOnFx(buffer::isCharsetAssumed), "detection cannot know it is Shift-JIS");
            assertTrue(FxTestSupport.callOnFx(
                    () -> encodingLabel(fx).getStyleClass().contains("status-encoding-assumed")));

            assertTrue(reopen(fx, buffer, "Shift_JIS"));

            assertEquals("日本語\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertFalse(FxTestSupport.callOnFx(buffer::isCharsetAssumed));
            assertEquals("Shift_JIS", FxTestSupport.callOnFx(buffer::getEffectiveCharset));
            assertEquals("Shift_JIS", statusSegment(fx));
            assertFalse(FxTestSupport.callOnFx(
                    () -> encodingLabel(fx).getStyleClass().contains("status-encoding-assumed")));
            assertEquals(tr("status.encoding.reopened", "sjis.txt", "Shift_JIS"), echo(fx));
            assertEquals("Shift_JIS", remembered(fx, file));

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x\n"));
            save(async, fx);
            assertEquals("780a" + SJIS, hex(Files.readAllBytes(file)), "edited and saved in Shift-JIS");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            EditorBuffer again = load(fx, file);
            assertEquals("x\n日本語\n", FxTestSupport.callOnFx(again::getContent), "the next open reads Shift-JIS");
            assertEquals("Shift_JIS", FxTestSupport.callOnFx(again::getUserCharset));
        }
    }

    @Test
    void reopeningAsWhatDetectionFindsAnywayIsNotRemembered(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("plain.txt"), "año\n");
            EditorBuffer buffer = load(fx, file);

            assertTrue(reopen(fx, buffer, "utf-8"));

            assertNull(remembered(fx, file));
            assertNull(FxTestSupport.callOnFx(buffer::getUserCharset));
            assertEquals("año\n", FxTestSupport.callOnFx(buffer::getContent));
        }
    }

    @Test
    void bytesThatAreNotValidInTheChosenEncodingChangeNothing(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("bytes.txt"), hex("610a62ff0a")); // a LF b 0xFF LF
            EditorBuffer buffer = load(fx, file);
            String before = FxTestSupport.callOnFx(buffer::getContent);

            assertFalse(reopen(fx, buffer, "utf-8"));

            assertEquals(before, FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isCharsetAssumed));
            assertNull(remembered(fx, file));
            assertEquals(tr("status.encoding.notValid", "bytes.txt", "UTF-8", "2", "2"), echo(fx));
        }
    }

    @Test
    void reopeningOverUnsavedEditsAsksFirst(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("sjis.txt"), hex(SJIS));
            EditorBuffer buffer = load(fx, file);
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "edit\n"));
            EncodingCommands encodings = encodings(fx);

            encodings.confirmDiscard = (b, charset) -> false;
            assertFalse(reopen(fx, buffer, "Shift_JIS"));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "declined: the edit stays");
            assertTrue(FxTestSupport.callOnFx(buffer::getContent).startsWith("edit\n"));

            encodings.confirmDiscard = (b, charset) -> true;
            assertTrue(reopen(fx, buffer, "Shift_JIS"));
            assertEquals("日本語\n", FxTestSupport.callOnFx(buffer::getContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void savingWithAnEncodingIsAPendingChangeUntilSaved(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("ru.txt"), "Привет\n");
            EditorBuffer buffer = load(fx, file);
            EncodingCommands encodings = encodings(fx);

            FxTestSupport.runOnFx(() -> encodings.saveIn(buffer, "windows-1251"));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "a new encoding is an unsaved change");
            assertEquals("windows-1251", statusSegment(fx));
            assertEquals(tr("status.encoding.willSave", "ru.txt", "windows-1251"), echo(fx));

            FxTestSupport.runOnFx(() -> encodings.saveIn(buffer, "utf-8"));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty), "back to what is on disk: clean again");

            FxTestSupport.runOnFx(() -> encodings.saveIn(buffer, "windows-1251"));
            save(async, fx);
            assertEquals(hex("Привет\n".getBytes(Charset.forName("windows-1251"))), hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals("windows-1251", FxTestSupport.callOnFx(buffer::getDetectedCharset));
            assertTrue(echo(fx).endsWith(tr("status.save.note.charsetChosen", "UTF-8", "windows-1251")), echo(fx));
            assertEquals("windows-1251", remembered(fx, file), "saved in it: the next open must read it in it");

            EditorBuffer again = load(fx, file);
            assertEquals("Привет\n", FxTestSupport.callOnFx(again::getContent));
        }
    }

    @Test
    void textTheChosenEncodingCannotHoldIsNotSwitched(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("mixed.txt"), "café\n日本\n");
            EditorBuffer buffer = load(fx, file);

            EncodingCommands encodings = encodings(fx);
            FxTestSupport.runOnFx(() -> encodings.saveIn(buffer, "latin1"));

            assertNull(FxTestSupport.callOnFx(buffer::getUserCharset));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(tr("status.encoding.cannotRepresent", "ISO-8859-1", "日", "2"), echo(fx));
        }
    }

    @Test
    void aCharacterTypedAfterChoosingRefusesTheSaveRatherThanWritingUtf8(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("note.txt"), "abc\n");
            EditorBuffer buffer = load(fx, file);
            EncodingCommands encodings = encodings(fx);
            FxTestSupport.runOnFx(() -> encodings.saveIn(buffer, "latin1"));
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "日\n"));

            save(async, fx);

            assertEquals("abc\n", Files.readString(file), "nothing written");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            assertEquals(
                    tr(
                            "status.save.cannotEncodeChosen",
                            "note.txt",
                            "ISO-8859-1",
                            "日",
                            "1",
                            tr("command.buffer.saveWithEncoding")),
                    echo(fx));
        }
    }

    @Test
    void anAssumedEncodingCannotBeSavedAsAnotherOne(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("sjis.txt"), hex(SJIS));
            EditorBuffer buffer = load(fx, file);
            String standIn = FxTestSupport.callOnFx(buffer::getDetectedCharset);

            CommandRegistry registry = registry(fx);
            FxTestSupport.runOnFx(() -> registry.run(EncodingCommands.SAVE));

            assertNull(FxTestSupport.callOnFx(buffer::getUserCharset));
            assertEquals(
                    tr(
                            "status.encoding.unknown",
                            "sjis.txt",
                            com.editora.editorconfig.EditorConfigCharset.displayName(standIn),
                            tr("command.buffer.reopenWithEncoding")),
                    echo(fx));
        }
    }

    @Test
    void aChosenEncodingOutranksEditorConfig(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\ncharset = utf-8\n");
            Path file = Files.write(dir.resolve("sjis.txt"), hex(SJIS));
            EditorBuffer buffer = load(fx, file);

            assertTrue(reopen(fx, buffer, "Shift_JIS"));
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x\n"));
            save(async, fx);

            assertEquals("780a" + SJIS, hex(Files.readAllBytes(file)), "not converted to UTF-8 by the rule");
        }
    }

    @Test
    void aRememberedEncodingTheFileNoLongerFitsIsForgotten(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            // 0x81 must be followed by a trail byte in Shift-JIS; a line feed is not one.
            Path file = Files.write(dir.resolve("broken.txt"), hex("61810a"));
            ConfigManager config = config(fx);
            FxTestSupport.runOnFx(
                    () -> config.getWorkspaceState().getFileEncodings().put(file.toString(), "Shift_JIS"));
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");

            String note = FxTestSupport.callOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setPath(file);
                return workflows.loadInto(buffer, file);
            });

            assertNull(remembered(fx, file));
            assertEquals(tr("status.encodingForgotten", "broken.txt", "Shift_JIS", "ISO-8859-1"), note);
        }
    }

    @Test
    void theStatusBarEncodingIsClickable() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            assertTrue(FxTestSupport.callOnFx(
                    () -> encodingLabel(fx).getStyleClass().contains("status-segment-clickable")));
            assertTrue(FxTestSupport.callOnFx(
                    () -> registry(fx).get(EncodingCommands.CHOOSE).isPresent()));
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private static boolean reopen(FxWindowFixture fx, EditorBuffer buffer, String charset) throws Exception {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        EncodingCommands encodings = encodings(fx);
        FxTestSupport.runOnFx(() -> encodings.reopenAs(buffer, charset, done::complete));
        boolean applied = done.get(10, TimeUnit.SECONDS);
        FxTestSupport.runOnFx(() -> {}); // let the reload's own FX follow-ups settle
        return applied;
    }

    private static EncodingCommands encodings(FxWindowFixture fx) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        return workflows.encodings();
    }

    private static CommandRegistry registry(FxWindowFixture fx) throws Exception {
        return FxTestSupport.field(fx.controller, "registry");
    }

    private static ConfigManager config(FxWindowFixture fx) throws Exception {
        return FxTestSupport.field(fx.controller, "config");
    }

    private static String remembered(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(
                () -> config(fx).getWorkspaceState().getFileEncodings().get(file.toString()));
    }

    private static EditorBuffer load(FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            workflows.loadInto(buffer, file);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static void save(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
        CommandRegistry registry = registry(fx);
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        async.awaitWorker(worker);
        async.awaitFx();
    }

    private static Label encodingLabel(FxWindowFixture fx) throws Exception {
        StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
        status.refresh();
        return FxTestSupport.field(status, "encoding");
    }

    private static String statusSegment(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> encodingLabel(fx).getText());
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<Label>field(status, "echo").getText();
        });
    }

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
