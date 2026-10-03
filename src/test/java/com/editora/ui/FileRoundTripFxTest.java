package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.logviewer.LogLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-exact load → edit → save through the real load ({@code prepareLoad}/{@code applyPreparedLoad}) and the
 * real save ({@code file.save} → {@code captureSave} → the atomic writer). Three independent defects each
 * rewrote a file the user had barely touched:
 *
 * <ul>
 *   <li>a BOM-less non-UTF-8 file was decoded with replacement, so every non-ASCII character went back to
 *       disk as {@code EF BF BD};
 *   <li>the editor's document never holds {@code \r}, and nothing remembered the file's line ending, so a
 *       CRLF file was saved as LF;
 *   <li>a filtered log's editor text is the matching subset, and that subset is what was written.
 * </ul>
 */
@Tag("fx")
class FileRoundTripFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    static Stream<Arguments> samples() {
        return Stream.of(
                // name, bytes on disk, expected status-bar line ending, expected status-bar encoding
                Arguments.of("lf", hex("6f6e650a74776f0a"), "LF", "UTF-8"),
                Arguments.of("crlf", hex("6f6e650d0a74776f0d0a"), "CRLF", "UTF-8"),
                Arguments.of("crlf-no-final-newline", hex("6f6e650d0a74776f"), "CRLF", "UTF-8"),
                Arguments.of("cr", hex("6f6e650d74776f0d"), "CR", "UTF-8"),
                Arguments.of("utf8-multibyte", "año café — 日本語\n".getBytes(StandardCharsets.UTF_8), "LF", "UTF-8"),
                Arguments.of("utf8-bom-crlf", hex("efbbbf61c3b16f0d0a"), "CRLF", "UTF-8 BOM"),
                Arguments.of("utf8-literal-replacement-char", hex("61efbfbd620a"), "LF", "UTF-8"),
                Arguments.of("utf16le-bom-crlf", hex("fffe61000d000a00f1000d000a00"), "CRLF", "UTF-16 LE"),
                Arguments.of("latin1", hex("61f16f20636166e90a"), "LF", "Windows-1252"),
                Arguments.of("latin1-crlf", hex("61f16f0d0a636166e90d0a"), "CRLF", "Windows-1252"),
                Arguments.of("windows-1252-punctuation", hex("93717591208520800a"), "LF", "Windows-1252"),
                // 日本語 in Shift-JIS plus the 0x81 0x40 ideographic space: 0x81 is undefined in windows-1252.
                Arguments.of("shift-jis", hex("93fa967b8cea81400d0a"), "CRLF", "ISO-8859-1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void anUneditedSaveLeavesEveryByteAlone(
            String name, byte[] original, String eol, String encoding, @TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve(name + ".txt"), original);
            EditorBuffer buffer = load(fx, file);

            assertFalse(FxTestSupport.callOnFx(buffer::isDirty), "loading must not dirty the buffer");
            assertEquals(eol, statusSegment(fx, "endings"), "the status bar shows the file's real line ending");
            assertEquals(encoding, statusSegment(fx, "encoding"), "the status bar shows the real charset");

            save(async, fx);

            assertEquals(hex(original), hex(Files.readAllBytes(file)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void anEditedSaveChangesOnlyTheEditedBytes(
            String name, byte[] original, String eol, String encoding, @TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve(name + ".txt"), original);
            EditorBuffer buffer = load(fx, file);

            // A new first line: one ASCII character and one line break, which must take the file's own form.
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x\n"));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            save(async, fx);

            String separator = "CRLF".equals(eol) ? "\r\n" : "CR".equals(eol) ? "\r" : "\n";
            assertEquals(hex(expectedAfterInsert(original, "x" + separator, encoding)), hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty), "the save is acknowledged against the same text");
        }
    }

