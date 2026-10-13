package com.editora.lsp;

import java.nio.file.Path;

/**
 * Converts between the URI a document is <em>keyed</em> by inside {@link LspManager} and the URI its language
 * server knows it by. For a server running on this machine the two are the same {@code file:} string. They
 * part when the server runs somewhere else: the key has to say which machine the file is on, while the
 * server only ever sees a {@code file:} URI of its own filesystem.
 *
 * <p>Each session owns one. A URI built from a {@link Path} is a key and goes through {@link #toWire} on its
 * way into a request; a URI read out of anything a server sent is in wire form and goes through
 * {@link #toKey} or {@link #toPath} before it is compared with a key or opened. Objects handed back to the
 * server unchanged (a hierarchy item, a code action) stay in wire form throughout. A URI of another scheme
 * ({@code jdt:}, {@code untitled:}) passes through both ways.
 */
interface WireUris {

    /** A server on this machine: keys and wire URIs are the same strings. */
    WireUris LOCAL = new WireUris() {
        @Override
        public String toWire(String key) {
            return key;
        }

        @Override
        public String toKey(String wireUri) {
            return wireUri;
        }
    };

    String toWire(String key);

    String toKey(String wireUri);

    /** The file a server's URI names, or {@code null} when it is not a file Editora can open. */
    default Path toPath(String wireUri) {
        return DocumentUris.toPath(toKey(wireUri));
    }
}
