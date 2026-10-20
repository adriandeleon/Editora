package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.editora.io.Trash;
import com.editora.markdown.MarkdownImagePaste;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * After a document is saved: the images that were pasted or dropped into it <em>in this session</em> and
 * that nothing mentions any more are moved to the system trash. Only ever a file this session's paste or
 * drop created (the buffer keeps that list); never one that another open document, or another document in
 * the same folder, still mentions; never deleted for good — when there is no trash the file stays and the
 * status line says so.
 *
 * <p>The steps are separate because they belong on different threads. {@link #candidates} is cheap string
 * work for the FX thread (the saved text, the other open buffers). {@link #unusedOnDisk} reads the sibling
 * documents and {@link #trash} moves files: both are file I/O and run on a background executor, and both
 * stop when {@code cancelled} says the window is closing. The caller re-checks the candidates against the
 * live buffer between the two, since the user may have undone in the meantime.
 */
final class PastedImageCleanup {

    /** Sibling documents read to see whether one of them still uses an image; more than this and we stop looking. */
    static final int MAX_SIBLINGS = 200;

    static final long MAX_SIBLING_BYTES = 2_000_000;

    private PastedImageCleanup() {}

    /** What {@link #trash} did. */
    record Outcome(List<Path> trashed, List<Path> gone, int kept) {}

    /**
     * The files among {@code created} that {@code text} — the document — does not mention, and no other open
     * document does either ({@code usedElsewhere}). No file I/O: for the FX thread.
     */
    static List<Path> candidates(
            @Nullable Path document, String text, Collection<Path> created, Predicate<Path> usedElsewhere) {
        Path dir = created.isEmpty() || document == null
                ? null
                : document.toAbsolutePath().getParent();
        if (dir == null) { // a saved file's absolute path always has a folder
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        for (Path file : MarkdownImagePaste.unreferenced(text, dir, created)) {
            if (!usedElsewhere.test(file)) {
                out.add(file);
            }
        }
        return out;
    }

    /**
     * Whether {@code otherText} — another open document, saved as {@code otherDocument} (null: never saved) —
     * mentions {@code file}: by the path from that document's own folder to it (a picture of the same name
     * in some other folder is a different picture). An unsaved document has no folder to measure from, so
     * there the bare file name counts.
     */
    static boolean mentions(@Nullable Path otherDocument, String otherText, Path file) {
        if (otherDocument == null || otherDocument.toAbsolutePath().getParent() == null) {
            return otherText.contains(file.getFileName().toString());
        }
        Path dir = otherDocument.toAbsolutePath().getParent();
        String rel = MarkdownImagePaste.relativePath(dir, file);
        return otherText.contains(rel)
                || otherText.contains(rel.replace(" ", "%20"))
                || otherText.contains(rel.replace('/', '\\'));
    }

    /**
     * The {@code candidates} that no other Markdown / Typst document in the document's folder mentions.
     * Reads those documents (bounded): background thread. Empty when the folder cannot be read — then nothing
     * is known to be unused — and when {@code cancelled}.
     */
    static List<Path> unusedOnDisk(@Nullable Path document, List<Path> candidates, BooleanSupplier cancelled) {
        if (candidates.isEmpty() || document == null) { // no document: candidates() found none
            return List.of();
        }
        String siblings = siblingText(document.toAbsolutePath().getParent(), document, cancelled);
        if (siblings == null) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        for (Path file : candidates) {
            if (!siblings.contains(file.getFileName().toString())) {
                out.add(file);
            }
        }
        return out;
    }

    /**
     * Moves {@code files} to {@code bin}: background thread. A file that is no longer there is reported as
     * gone; one the bin does not take, or fails on, is kept in place and counted.
     */
    static Outcome trash(List<Path> files, Trash.Bin bin, BooleanSupplier cancelled) {
        List<Path> trashed = new ArrayList<>();
        List<Path> gone = new ArrayList<>();
        int kept = 0;
        for (Path file : files) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            if (!Files.isRegularFile(file)) {
                gone.add(file);
            } else if (!bin.accepts(file)) {
                kept++;
            } else {
                try {
                    bin.trash(file);
                    trashed.add(file);
                } catch (IOException e) {
                    kept++;
                }
            }
        }
        return new Outcome(trashed, gone, kept);
    }

    /** The status-line note for an outcome; "" when there is nothing to say. */
    static String note(Outcome outcome) {
        String trashed = outcome.trashed().isEmpty()
                ? ""
                : tr("status.markdown.imagesTrashed", outcome.trashed().size());
        String kept = outcome.kept() == 0 ? "" : tr("status.markdown.imagesNotTrashed", outcome.kept());
        return trashed.isEmpty() || kept.isEmpty() ? trashed + kept : trashed + " — " + kept;
    }

    /** The text of the other Markdown / Typst documents in {@code dir} (bounded); null when it cannot be read. */
    private static @Nullable String siblingText(@Nullable Path dir, Path self, BooleanSupplier cancelled) {
        StringBuilder all = new StringBuilder();
        int read = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                boolean document =
                        n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".typ") || n.endsWith(".mdx");
                if (!document || f.equals(self) || !Files.isRegularFile(f)) {
                    continue;
                }
                if (++read > MAX_SIBLINGS || Files.size(f) > MAX_SIBLING_BYTES) {
                    return null; // too much to check: nothing is known to be unused
                }
                all.append(Files.readString(f)).append('\n');
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return all.toString();
    }
}
