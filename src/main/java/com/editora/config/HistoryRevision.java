package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One recorded version of a file in the Local File History: the absolute file {@code path}, the
 * {@code timestamp} it was captured (epoch millis), the uncompressed content {@code sizeBytes}, the
 * {@code sha256} hash of the content (which content-addresses the gzip'd body blob and powers
 * deduplication), the {@code reason} it was captured ({@code "SAVE"} / {@code "AUTOSAVE"} /
 * {@code "EXTERNAL"} / {@code "LABEL"}), and an optional user {@code label} (the manual "Put Label" name;
 * {@code ""} for automatic revisions). The body itself lives outside this record, in {@code history/blobs/}
 * keyed by {@code sha256}, so the {@code history/index.json} metadata stays small.
 *
 * <p>A revision captured just before its file was deleted ({@link #REASON_DELETE}) also records how the file
 * was written: its {@code charset} (an EditorConfig charset name as used by
 * {@code EditorConfigCharset}), whether it began with a byte-order mark ({@code bom}) and its dominant
 * {@code lineEnding} ({@code "LF"}, {@code "CRLF"} or {@code "CR"}). The body is text, and once the file
 * is gone nothing else says which bytes it was, so without these a restore could only write UTF-8. All
 * three are empty/false on every other revision and on rows written before index schema 3; they are left
 * out of the JSON then.
 *
 * <p>A Jackson-serialized record; the {@code com.editora.config} package is already opened to
 * jackson.databind in {@code module-info.java} (see {@link Breakpoint}). Timestamps are {@code long}
 * epoch millis so the default mapper needs no jsr310 module.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoryRevision(
        String path,
        long timestamp,
        long sizeBytes,
        String sha256,
        String reason,
        String label,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String charset,
        @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean bom,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String lineEnding) {

    /** Capture reasons (stored verbatim as strings so an unknown future reason never breaks parsing). */
    public static final String REASON_SAVE = "SAVE";

    public static final String REASON_AUTOSAVE = "AUTOSAVE";

    public static final String REASON_EXTERNAL = "EXTERNAL";

    /** A manual, user-named snapshot ("Put Label"); carries a non-blank {@link #label()}. */
    public static final String REASON_LABEL = "LABEL";

    /** Captured just before the file was deleted (so it can be recovered from folder history). */
    public static final String REASON_DELETE = "DELETE";

    public HistoryRevision {
        path = path == null ? "" : path;
        sha256 = sha256 == null ? "" : sha256;
        reason = reason == null ? REASON_SAVE : reason;
        label = label == null ? "" : label; // absent in pre-schema-2 index.json rows
        charset = charset == null ? "" : charset; // absent before schema 3 and on non-delete revisions
        lineEnding = lineEnding == null ? "" : lineEnding;
    }

    /** A revision without recorded encoding (every reason but {@link #REASON_DELETE}). */
    public HistoryRevision(String path, long timestamp, long sizeBytes, String sha256, String reason, String label) {
        this(path, timestamp, sizeBytes, sha256, reason, label, "", false, "");
    }

    /** Back-compat factory for automatic (unlabeled) revisions. */
    public HistoryRevision(String path, long timestamp, long sizeBytes, String sha256, String reason) {
        this(path, timestamp, sizeBytes, sha256, reason, "");
    }

    /** Whether this revision says how its file was encoded (see the class comment). */
    @JsonIgnore
    public boolean hasEncoding() {
        return !charset.isEmpty();
    }

    /** This revision as a revision of the file at {@code newPath}; everything else is kept. */
    public HistoryRevision withPath(String newPath) {
        return new HistoryRevision(newPath, timestamp, sizeBytes, sha256, reason, label, charset, bom, lineEnding);
    }

    /** This revision under another user label; everything else is kept. */
    public HistoryRevision withLabel(String newLabel) {
        return new HistoryRevision(path, timestamp, sizeBytes, sha256, reason, newLabel, charset, bom, lineEnding);
    }

    /** This revision with the encoding of the file it was captured from. */
    public HistoryRevision withEncoding(String newCharset, boolean newBom, String newLineEnding) {
        return new HistoryRevision(
                path, timestamp, sizeBytes, sha256, reason, label, newCharset, newBom, newLineEnding);
    }
}
