package com.editora.config.migration;

import java.util.Map;

import com.editora.config.BookmarkStore;
import com.editora.config.NoteStore;
import com.editora.config.ProjectManager;
import com.editora.config.RecentFiles;
import com.editora.config.Settings;
import com.editora.config.WorkspaceState;

/**
 * The versioned config files and their migration registries. Each entry knows its <b>current</b> schema
 * version (from the owning POJO's {@code SCHEMA_VERSION}), the version to <b>assume when the file has no
 * {@code schemaVersion} marker</b> (the pre-versioning baseline = 1; a bare JSON array is detected as v0
 * by {@link ConfigMigrations#versionOf}), and the ordered <b>step migrations</b> keyed by the version they
 * upgrade <em>from</em> ({@code v → v+1}).
 *
 * <p>To evolve a file's format in a future release: bump that POJO's {@code SCHEMA_VERSION} and add a
 * {@code v → v+1} entry to its {@code steps} map here. Everything else (read path, stamping, downgrade
 * backup) is automatic.
 */
public enum ConfigSchema {
    // v1 → v2 added the Personal Notes flags; v2 → v3 added markdownPreviewTheme; v3 → v4 added
    // showToolStripe; v4 → v5 added the Mermaid flags — all additive, so identity.
    SETTINGS(Settings.SCHEMA_VERSION, 1, Map.of(
            1, ConfigMigrations::identity,
            2, ConfigMigrations::identity,
            3, ConfigMigrations::identity,
            4, ConfigMigrations::identity)),
    WORKSPACE(WorkspaceState.SCHEMA_VERSION, 1, Map.of()),
    BOOKMARKS(BookmarkStore.SCHEMA_VERSION, 1, Map.of()),
    PROJECTS(ProjectManager.Index.SCHEMA_VERSION, 1, Map.of()),
    /** Legacy {@code recent-files.json} was a bare JSON array (v0); v1 wraps it in an object. */
    RECENT(RecentFiles.SCHEMA_VERSION, 1, Map.of(0, ConfigMigrations::wrapRecentFilesArray)),
    NOTES(NoteStore.SCHEMA_VERSION, 1, Map.of());

    private final int currentVersion;
    private final int assumedLegacyVersion;
    private final Map<Integer, Migration> steps;

    ConfigSchema(int currentVersion, int assumedLegacyVersion, Map<Integer, Migration> steps) {
        this.currentVersion = currentVersion;
        this.assumedLegacyVersion = assumedLegacyVersion;
        this.steps = steps;
    }

    public int currentVersion() {
        return currentVersion;
    }

    public int assumedLegacyVersion() {
        return assumedLegacyVersion;
    }

    /** The step that upgrades {@code fromVersion → fromVersion+1}, or {@code null} if none is registered. */
    public Migration step(int fromVersion) {
        return steps.get(fromVersion);
    }
}
