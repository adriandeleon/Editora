package com.editora.config.migration;

import java.util.Map;

import com.editora.config.BookmarkStore;
import com.editora.config.BreakpointStore;
import com.editora.config.ConnectionStore;
import com.editora.config.HistoryStore;
import com.editora.config.MacroStore;
import com.editora.config.NoteStore;
import com.editora.config.PluginStore;
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
    // showToolStripe; v4 → v5 added the Mermaid flags; v5 → v6 added the PDF-export options; v6 → v7
    // added the LSP flags (lspSupport/javaLspCommand); v7 → v8 added the TypeScript server; v8 → v9 added
    // Python; v9 → v10 added the XML/JSON/Bash servers; v10 → v11 added the YAML/Go/Rust/PHP/Ruby
    // servers; v11 → v12 added the C/C++/HTML/CSS/Kotlin/Lua/Dockerfile/SQL/Terraform/TOML servers;
    // v12 → v13 added the C# server; v13 → v14 added markdownFormatBar; v14 → v15 added
    // debugSupport + javaDebugPluginPath; v15 → v16 added pythonDebug/jsDebug enable+command;
    // v16 → v17 added multiCaret; v17 → v18 added authorName (file templates); v18 → v19 added
    // httpClientSupport + ijhttpCommand — all additive, so identity.
    SETTINGS(
            Settings.SCHEMA_VERSION,
            1,
            Map.<Integer, Migration>ofEntries(
                    Map.entry(1, (Migration) ConfigMigrations::identity),
                    Map.entry(2, (Migration) ConfigMigrations::identity),
                    Map.entry(3, (Migration) ConfigMigrations::identity),
                    Map.entry(4, (Migration) ConfigMigrations::identity),
                    Map.entry(5, (Migration) ConfigMigrations::identity),
                    Map.entry(6, (Migration) ConfigMigrations::identity),
                    Map.entry(7, (Migration) ConfigMigrations::identity),
                    Map.entry(8, (Migration) ConfigMigrations::identity),
                    Map.entry(9, (Migration) ConfigMigrations::identity),
                    Map.entry(10, (Migration) ConfigMigrations::identity),
                    Map.entry(11, (Migration) ConfigMigrations::identity),
                    Map.entry(12, (Migration) ConfigMigrations::identity),
                    Map.entry(13, (Migration) ConfigMigrations::identity),
                    Map.entry(14, (Migration) ConfigMigrations::identity),
                    Map.entry(15, (Migration) ConfigMigrations::identity),
                    Map.entry(16, (Migration) ConfigMigrations::identity),
                    Map.entry(17, (Migration) ConfigMigrations::identity),
                    Map.entry(18, (Migration) ConfigMigrations::identity),
                    Map.entry(19, (Migration) ConfigMigrations::identity), // v19→20: + simpleMode (additive)
                    Map.entry(20, (Migration) ConfigMigrations::identity), // v20→21: + gitBlameInline (additive)
                    Map.entry(21, (Migration) ConfigMigrations::identity), // v21→22: + pluginSupport (additive)
                    Map.entry(22, (Migration) ConfigMigrations::identity), // v22→23: + pluginRegistryUrl (additive)
                    Map.entry(23, (Migration) ConfigMigrations::identity), // v23→24: + pluginRequireSignature
                    Map.entry(24, (Migration) ConfigMigrations::identity), // v24→25: + htmlPreviewSupport/Browser
                    Map.entry(25, (Migration) ConfigMigrations::identity), // v25→26: + fillColumn (additive)
                    Map.entry(26, (Migration) ConfigMigrations::identity), // v26→27: + localHistory + limits
                    Map.entry(27, (Migration) ConfigMigrations::identity), // v27→28: + mcpSupport (additive)
                    Map.entry(28, (Migration) ConfigMigrations::identity), // v28→29: + completionDoc (additive)
                    Map.entry(29, (Migration) ConfigMigrations::identity), // v29→30: + editorConfigSupport
                    Map.entry(30, (Migration) ConfigMigrations::identity), // v30→31: + indentStyle (additive)
                    Map.entry(31, (Migration) ConfigMigrations::identity), // v31→32: + semanticHighlight
                    Map.entry(32, (Migration) ConfigMigrations::identity), // v32→33: + todoHighlight/todoPatterns
                    Map.entry(33, (Migration) ConfigMigrations::identity), // v33→34: + markdownLint
                    Map.entry(34, (Migration) ConfigMigrations::identity))), // v34→35: + mathSupport
    WORKSPACE(WorkspaceState.SCHEMA_VERSION, 1, Map.of()),
    BOOKMARKS(BookmarkStore.SCHEMA_VERSION, 1, Map.of()),
    BREAKPOINTS(BreakpointStore.SCHEMA_VERSION, 1, Map.of()),
    // v1 → v2 added openProjectIds (the multi-window open-set), seeded from the old activeProjectId.
    PROJECTS(ProjectManager.Index.SCHEMA_VERSION, 1, Map.of(1, ConfigMigrations::seedOpenProjectIds)),
    /** Legacy {@code recent-files.json} was a bare JSON array (v0); v1 wraps it in an object. */
    RECENT(RecentFiles.SCHEMA_VERSION, 1, Map.of(0, ConfigMigrations::wrapRecentFilesArray)),
    NOTES(NoteStore.SCHEMA_VERSION, 1, Map.of()),
    CONNECTIONS(ConnectionStore.SCHEMA_VERSION, 1, Map.of()),
    PLUGINS(PluginStore.SCHEMA_VERSION, 1, Map.of()),
    HISTORY(HistoryStore.SCHEMA_VERSION, 1, Map.of()),
    MACROS(MacroStore.SCHEMA_VERSION, 1, Map.of());

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
