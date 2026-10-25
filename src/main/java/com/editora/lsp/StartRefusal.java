package com.editora.lsp;

/**
 * Why a language server is not running although its command exists, as the coordinator words it: the server
 * refused {@code initialize} (its own message in {@code detail}), or Editora did not start the TypeScript server
 * because there is no TypeScript it may run ({@code detail}: the version of the {@code typescript} package found
 * beside the server, which has no tsserver, or ""), or because only an untrusted folder's own TypeScript would
 * do ({@code detail}: that folder), or because no JDK can run jdtls ({@code detail}: the Java version it needs). Unlike a crash, none of these is retried until something changes.
 */
public record StartRefusal(Kind kind, String detail) {

    public enum Kind {
        /** The server answered {@code initialize} with an error. */
        SERVER_REFUSED,
        /** No TypeScript with a tsserver beside the TypeScript server (or a usable one in a trusted folder). */
        NO_TYPESCRIPT,
        /** The only TypeScript is the folder's own, and the folder is not trusted. */
        UNTRUSTED_TYPESCRIPT,
        /** No JDK new enough to run the server on ({@code detail}: the major version it needs). */
        NO_JAVA_FOR_SERVER
    }

    public StartRefusal {
        detail = detail == null ? "" : detail;
    }
}
