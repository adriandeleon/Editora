package com.editora.http;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import com.editora.io.PathContainment;

/**
 * Containment for the file paths a {@code .http} request may reference — the {@code < ./body.json} external
 * body, a {@code multipart/form-data} file part, and the {@code >>} response redirect. Each is resolved
 * against the request file's own folder and must stay inside it: a {@code .http} is *content*, frequently
 * shipped inside a repository the user merely opened, so an absolute path or a {@code ../} escape would let
 * it read something the user never meant to send (a {@code < /etc/passwd} or {@code < ../../.ssh/id_rsa}
 * body is exfiltration to the request's own host — the request itself is user-initiated, which is exactly
 * what makes the read look legitimate).
 *
 * <p>The lexical check alone is not containment: a repository can ship {@code payload.json} as a symlink to
 * {@code ~/.ssh/id_rsa}, or {@code out} as a symlink to {@code ~/.bashrc} for a {@code >>! ./out} redirect. So
 * the resolved target (or, when it does not exist yet, its nearest existing parent) is canonicalized with
 * {@code toRealPath()} and containment is re-checked against the canonical base folder. Reads additionally
 * require a regular file; writes refuse a symbolic link as the final component. Unit-tested.
 */
public final class HttpPaths {

    private HttpPaths() {}

    /**
     * Resolves a <b>read</b> reference ({@code < ./body.json}, a multipart file part) against {@code baseDir},
     * or returns {@code null} when it escapes that folder — an absolute path, a {@code ../} climb, a symlink
     * (the file itself or any folder on the way) whose real location is outside the folder, an existing target
     * that is not a regular file, or a blank/unusable reference. A null {@code baseDir} — an unsaved buffer,
     * with no folder to contain against — also yields {@code null}, since there is nothing to anchor the
     * reference to.
     *
     * <p>A target that does not exist is returned as resolved (its existing parent chain is still checked), so
     * the caller's read fails with its ordinary "missing file" handling.
     */
    public static Path contained(Path baseDir, String reference) {
        Path target = lexicallyContained(baseDir, reference);
        if (target == null || !PathContainment.isWithin(baseDir, target)) {
            return null;
        }
        // Follows a (contained) link on purpose: what matters is that the thing read is a regular file.
        return !Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(target) ? target : null;
    }

    /**
     * Resolves a <b>write</b> target ({@code >> ./out.json}) against {@code baseDir}, or returns {@code null}
     * when it escapes that folder (as {@link #contained}), when the final component is a symbolic link (a
     * write must never be redirected through a link, wherever it points), or when it exists and is not a
     * regular file. The target itself usually does not exist yet; its nearest existing parent is what gets
     * canonicalized.
     */
    public static Path containedForWrite(Path baseDir, String reference) {
        Path target = lexicallyContained(baseDir, reference);
        if (target == null || Files.isSymbolicLink(target) || !PathContainment.isWithin(baseDir, target)) {
            return null;
        }
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        return !exists || Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? target : null;
    }

    private static Path normalized(Path baseDir) {
        return baseDir.toAbsolutePath().normalize();
    }

    /** The lexically normalized target, or null when the reference is unusable or climbs out of the base. */
    private static Path lexicallyContained(Path baseDir, String reference) {
        if (baseDir == null || reference == null || reference.isBlank()) {
            return null;
        }
        Path base = normalized(baseDir);
        Path target;
        try {
            target = base.resolve(reference).normalize();
        } catch (RuntimeException e) {
            return null; // an invalid path string for this filesystem
        }
        return target.startsWith(base) ? target : null;
    }
}
