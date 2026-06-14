package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One recorded version of a file in the Local File History: the absolute file {@code path}, the
 * {@code timestamp} it was captured (epoch millis), the uncompressed content {@code sizeBytes}, the
 * {@code sha256} hash of the content (which content-addresses the gzip'd body blob and powers
 * deduplication), and the {@code reason} it was captured ({@code "SAVE"} / {@code "AUTOSAVE"} /
 * {@code "EXTERNAL"}). The body itself lives outside this record, in {@code history/blobs/} keyed by
 * {@code sha256}, so the {@code history/index.json} metadata stays small.
 *
 * <p>A Jackson-serialized record; the {@code com.editora.config} package is already opened to
 * jackson.databind in {@code module-info.java} (see {@link Breakpoint}). Timestamps are {@code long}
 * epoch millis so the default mapper needs no jsr310 module.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoryRevision(String path, long timestamp, long sizeBytes, String sha256, String reason) {

    /** Capture reasons (stored verbatim as strings so an unknown future reason never breaks parsing). */
    public static final String REASON_SAVE = "SAVE";

    public static final String REASON_AUTOSAVE = "AUTOSAVE";

    public static final String REASON_EXTERNAL = "EXTERNAL";

    public HistoryRevision {
        path = path == null ? "" : path;
        sha256 = sha256 == null ? "" : sha256;
        reason = reason == null ? REASON_SAVE : reason;
    }
}
