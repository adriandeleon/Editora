package com.editora.config;

/** Why purged Local History bodies stay on disk after their rows are gone ({@link SharedConfig#historyPurgeHold}). */
public enum HistoryPurgeHold {
    /** Nothing: the next collection deletes them. */
    NONE,
    /** Another running Editora shares this configuration, so collection waits for it to exit. */
    OTHER_PROCESS,
    /** A backup of the index beside it still lists them. */
    BACKUP,
    /** The index cannot be trusted (lost, empty beside bodies, or a torn backup within its grace period). */
    INDEX_UNTRUSTED
}
