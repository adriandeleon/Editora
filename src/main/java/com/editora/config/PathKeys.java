package com.editora.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.editora.vfs.Vfs;

/**
 * Pure, unit-tested path-identity + store-keying helpers extracted from {@code MainController}. These decide
 * when two paths refer to the same file and produce the string keys the per-project stores (notes, history)
 * are indexed by. The logic is bug-prone — a past defect compared a server-reported <em>canonical</em> path
 * ({@code /private/tmp/…}) against a buffer's as-opened path ({@code /tmp/…}) with plain {@code normalize()}
 * and silently dropped diagnostics — so it's worth isolating and testing.
 *
 * <p>Touches the filesystem ({@code toRealPath}) but is otherwise dependency-light; verify with a temp dir.
 */
public final class PathKeys {

    private PathKeys() {}

    /**
     * Resolves a user-typed Save-As target (from the keyboard prompt) to an absolute path, or {@code null}
     * when it's blank or not a valid path. A leading {@code ~} (alone or {@code ~/…}) expands to
     * {@code userHome}; a relative path resolves against {@code baseDir} (the current file's folder, else the
     * project root / home); the result is normalized. Pure so it's unit-tested without the toolkit.
     */
    public static Path resolveUserInput(String input, Path baseDir, String userHome) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) {
            s = userHome + s.substring(1);
        }
        try {
            Path p = Path.of(s);
            if (!p.isAbsolute()) {
                p = (baseDir == null ? Path.of("").toAbsolutePath() : baseDir).resolve(p);
            }
            return p.normalize();
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /**
     * A path for cross-source identity comparison: the real (symlink-resolved) path when it exists, else the
     * absolute-normalized form. Matching by {@code normalize()} alone misses a symlinked path that a tool
     * reports under its real URI.
     */
    public static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (java.io.IOException | RuntimeException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /**
     * A provider-safe string identity key: the {@code sftp://} URI for a remote path, the canonical string
     * for a local one. Avoids {@code Path.equals} across filesystems (a MINA SFTP path throws
     * {@link java.nio.file.ProviderMismatchException} when compared to a local path) and a network
     * {@code toRealPath()} for remote files.
     */
    public static String key(Path p) {
        return Vfs.isRemote(p) ? Vfs.toStorableString(p) : canonical(p).toString();
    }

    /** The absolute-normalized string (the Local File History index key — never symlink-resolved). */
    public static String normalizedKey(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    /** The canonical string key for a buffer path, or {@code ""} for a null path (the notes-store key). */
    public static String canonicalKey(Path p) {
        return p == null ? "" : canonical(p).toString();
    }

    /** Whether two paths are the same file by absolute-normalized form, with a defensive equality fallback. */
    public static boolean sameNormalized(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return a.equals(b);
        }
    }

    /**
     * The bucket key holding the notes that belong to {@code id}'s file, when it is keyed under a different
     * path — i.e. the file was renamed or moved outside Editora. Returns {@code null} for a null {@code id}
     * or no match. The caller re-keys the notes onto the new path, so a wrong answer here <b>moves a note
     * off the file it was written on</b>.
     *
     * <p>A canonical-path match is identity. A <b>content-hash match is not</b>: two files can hold the same
     * bytes without being the same file — a {@code cp config.yaml config.backup.yaml}, a duplicated
     * {@code LICENSE}, the boilerplate {@code index.ts} in each package of a monorepo. Accepting it meant
     * merely *opening* such a file moved the other file's notes onto it and deleted them from the original.
     * So a hash match is only trusted when the candidate's own file is <b>gone</b> from disk, which is what
     * a rename actually looks like; {@code stillExists} decides that (injected so this stays pure).
     */
    public static String findKeyByIdentity(
            Map<String, List<PersonalNote>> map, FileIdentity id, Predicate<String> stillExists) {
        if (id == null) {
            return null;
        }
        String hashCandidate = null;
        for (var entry : map.entrySet()) {
            for (PersonalNote n : entry.getValue()) {
                FileIdentity.Match m = FileIdentity.match(n.file(), id);
                if (m == FileIdentity.Match.CANONICAL_PATH) {
                    return entry.getKey(); // the same file — no ambiguity
                }
                if (m == FileIdentity.Match.CONTENT_HASH
                        && hashCandidate == null
                        && !stillExists.test(n.file().canonicalPath())
                        && !stillExists.test(n.file().path())) {
                    hashCandidate = entry.getKey(); // same bytes AND the original is gone ⇒ a rename
                }
            }
        }
        return hashCandidate;
    }
}
