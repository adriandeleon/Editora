package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.editora.agent.AcpFsGuard;
import com.editora.editor.LineEndings;

/**
 * What an agent's {@code fs/write_text_file} does to a file that has no open buffer: the bytes that go to disk
 * and the text that is kept in Local History first.
 *
 * <p>An open buffer has a recovery path of its own — the edit is undoable and nothing reaches the disk until
 * the user saves. A file with no tab has neither, and the write used to be {@code content.getBytes(UTF_8)}
 * over whatever was there: the previous bytes were gone, and a Latin-1, UTF-16, BOM-marked or CRLF file came
 * back as BOM-less UTF-8 with the agent's line endings. The replacement is now written the way the editor
 * itself would save that file, and the text it replaces is handed to the caller to record beforehand.
 *
 * <p>Pure apart from reading the target; unit-tested against a temp directory.
 */
final class AgentFileWrites {

    /**
     * A replacement ready to be written.
     *
     * @param existing the bytes the write is conditional on, or {@code null} when the file does not exist
     * @param previousText {@code existing} as the editor would show it ({@code \n} line ends) — the Local
     *     History pre-image — or {@code null} when the file does not exist
     * @param currentText {@code existing} decoded with its own line terminators — what a read would have
     *     served the agent — or {@code null} when the file does not exist
     * @param replacement the bytes to write
     * @param replacementText {@code replacement} as a later read will serve it
     */
    record Plan(byte[] existing, String previousText, String currentText, byte[] replacement, String replacementText) {}

    private AgentFileWrites() {}

    /** The text of {@code bytes} as the editor would read the file: BOM, {@code .editorconfig}, UTF-8, stand-in. */
    static String decode(byte[] bytes, String editorConfigCharset) {
        return HistoryCoordinator.decodeCaptured(bytes, editorConfigCharset);
    }

    /**
     * Prepares replacing {@code file} with {@code content}, or explains why the editor will not do it.
     *
     * @param editorConfigCharset the {@code .editorconfig} charset for the file, or {@code null}
     */
    static Plan plan(Path file, String content, String editorConfigCharset) throws IOException {
        String body = content == null ? "" : content;
        byte[] existing = null;
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Refused: not a regular file: " + file);
            }
            if (!Files.isWritable(file)) {
                // The replacement is a rename over the target, which its own permission bits do not stop.
                throw new IOException("Refused: " + file + " is read-only");
            }
            if (Files.size(file) > AcpFsGuard.MAX_READ_BYTES) {
                throw new IOException("Refused: " + file + " is too large to replace through the editor ("
                        + (AcpFsGuard.MAX_READ_BYTES / (1024 * 1024)) + " MB limit)");
            }
            existing = Files.readAllBytes(file);
            if (com.editora.diff.BinaryDiff.isProbablyBinary(existing)) {
                throw new IOException(
                        "Refused: " + file + " is not a text file; replacing it with text would" + " destroy it");
            }
        }
        byte[] replacement = HistoryCoordinator.restoredBytes(body, existing, editorConfigCharset);
        String current = existing == null ? null : decode(existing, editorConfigCharset);
        return new Plan(
                existing,
                current == null ? null : LineEndings.toLf(current),
                current,
                replacement,
                decode(replacement, editorConfigCharset));
    }
}
