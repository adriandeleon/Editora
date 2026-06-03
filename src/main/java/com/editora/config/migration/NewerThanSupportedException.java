package com.editora.config.migration;

/**
 * Thrown when a config file's stored {@code schemaVersion} is newer than the version this build of Editora
 * supports (e.g. the user downgraded the app). The caller backs the file up and falls back to defaults so
 * an older build never overwrites — and silently drops fields from — a newer file.
 */
public class NewerThanSupportedException extends RuntimeException {

    private final transient ConfigSchema schema;
    private final int storedVersion;
    private final int currentVersion;

    public NewerThanSupportedException(ConfigSchema schema, int storedVersion, int currentVersion) {
        super(schema + " on disk is schema v" + storedVersion
                + " but this build supports up to v" + currentVersion);
        this.schema = schema;
        this.storedVersion = storedVersion;
        this.currentVersion = currentVersion;
    }

    public ConfigSchema schema() {
        return schema;
    }

    public int storedVersion() {
        return storedVersion;
    }

    public int currentVersion() {
        return currentVersion;
    }
}
