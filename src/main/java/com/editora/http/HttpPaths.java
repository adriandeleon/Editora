package com.editora.http;

import java.nio.file.Path;

/**
 * Containment for the file paths a {@code .http} request may reference — the {@code < ./body.json} external
 * body, a {@code multipart/form-data} file part, and the {@code >>} response redirect. Each is resolved
 * against the request file's own folder and must stay inside it: a {@code .http} is *content*, frequently
 * shipped inside a repository the user merely opened, so an absolute path or a {@code ../} escape would let
 * it read something the user never meant to send (a {@code < /etc/passwd} or {@code < ../../.ssh/id_rsa}
 * body is exfiltration to the request's own host — the request itself is user-initiated, which is exactly
 * what makes the read look legitimate).
 *
 * <p>The write side ({@code >>}) has always been contained; the two read sides were not, which is the
 * asymmetry this closes. Pure, so it is unit-tested.
 */
public final class HttpPaths {

    private HttpPaths() {}

    /**
     * Resolves {@code reference} against {@code baseDir}, or returns {@code null} when it escapes that folder
     * (an absolute path, a {@code ../} climb, or a blank/unusable reference). A null {@code baseDir} — an
     * unsaved buffer, with no folder to contain against — also yields {@code null}, since there is nothing to
     * anchor the reference to.
     *
     * <p>Containment is decided on the lexically normalized paths: the caller reads through the returned
     * path, so a symlink inside the folder resolves as the user's own filesystem dictates, exactly as a
     * relative reference always has.
     */
    public static Path contained(Path baseDir, String reference) {
        if (baseDir == null || reference == null || reference.isBlank()) {
            return null;
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path target;
        try {
            target = base.resolve(reference).normalize();
        } catch (RuntimeException e) {
            return null; // an invalid path string for this filesystem
        }
        return target.startsWith(base) ? target : null;
    }
}
