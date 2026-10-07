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
            byte[] original = "ab😀cd\n".getBytes(StandardCharsets.UTF_8);
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

    // --- S1: mixed line terminators -------------------------------------------------------------------

    static Stream<Arguments> mixedFiles() {
        return Stream.of(
                Arguments.of("lone-cr-in-lf", "610d620a630a640a", null),
                Arguments.of("tie-crlf-lf", "610d0a620a", null),
                Arguments.of("cr-crlf-mix", "610d0d0a620d0d0a", null),
                Arguments.of("mostly-crlf", "610d0a620d0a630a640d0a", null),
                Arguments.of("latin1-mixed", "e90d0a620a630a", null),
                // A project rule is not the user agreeing to have an untouched file rewritten.
                Arguments.of("rule-eol", "610d0a620a630a", "end_of_line = lf\n"),
                Arguments.of(
                        "rule-trim",
                        "6120200d0a620a630a0a0a",
                        "trim_trailing_whitespace = true\ninsert_final_newline = false\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mixedFiles")
    void anUneditedSaveOfAMixedFileLeavesEveryByteAlone(String name, String bytes, String rules, @TempDir Path dir)
            throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            if (rules != null) {
                Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\n" + rules);
            }
            Path file = Files.write(dir.resolve(name + ".txt"), hex(bytes));
            EditorBuffer buffer = load(fx, file);

            save(async, fx);

            assertEquals(bytes, hex(Files.readAllBytes(file)));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            // Edit and undo by hand: the text is the loaded text again, so are the bytes.
            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x"));
            FxTestSupport.runOnFx(() -> buffer.getArea().deleteText(0, 1));
            save(async, fx);
            assertEquals(bytes, hex(Files.readAllBytes(file)));
        }
    }

    /** A script followed, past the first 8000 bytes, by a binary payload: opened as text by the sniff. */
    private static byte[] installer() {
        StringBuilder script = new StringBuilder("#!/bin/sh\n");
        while (script.length() < 9000) {
            script.append("echo unpacking\n");
        }
        byte[] head = script.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] payload = hex("1f8b0800000000000003ed0d0a0d0001ff0a0d0d0a8190");
        byte[] all = new byte[head.length + payload.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(payload, 0, all, head.length, payload.length);
        return all;
    }

    @Test
    void aTextFileWithABinaryTailIsNeverToldThatNoBytesAreLost(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            byte[] original = installer();
            Path file = Files.write(dir.resolve("installer.run"), original);
            assertFalse(BinarySniff.looksBinary(java.util.Arrays.copyOf(original, BinarySniff.SAMPLE_BYTES)));

            String note = FxTestSupport.callOnFx(() -> {
                EditorBuffer probe = new EditorBuffer();
                probe.setPath(file);
                return workflows(fx).loadInto(probe, file);
            });
            assertEquals(
                    tr("status.charsetAssumedMixedBinary", "installer.run", "UTF-8", "ISO-8859-1", "LF"),
                    note,
                    "the note must describe the mixed terminators, not promise that no bytes are lost");
            assertFalse(note.contains("no bytes are lost"), note);

            EditorBuffer buffer = load(fx, file);
            assertEquals(tr("statusbar.endings.mixed", "LF"), statusSegment(fx, "endings"));
            save(async, fx);
            assertEquals(hex(original), hex(Files.readAllBytes(file)), "an unedited save changes nothing");
        }
    }

    @Test
    void anEditToATextFileWithABinaryTailIsOnlySavedOnceTheUserAgrees(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            byte[] original = installer();
            Path file = Files.write(dir.resolve("installer.run"), original);
            EditorBuffer buffer = load(fx, file);
            java.util.List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicBoolean agree = new java.util.concurrent.atomic.AtomicBoolean();
            workflows(fx).mixedBinaryConsent = (target, ending) -> {
                asked.add(target.getFileName() + " " + ending);
                return agree.get();
            };

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "#"));
            save(async, fx);

            assertEquals(java.util.List.of("installer.run LF"), asked, "an explicit save asks");
            assertEquals(hex(original), hex(Files.readAllBytes(file)), "declined: not one byte is changed");
            assertTrue(FxTestSupport.callOnFx(buffer::isDirty), "and the edit is still unsaved");
            assertStatus(fx, tr("status.save.cannotSaveMixedBinary", "installer.run", "LF", tr("command.file.saveAs")));

            // A background save never asks, and never rewrites the payload.
            FxTestSupport.runOnFx(() -> workflows(fx).autoSaveBuffer(buffer));
            async.awaitWorker(FxTestSupport.field(workflows(fx), "autoSaveExecutor"));
            async.awaitFx();
            assertEquals(1, asked.size());
            assertEquals(hex(original), hex(Files.readAllBytes(file)));

            agree.set(true);
            save(async, fx);

            byte[] saved = Files.readAllBytes(file);
            assertEquals('#', saved[0]);
            assertFalse(new String(saved, StandardCharsets.ISO_8859_1).contains("\r"), "normalised, as agreed");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
            try (var copies = Files.list(fx.configDir.resolve(MixedLineEndings.ORIGINALS_FOLDER))) {
                assertEquals(
                        hex(original),
                        hex(Files.readAllBytes(copies.findFirst().orElseThrow())),
                        "and the payload as it was is kept");
            }
        }
    }

    @Test
    void aMixedFileSaysSoWhileItIsOpenAndUntilASaveMakesItUniform(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            byte[] original = hex("610a620a630d0a640a");
            Path file = Files.write(dir.resolve("mostly-lf.txt"), original);

            String note = FxTestSupport.callOnFx(() -> {
                EditorBuffer probe = new EditorBuffer();
                probe.setPath(file);
                return workflows(fx).loadInto(probe, file);
            });
            assertEquals(tr("status.mixedLineEndings", "mostly-lf.txt", "LF"), note);

            EditorBuffer buffer = load(fx, file);
            assertEquals(tr("statusbar.endings.mixed", "LF"), statusSegment(fx, "endings"));

            FxTestSupport.runOnFx(() -> buffer.getArea().insertText(0, "x\n"));
            save(async, fx);

            // Pinned: an edited mixed file is written in its dominant ending. Never silently, though.
            assertEquals("780a610a620a630a640a", hex(Files.readAllBytes(file)));
            Path kept;
            try (var copies = Files.list(fx.configDir.resolve(MixedLineEndings.ORIGINALS_FOLDER))) {
                kept = copies.findFirst().orElseThrow();
            }
            assertEquals(hex(original), hex(Files.readAllBytes(kept)), "the bytes as they were are still on disk");
            assertStatus(
                    fx,
                    tr("status.saved", com.editora.config.PathDisplay.of(file)) + " "
                            + tr("status.save.note.mixedLineEndings", "LF", com.editora.config.PathDisplay.of(kept)));
            assertEquals("LF", statusSegment(fx, "endings"), "the file is uniform now");
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));
        }
    }

    // --- S4: EditorConfig rules that rewrite untouched bytes ------------------------------------------

    @Test
    void aRuleThatStripsTheByteOrderMarkSaysSoOnce(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\ncharset = utf-8\n");
            Path file = Files.write(dir.resolve("script.ps1"), hex("efbbbf610a"));
            load(fx, file);

            save(async, fx);

            assertEquals("610a", hex(Files.readAllBytes(file)), "the rule still applies");
            String saved = tr("status.saved", com.editora.config.PathDisplay.of(file));
            assertStatus(fx, saved + " " + tr("status.save.note.charset", "UTF-8 BOM", "UTF-8", "utf-8"));

            save(async, fx);
            assertStatus(fx, saved);
        }
    }

    @Test
    void aRuleThatSwapsTheByteOrderSaysSo(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\ncharset = utf-16be\n");
            Path file = Files.write(dir.resolve("wide.txt"), hex("fffe61000a00"));
            load(fx, file);

            save(async, fx);

            assertEquals("feff0061000a", hex(Files.readAllBytes(file)));
            assertStatus(
                    fx,
                    tr("status.saved", com.editora.config.PathDisplay.of(file)) + " "
                            + tr("status.save.note.charset", "UTF-16 LE", "UTF-16 BE", "utf-16be"));
        }
    }

    @Test
    void aRuleThatDropsTrailingBlankLinesSaysHowMany(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            Files.writeString(dir.resolve(".editorconfig"), "root = true\n[*]\ninsert_final_newline = false\n");
            Path file = Files.write(dir.resolve("blank.txt"), hex("610a0a0a0a"));
            load(fx, file);

            save(async, fx);

            assertEquals("61", hex(Files.readAllBytes(file)), "pinned by EditorConfigTransformTest");
            assertStatus(
                    fx,
                    tr("status.saved", com.editora.config.PathDisplay.of(file)) + " "
                            + tr("status.save.note.finalNewline", 4));
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
