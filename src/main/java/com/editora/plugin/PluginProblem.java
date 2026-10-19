package com.editora.plugin;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;

/**
 * Why a registry fetch, a download or an install failed, as a kind and its values, so the UI can say it in the
 * user's language ({@code ui.PluginProblems}). {@code detail} is the underlying (English) message, for a log or
 * a dialog's details; {@link #english()} is the whole sentence in English for logs and older callers.
 */
public record PluginProblem(Kind kind, List<String> args, String detail) {

    public enum Kind {
        /** The registry or download address is not https. */
        NOT_HTTPS,
        /** The registry entry gives no SHA-256 for its download. */
        NO_CHECKSUM,
        /** The server answered with an error status; args: the status code. */
        HTTP_STATUS,
        /** The host could not be reached (offline, unknown host, refused); args: the host. */
        UNREACHABLE,
        /** The server took too long; args: the host. */
        TIMED_OUT,
        /** The download does not match the registry's checksum (damaged, or tampered with). */
        CHECKSUM_MISMATCH,
        /** The archive is over the size cap; args: the cap in MB. */
        TOO_LARGE,
        /** The archive is damaged or contains unsafe entries (paths outside it, too many files). */
        ARCHIVE_INVALID,
        /** The archive has no {@code plugin.json}. */
        NO_MANIFEST,
        /** Its {@code plugin.json} is not valid; args: line, column (blank when unknown). */
        MANIFEST_INVALID,
        /** Its id is not a safe folder name; args: the id. */
        INVALID_ID,
        /** Its id names one of Editora's own tool folders; args: the id. */
        RESERVED_ID,
        /** A plugin whose feature is built into Editora now ({@link RetiredPlugins}); args: the id. */
        RETIRED,
        /** A folder with that name that is not a plugin is in the way; args: the id. */
        IN_THE_WAY,
        /** The installed copy is a link to a folder elsewhere (a plugin being developed); args: the id. */
        SYMLINKED,
        /** The archive's id is not the registry entry's; args: the entry's id, the archive's id. */
        ID_MISMATCH,
        /** The archive's version is not the registry entry's; args: the entry's version, the archive's. */
        VERSION_MISMATCH,
        /** The registry index is not valid JSON. */
        INDEX_INVALID,
        /** The registry index uses a newer format than this Editora reads; args: its schema version. */
        INDEX_TOO_NEW,
        /** Anything else (a disk error, …); the detail says what. */
        FAILED
    }

    public PluginProblem {
        args = args == null ? List.of() : List.copyOf(args);
        detail = detail == null ? "" : detail;
    }

    public static PluginProblem of(Kind kind, String... args) {
        return new PluginProblem(kind, List.of(args), "");
    }

    /** The English sentence, for logs. */
    public String english() {
        String arg = args.isEmpty() ? "" : args.getFirst();
        return switch (kind) {
            case NOT_HTTPS -> "address must be https";
            case NO_CHECKSUM -> "registry entry has no sha256";
            case HTTP_STATUS -> "HTTP " + arg;
            case UNREACHABLE -> "can't reach " + arg;
            case TIMED_OUT -> arg + " took too long to answer";
            case CHECKSUM_MISMATCH -> "checksum mismatch";
            case TOO_LARGE -> "archive too large (over " + arg + " MB)";
            case ARCHIVE_INVALID -> "archive is damaged or unsafe: " + detail;
            case NO_MANIFEST -> "no plugin.json in archive";
            case MANIFEST_INVALID -> "invalid plugin.json: " + detail;
            case INVALID_ID -> "invalid plugin id \"" + arg + "\"";
            case RESERVED_ID -> "plugin id \"" + arg + "\" is reserved";
            case RETIRED -> "plugin \"" + arg + "\" is built into Editora now";
            case IN_THE_WAY -> "a folder named \"" + arg + "\" that is not a plugin is in the way";
            case SYMLINKED -> "the installed \"" + arg + "\" is a link to another folder";
            case ID_MISMATCH -> "the archive is plugin \"" + args.get(1) + "\", not \"" + arg + "\"";
            case VERSION_MISMATCH -> "the archive is version " + args.get(1) + ", not " + arg;
            case INDEX_INVALID -> "registry index is not valid: " + detail;
            case INDEX_TOO_NEW -> "registry index format " + arg + " is newer than this Editora reads";
            case FAILED -> detail;
        };
    }

    /** What a failed network call means for the user: unreachable, too slow, or something else. */
    static PluginProblem network(String url, Throwable e) {
        String host = hostOf(url);
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException
                    || t instanceof HttpTimeoutException
                    || t instanceof java.net.SocketTimeoutException) {
                return new PluginProblem(Kind.TIMED_OUT, List.of(host), String.valueOf(e));
            }
            if (t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof java.nio.channels.UnresolvedAddressException
                    || t instanceof java.net.NoRouteToHostException) {
                return new PluginProblem(Kind.UNREACHABLE, List.of(host), String.valueOf(e));
            }
        }
        if (e instanceof IOException && e.getMessage() != null && e.getMessage().startsWith(DEADLINE)) {
            return new PluginProblem(Kind.TIMED_OUT, List.of(host), e.getMessage());
        }
        return new PluginProblem(
                Kind.FAILED,
                List.of(),
                e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }

    /** The message of the IOException a capped read throws at its deadline. */
    static final String DEADLINE = "response not complete within ";

    public static String hostOf(String url) {
        try {
            String h = URI.create(url == null ? "" : url.strip()).getHost();
            return h == null ? String.valueOf(url) : h;
        } catch (IllegalArgumentException e) {
            return String.valueOf(url);
        }
    }
}
