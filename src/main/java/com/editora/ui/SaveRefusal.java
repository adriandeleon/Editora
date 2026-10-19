package com.editora.ui;

/**
 * Why a buffer must not be written to its file. Each of these states means the buffer's text is <em>not the
 * document</em>, so saving it would replace the file with less than it holds. Pure, so the precedence is
 * unit-tested rather than inferred from the save path.
 */
enum SaveRefusal {
    /** The buffer may be saved. */
    NONE(null),
    /** The tab was closed (or its window disposed); there is nobody left to tell. */
    DISPOSED(null),
    /** A loading shell: the tab exists but its text has not arrived, so it would write an empty file. */
    LOADING("status.loadingNoSave"),
    /** Only part of a very large file was read. */
    TRUNCATED("status.truncatedNoSave"),
    /** Log follow mode dropped the oldest lines to bound memory. */
    LOG_TRIMMED("status.log.trimmedNoSave"),
    /** An encrypted file that is not decrypted yet: the buffer is empty, the file is not. */
    LOCKED("status.encryption.lockedNoSave");

    private final String messageKey;

    SaveRefusal(String messageKey) {
        this.messageKey = messageKey;
    }

    /** The status-bar message for this refusal ({@code {0}} = the tab title), or null when there is none. */
    String messageKey() {
        return messageKey;
    }

    static SaveRefusal of(boolean disposed, boolean loading, boolean truncated, boolean logTrimmed) {
        return of(disposed, loading, truncated, logTrimmed, false);
    }

    static SaveRefusal of(boolean disposed, boolean loading, boolean truncated, boolean logTrimmed, boolean locked) {
        if (disposed) {
            return DISPOSED;
        }
        if (loading) {
            return LOADING;
        }
        if (truncated) {
            return TRUNCATED;
        }
        if (locked) {
            return LOCKED;
        }
        return logTrimmed ? LOG_TRIMMED : NONE;
    }
}
