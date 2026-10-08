package com.editora.sync;

import java.util.Locale;

/** One kind of user data that settings sync carries, and which files of the config directory hold it. */
public enum SyncCategory {
    /** {@code snippets/<language>.json}. */
    SNIPPETS("snippets/", true),
    /** {@code abbreviations.json}. */
    ABBREVIATIONS("abbreviations.json", false),
    /** {@code templates/<id>.json}. */
    TEMPLATES("templates/", true),
    /** {@code dictionary.txt}. */
    DICTIONARY("dictionary.txt", false);

    private final String path;
    private final boolean directory;

    SyncCategory(String path, boolean directory) {
        this.path = path;
        this.directory = directory;
    }

    /** The file, or for a directory category the folder with a trailing slash; relative to the config directory. */
    public String path() {
        return path;
    }

    public boolean isDirectory() {
        return directory;
    }

    /**
     * Whether {@code relativePath} (forward slashes) is one of this category's files. A directory category
     * owns the {@code .json} files directly inside its folder, nothing deeper and no staging file.
     */
    public boolean owns(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        if (!directory) {
            return path.equals(relativePath);
        }
        if (!relativePath.startsWith(path)) {
            return false;
        }
        String name = relativePath.substring(path.length());
        return !name.isEmpty()
                && name.indexOf('/') < 0
                && !name.startsWith(".")
                && name.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /** The category that owns {@code relativePath}, or null. */
    public static SyncCategory of(String relativePath) {
        for (SyncCategory c : values()) {
            if (c.owns(relativePath)) {
                return c;
            }
        }
        return null;
    }
}
