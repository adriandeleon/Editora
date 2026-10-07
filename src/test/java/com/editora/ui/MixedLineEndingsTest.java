package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.editorconfig.EditorConfigCharset;
import com.editora.editorconfig.EditorConfigProperties;
import com.editora.ui.MixedLineEndings.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a save does to a file that mixes line terminators, and what it reports having changed. */
class MixedLineEndingsTest {

    private static final Path FILE = Path.of("/work/mixed.txt");

    private static MixedLineEndings.Source source(String text, boolean binary) {
        return new MixedLineEndings.Source(
                FILE, text.getBytes(StandardCharsets.ISO_8859_1), text, "LF", binary, new AtomicReference<>());
    }

    @Test
    void aUniformFileIsSavedTheOrdinaryWay() {
        assertEquals(Decision.NORMAL, MixedLineEndings.decide(null, "a\nb\n", "LF", false, FILE));
    }

    @Test
    void theLoadedTextGoesBackAsTheLoadedBytes() {
        MixedLineEndings.Source source = source("a\nb\nc\n", false);
        assertEquals(Decision.KEEP_BYTES, MixedLineEndings.decide(source, "a\nb\nc\n", "LF", false, FILE));
        // An end_of_line rule changes the label, not the user's mind: the untouched file stays untouched.
        assertEquals(Decision.KEEP_BYTES, MixedLineEndings.decide(source, "a\nb\nc\n", "CRLF", true, FILE));
        assertEquals(
                Decision.KEEP_BYTES,
                MixedLineEndings.decide(source, "a\nb\nc\n", "LF", false, Path.of("/work/copy.txt")),
                "Save As of an unedited buffer is a byte copy");
        assertEquals(
                Decision.KEEP_BYTES,
                MixedLineEndings.decide(source("a\0\nb\n", true), "a\0\nb\n", "LF", false, FILE),
                "binary or not");
    }

    @Test
    void anEditNormalisesAndConvertingIsAnExplicitRequestToDoSo() {
        MixedLineEndings.Source source = source("a\nb\nc\n", false);
        assertEquals(Decision.NORMALISE, MixedLineEndings.decide(source, "xa\nb\nc\n", "LF", false, FILE));
        assertEquals(
                Decision.NORMALISE,
                MixedLineEndings.decide(source, "a\nb\nc\n", "CRLF", false, FILE),
                "Convert Line Endings on an unedited buffer");
    }

    @Test
    void binaryDataIsOnlyRewrittenWithConsentOrBesideTheOriginal() {
        MixedLineEndings.Source binary = source("#!/bin/sh\n\0\u001f\n", true);
        assertEquals(
                Decision.NORMALISE_WITH_CONSENT,
                MixedLineEndings.decide(binary, "x#!/bin/sh\n\0\u001f\n", "LF", false, FILE));
        assertEquals(
                Decision.NORMALISE_WITH_CONSENT,
                MixedLineEndings.decide(binary, "x#!/bin/sh\n\0\u001f\n", "CRLF", true, FILE),
                "a project rule is not the user's consent");
        assertEquals(
                Decision.NORMALISE,
                MixedLineEndings.decide(binary, "x#!/bin/sh\n\0\u001f\n", "CRLF", false, FILE),
                "the user chose another line ending for this buffer");
        assertEquals(
                Decision.NORMALISE,
                MixedLineEndings.decide(binary, "x#!/bin/sh\n\0\u001f\n", "LF", false, Path.of("/work/copy.run")),
                "a copy leaves the original on disk");
    }

    @Test
    void theOriginalBytesAreKeptOnceAndOldCopiesArePruned(@TempDir Path dir) throws IOException {
        Path originals = dir.resolve("originals");
        MixedLineEndings.Source source = source("a\r\nb\n", false);

        Path kept = MixedLineEndings.keepOriginal(originals, source);

        assertArrayEquals(source.bytes(), Files.readAllBytes(kept));
        assertTrue(kept.getFileName().toString().startsWith("mixed.txt."), kept.toString());
        assertEquals(kept, MixedLineEndings.keepOriginal(originals, source), "one copy per loaded file");

        Instant old = Instant.now().minusSeconds(3600);
        for (int i = 0; i < MixedLineEndings.MAX_ORIGINALS + 5; i++) {
            Path filler = Files.createTempFile(originals, "old.", ".original");
            Files.setLastModifiedTime(filler, FileTime.from(old.minusSeconds(i)));
        }
        Path newest = MixedLineEndings.keepOriginal(originals, source("x\r\ny\n", false));
        try (var entries = Files.list(originals)) {
            assertEquals(MixedLineEndings.MAX_ORIGINALS, entries.count());
        }
        assertTrue(Files.exists(newest));
        assertTrue(Files.exists(kept), "the newest copies survive");
    }

    // --- what a save reports ---------------------------------------------------------------------------

    private static EditorConfigProperties rules(String charset, Boolean finalNewline) {
        return new EditorConfigProperties(null, null, null, null, charset, null, finalNewline, null);
    }

    private static SaveNotes notes(
            String content, String written, EditorConfigProperties rules, String onDisk, String effective) {
        return SaveNotes.of(Decision.NORMAL, null, "LF", content, written, rules, onDisk, effective, true);
    }

    @Test
    void aReencodingRuleIsReported() {
        SaveNotes bom =
                notes("a\n", "a\n", rules("utf-8", null), EditorConfigCharset.UTF_8_BOM, EditorConfigCharset.UTF_8);
        assertTrue(bom.charsetChanged());
        assertEquals(EditorConfigCharset.UTF_8_BOM, bom.charsetFrom());
        assertEquals(EditorConfigCharset.UTF_8, bom.charsetTo());
        assertEquals("utf-8", bom.charsetRule());

        SaveNotes same =
                notes("a\n", "a\n", rules("utf-8", null), EditorConfigCharset.UTF_8, EditorConfigCharset.UTF_8);
        assertFalse(same.charsetChanged());
        assertNull(same.charsetFrom());
        assertEquals("Saved a.txt", same.appendTo("Saved a.txt"), "nothing changed, nothing added");
    }

    @Test
    void aStandInCharsetOrAUtf8FallbackIsNotAnEditorConfigChange() {
        SaveNotes notes = SaveNotes.of(
                Decision.NORMAL,
                null,
                "LF",
                "a\n",
                "a\n",
                rules("utf-8", null),
                EditorConfigCharset.LATIN1,
                EditorConfigCharset.UTF_8,
                false);
        assertFalse(notes.charsetChanged());
    }

    @Test
    void droppedTrailingLineBreaksAreCounted() {
        assertEquals(
                4, notes("a\n\n\n\n", "a", rules(null, false), "utf-8", "utf-8").newlinesDropped());
        assertEquals(
                1, notes("a\r\n", "a", rules(null, false), "utf-8", "utf-8").newlinesDropped());
        assertEquals(0, notes("a", "a", rules(null, false), "utf-8", "utf-8").newlinesDropped());
        assertEquals(0, notes("a", "a\n", rules(null, true), "utf-8", "utf-8").newlinesDropped());
        assertEquals(
                0, notes("a\n\n", "a\n\n", rules(null, null), "utf-8", "utf-8").newlinesDropped());
        assertEquals(3, SaveNotes.terminators("a\r\nb\rc\n"));
    }
}
