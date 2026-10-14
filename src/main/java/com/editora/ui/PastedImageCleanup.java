package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.editora.io.Trash;
import com.editora.markdown.MarkdownImagePaste;

import static com.editora.i18n.Messages.tr;

/**
 * After a document is saved: the images that were pasted or dropped into it <em>in this session</em> and
 * that it no longer mentions are moved to the system trash. Only ever a file this session's paste or drop
 * created (the buffer keeps that list); never one that another open document, or another document in the
 * same folder, still mentions; never deleted for good — when there is no trash the file stays and the
 * status line says so.
 */
final class PastedImageCleanup {

    /** Sibling documents read to see whether one of them still uses an image; more than this and we stop looking. */
    static final int MAX_SIBLINGS = 200;

    static final long MAX_SIBLING_BYTES = 2_000_000;

    private PastedImageCleanup() {}

    /**
     * Trashes the unused images among {@code created} (the caller's own, mutable list: an image that was
     * trashed or is gone is taken off it) for a document in {@code baseDir} just saved as {@code savedText}.
     *
     * @param usedElsewhere whether some other open document mentions the given file name
     * @return what to add to the "Saved" status message; "" when nothing was unused
     */
    static String afterSave(
            Path document, String savedText, Set<Path> created, Predicate<String> usedElsewhere, Trash.Bin bin) {
        if (created.isEmpty() || document == null) {
            return "";
        }
        Path baseDir = document.toAbsolutePath().getParent();
        List<Path> unused = MarkdownImagePaste.unreferenced(savedText, baseDir, created);
        if (unused.isEmpty()) {
            return "";
        }
        String siblings = siblingText(baseDir, document);
        if (siblings == null) {
            return ""; // the folder could not be read: nothing is known to be unused
        }
        int trashed = 0;
        int kept = 0;
        for (Path file : unused) {
            String name = file.getFileName().toString();
            if (!Files.isRegularFile(file)) {
                created.remove(file); // gone already: nothing of ours to look after
                continue;
            }
            if (usedElsewhere.test(name) || siblings.contains(name)) {
                continue; // moved to another document: still used, still ours to watch
            }
            if (!bin.accepts(file)) {
                kept++;
                continue;
            }
            try {
                bin.trash(file);
                created.remove(file);
                trashed++;
            } catch (IOException e) {
                kept++;
            }
        }
        String note = "";
        if (trashed > 0) {
            note += " — " + tr("status.markdown.imagesTrashed", trashed);
        }
        if (kept > 0) {
            note += " — " + tr("status.markdown.imagesNotTrashed", kept);
        }
        return note;
    }

    /** The text of the other Markdown / Typst documents in {@code dir} (bounded), to look file names up in. */
    private static String siblingText(Path dir, Path self) {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.limit(MAX_SIBLINGS * 5L)::iterator) {
                String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                boolean document =
                        n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".typ") || n.endsWith(".mdx");
                if (document && !f.equals(self) && Files.isRegularFile(f) && Files.size(f) <= MAX_SIBLING_BYTES) {
                    all.append(Files.readString(f)).append('\n');
                    if (all.length() > MAX_SIBLING_BYTES * 4) {
                        break;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return all.toString();
    }
}
