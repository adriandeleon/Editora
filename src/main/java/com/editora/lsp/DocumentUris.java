package com.editora.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The {@code file:} URI a document is known to its language server by, remembered per path.
 *
 * <p>{@link Path#toUri()} is not a string operation: to decide whether the URI ends in a slash the JDK
 * stats the file, and it then percent-encodes the path and builds and re-parses a {@link java.net.URI}.
 * {@code LspManager} needs the URI for every routing question ("is this file managed?", "which session?"),
 * and those are asked on the JavaFX thread a dozen or more times each time typing pauses, and once per
 * capability per open tab when a server changes what it offers. Remembering the answer turns each of those
 * into a map lookup.
 *
 * <p>The string is exactly what {@code toUri().toString()} produced the first time it was asked — nothing
 * here formats a URI. A directory's URI (the trailing-slash form) is never remembered, so the one thing the
 * stat decides cannot go stale for a path that is later a document.
 */
final class DocumentUris {

    /** Far above the number of documents a window has open; the map is emptied rather than grown past it. */
    static final int MAX_ENTRIES = 4096;

    /** A remembered URI with the path spelling it was computed from. */
    private record Entry(String spelling, String uri) {}

    private static final ConcurrentHashMap<Path, Entry> CACHE = new ConcurrentHashMap<>();

    private DocumentUris() {}

    /** {@code file.toUri().toString()}, computed once per path. */
    static String of(Path file) {
        if (com.editora.vfs.Vfs.isRemote(file)) {
            // A remote file's key says which host it is on: its sftp:// URI (Path.toUri() throws for it).
            return com.editora.vfs.Vfs.toStorableString(file);
        }
        String spelling = file.toString();
        Entry cached = CACHE.get(file);
        // Path equality ignores case on Windows while the URI keeps it: only an identical spelling may
        // reuse the string, so two spellings of one file keep the two URIs they always had.
        if (cached != null && cached.spelling().equals(spelling)) {
            return cached.uri();
        }
        String uri = file.toUri().toString();
        if (!uri.endsWith("/")) {
            if (CACHE.size() >= MAX_ENTRIES) {
                CACHE.clear();
            }
            CACHE.put(file, new Entry(spelling, uri));
        }
        return uri;
    }

    /**
     * The file a document key names, or {@code null} when it is not one Editora can open as a path (a
     * {@code jdt:} class file, a malformed URI).
     */
    static @Nullable Path toPath(String key) {
        if (key == null) {
            return null;
        }
        if (com.editora.vfs.Vfs.isRemoteUri(key)) {
            return com.editora.vfs.Vfs.parseStorable(key); // null while that host is not connected
        }
        try {
            return Path.of(URI.create(key));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The key of a folder (a session's root): with a trailing slash, as {@link Path#toUri()} gives one. */
    static String ofDirectory(Path dir) {
        if (com.editora.vfs.Vfs.isRemote(dir)) {
            String key = com.editora.vfs.Vfs.toStorableString(dir);
            return key.endsWith("/") ? key : key + "/";
        }
        return dir.toUri().toString();
    }

    /** Drops {@code file}'s remembered URI (its document was closed). */
    static void forget(Path file) {
        if (file != null) {
            CACHE.remove(file);
        }
    }

    /** Remembered paths — package-private so a test can show the map is bounded. */
    static int size() {
        return CACHE.size();
    }
}
