package com.editora.markdown;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Pure helpers for inserting a pasted/dropped image into a Markdown buffer: pick a unique file name
 * within the sibling {@code assets/} directory, compute the forward-slashed relative link, and build
 * the {@code ![](…)} snippet. The actual disk write + clipboard access live in the controller; these
 * bits are stateless and unit-tested.
 */
public final class MarkdownImagePaste {

    /** The sibling directory (next to the Markdown file) that pasted images are written into. */
    public static final String ASSETS_DIR = "assets";

    private MarkdownImagePaste() {}

    /** A non-colliding file name {@code base.ext} / {@code base-N.ext}; {@code exists} tests the target dir. */
    public static String uniqueFileName(Predicate<String> exists, String base, String ext) {
        String name = base + "." + ext;
        if (!exists.test(name)) {
            return name;
        }
        for (int i = 1; i < 10_000; i++) {
            String candidate = base + "-" + i + "." + ext;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        return base + "-" + System.identityHashCode(exists) + "." + ext; // pathological fallback
    }

    /** The forward-slashed path of {@code target} relative to {@code baseDir} (for the Markdown link). */
    public static String relativePath(Path baseDir, Path target) {
        Path rel = baseDir.toAbsolutePath()
                .normalize()
                .relativize(target.toAbsolutePath().normalize());
        return rel.toString().replace('\\', '/');
    }

    /** The Markdown image snippet {@code ![alt](relPath)} (alt may be empty). */
    public static String snippet(String relPath, String alt) {
        return "![" + (alt == null ? "" : alt) + "](" + relPath + ")";
    }
}
