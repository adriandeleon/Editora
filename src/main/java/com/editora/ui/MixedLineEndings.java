package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * What a save may do to a file that uses more than one kind of line terminator.
 *
 * <p>The editor's document holds bare {@code \n} and one line-ending label per buffer, so a save writes one
 * terminator on every line. For a file that mixes them — a CRLF file with a stray LF, an LF file holding a
 * lone {@code \r}, a script followed by a binary payload — that rewrites bytes the user never touched, and it
 * used to happen on a plain Ctrl/Cmd-S of an unedited buffer, with nothing left to restore from. So the bytes
 * such a file was loaded from are remembered here, and each save is decided against them:
 *
 * <ul>
 *   <li>the text is still the loaded text: the loaded bytes are written back as they are;
 *   <li>the text was edited: every line gets the buffer's line ending, as before — but the status says so,
 *       the status bar shows the file as mixed until then, and the loaded bytes are first copied to
 *       {@code <config>/line-ending-originals};
 *   <li>the text was edited <em>and</em> holds NUL characters (binary data): rewriting its terminators
 *       corrupts it, so that needs the user's explicit agreement.
 * </ul>
 */
final class MixedLineEndings {

    /** The folder, under the config directory, that keeps the bytes a normalising save replaced. */
    static final String ORIGINALS_FOLDER = "line-ending-originals";

    /** How many of those copies are kept; the oldest go first. */
    static final int MAX_ORIGINALS = 30;

    /**
     * A mixed file as it was loaded.
     *
     * @param file where the bytes are on disk (a byte-preserving Save As moves this)
     * @param bytes the file's bytes
     * @param text the document text they decoded to, in the editor's {@code \n} form
     * @param lineEnding the file's dominant line ending, which an edited save writes throughout
     * @param binary the text holds NUL characters
     * @param kept the copy of {@link #bytes} made by a normalising save, once there is one
     */
    record Source(Path file, byte[] bytes, String text, String lineEnding, boolean binary, AtomicReference<Path> kept) {

        Source at(Path newFile) {
            return new Source(newFile, bytes, text, lineEnding, binary, kept);
        }
    }

    /** What a save of a buffer does about its file's terminators. */
    enum Decision {
        /** Not a mixed file: the ordinary save. */
        NORMAL,
        /** Unedited: write the loaded bytes back untouched. */
        KEEP_BYTES,
        /** Edited: one terminator on every line; the original bytes are kept aside and the status says so. */
        NORMALISE,
        /** Edited, and binary: as {@link #NORMALISE}, but only once the user has agreed to it. */
        NORMALISE_WITH_CONSENT
    }

    /** Buffers whose file is mixed on disk. FX-thread only. */
    private static final Map<EditorBuffer, Source> SOURCES = new WeakHashMap<>();

    private MixedLineEndings() {}

    /** Records (or clears) what {@code buffer} was just loaded from. Call it after the document is installed. */
    static void loaded(EditorBuffer buffer, Path file, byte[] bytes, String lineEnding, boolean mixed) {
        if (!mixed || bytes == null) {
            SOURCES.remove(buffer);
            return;
        }
        String text = buffer.getContent();
        SOURCES.put(
                buffer, new Source(file, bytes, text, lineEnding, text.indexOf('\0') >= 0, new AtomicReference<>()));
    }

    static Source of(EditorBuffer buffer) {
        return SOURCES.get(buffer);
    }

    /** The file on disk is uniform now (a normalising save completed), or the buffer left it. */
    static void forget(EditorBuffer buffer) {
        SOURCES.remove(buffer);
    }

    /** A byte-preserving save put the same bytes at {@code file}: that is where they are now. */
    static void movedTo(EditorBuffer buffer, Path file) {
        SOURCES.computeIfPresent(buffer, (ignored, source) -> source.at(file));
    }

    /** What the status bar's line-ending segment shows: the label, marked as mixed while the file is. */
    static String label(EditorBuffer buffer) {
        String label = buffer.getLineEnding();
        return SOURCES.containsKey(buffer) ? tr("statusbar.endings.mixed", label) : label;
    }

    /**
     * Decides a save. Pure.
     *
     * @param source what the buffer was loaded from, or null when its file is not mixed
     * @param content the buffer's text now
     * @param lineEnding the line ending the save would write
     * @param lineEndingForced an {@code end_of_line} rule fixes that ending (so it says nothing about the user)
     * @param target the file being written
     */
    static Decision decide(Source source, String content, String lineEnding, boolean lineEndingForced, Path target) {
        if (source == null) {
            return Decision.NORMAL;
        }
        // Choosing another line ending with Convert Line Endings is the user asking for exactly this rewrite.
        boolean converted = !lineEndingForced && !source.lineEnding().equals(lineEnding);
        if (!converted && source.text().equals(content)) {
            return Decision.KEEP_BYTES;
        }
        boolean originalStaysOnDisk = target != null && !PathKeys.sameNormalized(target, source.file());
        return source.binary() && !converted && !originalStaysOnDisk
                ? Decision.NORMALISE_WITH_CONSENT
                : Decision.NORMALISE;
    }

    /**
     * Copies {@code source}'s bytes into {@code dir} before a save normalises the file, so local history
     * (which holds text, already normalised) is not the only trace of them. Runs on the save worker.
     *
     * @return the copy; the same one when this source was already kept
     */
    static Path keepOriginal(Path dir, Source source) throws IOException {
        Path already = source.kept().get();
        if (already != null && Files.isRegularFile(already)) {
            return already;
        }
        Files.createDirectories(dir);
        String name = String.valueOf(source.file().getFileName());
        Path copy = Files.createTempFile(dir, name.substring(0, Math.min(name.length(), 60)) + ".", ".original");
        Files.write(copy, source.bytes());
        source.kept().set(copy);
        prune(dir, copy);
        return copy;
    }

    private static void prune(Path dir, Path newest) {
        try (var entries = Files.list(dir)) {
            List<Path> oldestFirst = entries.filter(
                            entry -> entry.getFileName().toString().endsWith(".original") && !entry.equals(newest))
                    .sorted(Comparator.comparingLong(FileWorkflowCoordinator::lastModifiedMillis))
                    .toList();
            for (int i = 0; i < oldestFirst.size() - (MAX_ORIGINALS - 1); i++) {
                Files.deleteIfExists(oldestFirst.get(i));
            }
        } catch (IOException | RuntimeException kept) {
            // Too many copies is clutter; failing the save over it would not be.
        }
    }
}