    @Test
    void aMixedFileIsSavedInItsDominantLineEnding(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path mostlyCrlf = Files.write(dir.resolve("mostly-crlf.txt"), hex("610d0a620d0a630a640d0a"));
            Path mostlyLf = Files.write(dir.resolve("mostly-lf.txt"), hex("610a620a630d0a640a"));

            EditorBuffer crlf = load(fx, mostlyCrlf);
            FxTestSupport.runOnFx(() -> crlf.getArea().insertText(0, "x\n"));
            save(async, fx);
            assertEquals("780d0a610d0a620d0a630d0a640d0a", hex(Files.readAllBytes(mostlyCrlf)));

            EditorBuffer lf = load(fx, mostlyLf);
            FxTestSupport.runOnFx(() -> lf.getArea().insertText(0, "x\n"));
            save(async, fx);
            assertEquals("780a610a620a630a640a", hex(Files.readAllBytes(mostlyLf)));
        }
    }

    @Test
    void undoingAnEditInACrlfFileClearsTheDirtyFlag(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("undo.txt"), hex("6f6e650d0a74776f0d0a"));
            EditorBuffer buffer = load(fx, file);

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x"));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty));
            FxTestSupport.runOnFx(() -> buffer.getArea().deleteText(0, 1));

            assertFalse(
                    FxTestSupport.callOnFx(buffer::isDirty),
                    "the baseline is kept in the editor's own form, so the reverted text equals it again");
        }
    }

    @Test
    void convertingLineEndingsReachesTheDisk(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("convert.txt"), hex("6f6e650a74776f0a"));
            EditorBuffer buffer = load(fx, file);

            FxTestSupport.runOnFx(() -> buffer.convertLineEndings(true));
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "a pending conversion is an unsaved change");
            assertEquals("CRLF", FxTestSupport.callOnFx(buffer::getLineEnding));
            save(async, fx);
            assertEquals("6f6e650d0a74776f0d0a", hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            FxTestSupport.runOnFx(() -> buffer.convertLineEndings(false));
            save(async, fx);
            assertEquals("6f6e650a74776f0a", hex(Files.readAllBytes(file)));
        }
    }

    @Test
    void editorConfigEndOfLineStillWinsOverTheDetectedOne(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\nend_of_line = lf\n");
            Path file = Files.write(dir.resolve("forced.txt"), hex("6f6e650d0a74776f0d0a"));
            EditorBuffer buffer = load(fx, file);

            assertEquals("LF", FxTestSupport.callOnFx(buffer::getLineEnding));
            assertTrue(FxTestSupport.callOnFx(buffer::isLineEndingForced));
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x\n"));
            save(async, fx);

            assertEquals("780a6f6e650a74776f0a", hex(Files.readAllBytes(file)));
        }
    }

    @Test
    void anUndecodableFileSaysWhichEncodingWasAssumed(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.write(dir.resolve("legacy.txt"), hex("61f16f0a"));
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");

            String note = FxTestSupport.callOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setPath(file);
                return workflows.loadInto(buffer, file);
            });

            assertEquals(tr("status.charsetAssumed", "legacy.txt", "UTF-8", "Windows-1252"), note);

            // 0x81 is undefined in windows-1252, so this one can only be kept exact as ISO-8859-1.
            Path sjis = Files.write(dir.resolve("sjis.txt"), hex("93fa81400a"));
            String sjisNote = FxTestSupport.callOnFx(() -> {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setPath(sjis);
                return workflows.loadInto(buffer, sjis);
            });
            assertEquals(tr("status.charsetAssumed", "sjis.txt", "UTF-8", "ISO-8859-1"), sjisNote);
        }
    }

    @Test
    void aWindows1252FileShowsItsPunctuationAndSavesTheSameBytes(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            byte[] original = hex("93689420800d0a"); // “h” € CRLF
            Path file = Files.write(dir.resolve("quotes.txt"), original);
            EditorBuffer buffer = load(fx, file);

            assertEquals(
                    "\u201Ch\u201D \u20AC\n",
                    FxTestSupport.callOnFx(buffer::getContent),
                    "curly quotes and the euro sign, not C1 control characters");
            assertEquals("Windows-1252", statusSegment(fx, "encoding"));
            save(async, fx);
            assertEquals(hex(original), hex(Files.readAllBytes(file)), "an unedited save is byte-identical");

            // Typing more windows-1252 punctuation stays in windows-1252: no UTF-8 fallback, no '?'.
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "\u2014x\n"));
            save(async, fx);
            assertEquals("97780d0a" + hex(original), hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    @Test
    void anEditorConfigCharsetThatCannotDecodeTheFileDoesNotReencodeIt(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\ncharset = utf-8\n");
            byte[] original = hex("93fa967b8cea0a"); // Shift-JIS, which the declared utf-8 cannot read
            Path file = Files.write(dir.resolve("sjis.txt"), original);
            EditorBuffer buffer = load(fx, file);

            assertEquals("ISO-8859-1", statusSegment(fx, "encoding"));
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x\n"));
            save(async, fx);

            assertEquals("780a" + hex(original), hex(Files.readAllBytes(file)));
        }
    }

    @Test
    void savingAFilteredLogWritesTheWholeLog(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            String log = "2026-01-01 INFO started\n2026-01-01 ERROR failed\n2026-01-01 INFO stopped\n";
            Path file = Files.writeString(dir.resolve("app.log"), log);
            EditorBuffer buffer = load(fx, file);

            boolean editableBefore = FxTestSupport.callOnFx(buffer::isEditable);
            FxTestSupport.runOnFx(() -> buffer.applyLogFilter(LogLevel.ERROR, null));
            assertEquals(
                    "2026-01-01 ERROR failed",
                    FxTestSupport.callOnFx(buffer::getVisibleContent).strip());
            assertEquals(log, FxTestSupport.callOnFx(buffer::getContent), "the document is still the whole log");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty), "filtering is a view change, not an edit");
            assertFalse(FxTestSupport.callOnFx(buffer::isEditable), "a filtered view cannot map edits back");

            save(async, fx);
            assertEquals(log, Files.readString(file), "the filter must never reach the disk");

            FxTestSupport.runOnFx(() -> buffer.appendLogText("2026-01-01 DEBUG ignored\n"));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty), "followed text comes from the file itself");
            assertEquals(log + "2026-01-01 DEBUG ignored\n", FxTestSupport.callOnFx(buffer::getContent));

            FxTestSupport.runOnFx(() -> buffer.applyLogFilter(null, null));
            assertEquals(log + "2026-01-01 DEBUG ignored\n", FxTestSupport.callOnFx(buffer::getVisibleContent));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty), "clearing the filter is not an edit either");
            assertEquals(editableBefore, FxTestSupport.callOnFx(buffer::isEditable));
        }
    }

    @Test
    void reloadingAFilteredLogDoesNotLeaveTheOldTextBehindTheFilter(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Path file = Files.writeString(dir.resolve("reload.log"), "INFO old\nERROR old\n");
            EditorBuffer buffer = load(fx, file);
            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            FxTestSupport.runOnFx(() -> buffer.applyLogFilter(LogLevel.ERROR, null));

            Files.writeString(file, "INFO new\nERROR new\nERROR newer\n");
            FxTestSupport.callOnFx(() -> workflows.loadInto(buffer, file));

            assertEquals("INFO new\nERROR new\nERROR newer\n", FxTestSupport.callOnFx(buffer::getContent));
            assertTrue(FxTestSupport.callOnFx(buffer::isLogFiltered), "the filter the control shows is still applied");
            assertFalse(FxTestSupport.callOnFx(buffer::getVisibleContent).contains("INFO"));
        }
    }

    @Test
    void aLogTrimmedByFollowModeIsNotSavedOverItsFile(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            String log = "INFO one\nINFO two\nINFO three\n";
            Path file = Files.writeString(dir.resolve("trimmed.log"), log);
            EditorBuffer buffer = load(fx, file);
            // Reaching the real 12 MB cap would push megabytes through a CodeArea; LogViewFxTest proves the
            // trim sets this flag, and this test drives everything downstream of it through the real command.
            FxTestSupport.runOnFx(() -> {
                try {
                    Object logView = FxTestSupport.field(buffer, "logView");
                    var trimmed = logView.getClass().getDeclaredField("trimmed");
                    trimmed.setAccessible(true);
                    trimmed.setBoolean(logView, true);
                    buffer.getArea().deleteText(0, "INFO one\n".length());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            });

            save(async, fx);

            assertEquals(log, Files.readString(file), "the tail must not replace the whole log");
            assertEquals(tr("status.log.trimmedNoSave", "trimmed.log"), echo(fx));
        }
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

    /** {@code file.save} on the active tab, then the write worker and its FX acknowledgment. */
    private static void save(AsyncTestScope async, FxWindowFixture fx) throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
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

    /** The original bytes with {@code inserted} (ASCII) placed after any BOM, in the file's own code units. */
    private static byte[] expectedAfterInsert(byte[] original, String inserted, String encoding) {
        boolean utf16 = "UTF-16 LE".equals(encoding);
        int bom = "UTF-8 BOM".equals(encoding) ? 3 : utf16 ? 2 : 0;
        byte[] insert = inserted.getBytes(utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.US_ASCII);
        byte[] out = new byte[original.length + insert.length];
        System.arraycopy(original, 0, out, 0, bom);
        System.arraycopy(insert, 0, out, bom, insert.length);
        System.arraycopy(original, bom, out, bom + insert.length, original.length - bom);
        return out;
    }

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
