package com.editora.lsp;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

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

    /**
     * A server on the host of connection {@code authority} ({@code user@host:port}): a key
     * {@code sftp://<authority>/a b/c.py} is {@code file:///a%20b/c.py} to it, and any {@code file:} URI it
     * sends names a file on that host. Other schemes pass through.
     */
    static WireUris remote(@Nullable String authority) {
        String prefix = "sftp://" + authority;
        return new WireUris() {
            @Override
            public String toWire(String key) {
                if (key == null || !key.startsWith(prefix + "/")) {
                    return key;
                }
                try {
                    return new java.net.URI("file", "", key.substring(prefix.length()), null, null).toASCIIString();
                } catch (java.net.URISyntaxException notAPath) {
                    return key;
                }
            }

            @Override
            public String toKey(String wireUri) {
                if (wireUri == null || !wireUri.startsWith("file:")) {
                    return wireUri;
                }
                try {
                    String path = java.net.URI.create(wireUri).getPath();
                    return path == null || !path.startsWith("/") ? wireUri : prefix + path;
                } catch (IllegalArgumentException notAUri) {
                    return wireUri;
                }
            }
        };
    }

    /**
     * A server for a folder of this machine whose tools run elsewhere ({@code target}: a container, WSL): a key
     * is a {@code file:} URI of this machine, and the server knows the same file by the target's name for it
     * ({@link com.editora.process.ExecTarget#toTargetPath}). A file outside the folders the target maps, and a
     * URI of another scheme, pass through unchanged.
     */
    static WireUris mapped(com.editora.process.ExecTarget target) {
        String id = target.id();
        return new WireUris() {
            @Override
            public String toWire(String key) {
                if (key == null || !key.startsWith("file:")) {
                    return key;
                }
                Path local = DocumentUris.toPath(key);
                if (local == null
                        || !id.equals(
                                com.editora.process.ExecTargets.forPath(local).id())) {
                    return key;
                }
                try {
                    String there = target.toTargetPath(local);
                    if (key.endsWith("/") && !there.endsWith("/")) {
                        there += "/"; // a folder stays a folder (DocumentUris.ofDirectory)
                    }
                    return new java.net.URI("file", "", there.startsWith("/") ? there : "/" + there, null, null)
                            .toASCIIString();
                } catch (java.net.URISyntaxException | RuntimeException notAPath) {
                    return key;
                }
            }

            @Override
            public String toKey(String wireUri) {
                if (wireUri == null || !wireUri.startsWith("file:")) {
                    return wireUri;
                }
                try {
                    String there = java.net.URI.create(wireUri).getPath();
                    if (there == null) {
                        return wireUri;
                    }
                    Path local = target.toPath(there);
                    if (local == null
                            || !id.equals(com.editora.process.ExecTargets.forPath(local)
                                    .id())) {
                        return wireUri;
                    }
                    return wireUri.endsWith("/") ? DocumentUris.ofDirectory(local) : DocumentUris.of(local);
                } catch (IllegalArgumentException notAPath) { // also an InvalidPathException
                    return wireUri;
                }
            }
        };
    }

    /**
     * {@code base}, except for the documents in {@code aliases} (key → the {@code file:} URI the server is
     * shown instead, see {@link JavaScriptShadow}): a request about one names its alias, and the alias in
     * anything the server sends — however it spells the URI — reads back as the document. {@code base} is
     * asked each time, since a session settles its own only once it starts.
     */
    static WireUris aliasing(java.util.function.Supplier<WireUris> base, java.util.Map<String, String> aliases) {
        return new WireUris() {
            @Override
            public String toWire(String key) {
                String alias = key == null ? null : aliases.get(key);
                return alias != null ? alias : base.get().toWire(key);
            }

            @Override
            public String toKey(String wireUri) {
                if (!aliases.isEmpty() && wireUri != null && wireUri.startsWith("file:")) {
                    Path named = DocumentUris.toPath(wireUri);
                    for (var alias : aliases.entrySet()) {
                        if (named != null && named.equals(DocumentUris.toPath(alias.getValue()))) {
                            return alias.getKey();
                        }
                    }
                }
                return base.get().toKey(wireUri);
            }
        };
    }

    String toWire(String key);

    String toKey(String wireUri);

    /** The file a server's URI names, or {@code null} when it is not a file Editora can open. */
    default @Nullable Path toPath(String wireUri) {
        return DocumentUris.toPath(toKey(wireUri));
    }
}
