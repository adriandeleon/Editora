package com.editora.review;

import java.nio.file.Path;

import com.editora.config.PathKeys;

/** How a repository and a file in it are named in {@code reviews.json}. Pure. */
public final class ReviewKeys {

    private ReviewKeys() {}

    /** The key of the repository whose root is {@code root}: its canonical path, links resolved. */
    public static String repoKey(Path root) {
        return PathKeys.canonicalKey(root);
    }

    /**
     * {@code file}'s path relative to the repository {@code root}, with forward slashes, or {@code null} when it is
     * not inside it. Both are made canonical first, so a link into the repository names the file it reaches.
     */
    public static @org.jspecify.annotations.Nullable String relativePath(Path root, Path file) {
        Path r = PathKeys.canonical(root);
        Path f = PathKeys.canonical(file);
        if (!f.startsWith(r) || f.equals(r)) {
            return null;
        }
        return r.relativize(f).toString().replace('\\', '/');
    }

    /** The file a repository-relative {@code path} names under {@code root}. */
    public static Path resolve(Path root, String path) {
        return root.resolve(
                path.replace('/', root.getFileSystem().getSeparator().charAt(0)));
    }
}
