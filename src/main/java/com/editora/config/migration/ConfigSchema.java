package com.editora.config.migration;

import java.util.Map;

import com.editora.config.AbbrevStore;
import com.editora.config.AgentSessionHistory;
import com.editora.config.BookmarkStore;
import com.editora.config.BreakpointStore;
import com.editora.config.ConnectionStore;
import com.editora.config.HistoryStore;
import com.editora.config.MacroStore;
import com.editora.config.NoteStore;
import com.editora.config.PluginStore;
import com.editora.config.ProjectManager;
import com.editora.config.RecentFiles;
import com.editora.config.SearchHistory;
import com.editora.config.Settings;
import com.editora.config.TrustStore;
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
                    Map.entry(34, (Migration) ConfigMigrations::identity), // v34→35: + mathSupport
                    Map.entry(35, (Migration) ConfigMigrations::identity), // v35→36: + externalTools (additive)
                    Map.entry(36, (Migration) ConfigMigrations::identity), // v36→37: + ripgrepSearch/Command
                    Map.entry(37, (Migration) ConfigMigrations::identity), // v37→38: + logViewer (additive)
                    Map.entry(38, (Migration) ConfigMigrations::identity), // v38→39: + projectShowHidden
                    Map.entry(39, (Migration) ConfigMigrations::identity), // v39→40: + markdownLintDisabledRules
                    Map.entry(40, (Migration) ConfigMigrations::identity), // v40→41: + personalDictionary (additive)
                    Map.entry(41, (Migration) ConfigMigrations::identity), // v41→42: + technicalDictionary (additive)
                    Map.entry(42, (Migration) ConfigMigrations::identity), // v42→43: + searchRespectGitignore
                    Map.entry(43, (Migration) ConfigMigrations::identity), // v43→44: + largeFileThreshold (additive)
                    Map.entry(44, (Migration) ConfigMigrations::identity), // v44→45: + lspInstallPrompts (additive)
                    Map.entry(45, (Migration) ConfigMigrations::identity), // v45→46: + wordWrap (additive)
                    Map.entry(46, (Migration) ConfigMigrations::identity), // v46→47: + adminSave (additive)
                    Map.entry(47, (Migration) ConfigMigrations::identity), // v47→48: + csvPreview (additive)
                    Map.entry(48, (Migration) ConfigMigrations::identity), // v48→49: + csvRainbow (additive)
                    Map.entry(49, (Migration) ConfigMigrations::growTodoDefaultKeywords), // v49→50: grow TODO keywords
                    Map.entry(50, (Migration) ConfigMigrations::identity), // v50→51: + autoRenameTag (additive)
                    Map.entry(51, (Migration)
                            ConfigMigrations::identity), // v51→52: + agentSupport/agentCommand (additive)
                    Map.entry(52, (Migration) ConfigMigrations::identity), // v52→53: + autoCloseTags (additive)
                    Map.entry(53, (Migration)
                            ConfigMigrations::identity), // v53→54: + aiSupport/aiModel/aiApiKey (additive)
                    Map.entry(54, (Migration)
                            ConfigMigrations::identity), // v54→55: + aiInlineCompletion/aiCompletionModel (additive)
                    Map.entry(55, (Migration) ConfigMigrations::identity), // v55→56: + todoGroupBy (additive)
                    Map.entry(56, (Migration) ConfigMigrations::identity), // v56→57: + aiProvider/aiEndpoint (additive)
                    Map.entry(57, (Migration) ConfigMigrations::identity), // v57→58: + TODO part colors (additive)
                    Map.entry(58, (Migration) ConfigMigrations::identity), // v58→59: + agentIncludeContext (additive)
                    Map.entry(59, (Migration)
                            ConfigMigrations::identity), // v59→60: + agentClient/<id>AgentCommand (additive)
                    Map.entry(60, (Migration) ConfigMigrations::identity), // v60→61: + aiEnabled (additive)
                    Map.entry(61, (Migration)
                            ConfigMigrations::identity), // v61→62: + mavenSupport/mavenCommand (additive)
                    Map.entry(62, (Migration)
                            ConfigMigrations::identity), // v62→63: + diagramSupport/dotPath/plantumlPath (additive)
                    Map.entry(63, (Migration) ConfigMigrations::identity), // v63→64: + structuredPreview (additive)
                    Map.entry(64, (Migration) ConfigMigrations::identity), // v64→65: + svgPreview (additive)
                    Map.entry(65, (Migration) ConfigMigrations::identity), // v65→66: + crontabPreview (additive)
                    Map.entry(
                            66, (Migration) ConfigMigrations::identity), // v66→67: + typstSupport/typstPath (additive)
                    Map.entry(67, (Migration) ConfigMigrations::identity), // v67→68: + fstabPreview (additive)
                    Map.entry(68, (Migration) ConfigMigrations::identity), // v68→69: + npmSupport/npmCommand (additive)
                    Map.entry(69, (Migration)
                            ConfigMigrations::identity), // v69→70: + cargoSupport/cargoCommand (additive)
                    Map.entry(70, (Migration) ConfigMigrations::identity), // v70→71: + goSupport/goCommand (additive)
                    Map.entry(71, (Migration)
                            ConfigMigrations::identity), // v71→72: + gradleSupport/gradleCommand (additive)
                    Map.entry(72, (Migration)
                            ConfigMigrations::identity), // v72→73: + typstLspCommand/typstLspEnabled (additive)
                    Map.entry(73, (Migration) ConfigMigrations::identity), // v73→74: + systemd/ssh/dockerfile previews
                    Map.entry(74, (Migration) ConfigMigrations::identity), // v74→75: + githubActionsPreview (additive)
                    Map.entry(
                            75, (Migration) ConfigMigrations::identity), // v75→76: + copyLineWhenNoSelection (additive)
                    Map.entry(76, (Migration)
                            ConfigMigrations::identity), // v76→77: + updateCheck/lastUpdateCheck/dismissed
                    // v77→78: split the shared aiApiKey onto per-provider fields (aiApiKey = Anthropic,
                    // aiApiKeyOpenai = OpenAI-compatible) so switching provider can't leak the other's key.
                    Map.entry(77, (Migration) ConfigMigrations::splitAiApiKeyByProvider),
                    // v78→79: + mavenPomLspEnabled/mavenPomLspCommand (Maven-aware pom.xml server; additive)
                    Map.entry(78, (Migration) ConfigMigrations::identity),
                    Map.entry(79, (Migration) ConfigMigrations::identity), // v79→80: + toolbarLayout (additive)
                    // v80→81: split keybindings into per-platform maps (keybindings=Ctrl, keybindingsMac=Cmd) so a
                    // synced config no longer double-binds a rebound command across macOS/Windows (#439).
                    Map.entry(80, (Migration) ConfigMigrations::splitKeybindingsByPlatform),
                    Map.entry(81, (Migration) ConfigMigrations::identity), // v81→82: + githubSupport/ghPath (additive)
                    Map.entry(82, (Migration) ConfigMigrations::identity), // v82→83: + autoFill (additive)
                    Map.entry(83, (Migration)
                            ConfigMigrations::identity), // v83→84: + abbreviations/abbrevMode (additive)
                    Map.entry(84, (Migration) ConfigMigrations::identity), // v84→85: + inlayHints (additive)
                    Map.entry(85, (Migration)
                            ConfigMigrations::identity), // v85→86: + copyWithSyntaxHighlighting (additive)
                    Map.entry(86, (Migration) ConfigMigrations::identity), // v86→87: + lspOnTypeFormatting (additive)
                    Map.entry(87, (Migration) ConfigMigrations::identity), // v87→88: + showMenuBar (additive)
                    // v88→89: Projects went from opt-in to on. NOT identity — the default change alone reaches
                    // only a fresh config dir, since every existing settings file stores the old false.
                    Map.entry(88, (Migration) ConfigMigrations::enableProjectSupport),
                    Map.entry(89, (Migration) ConfigMigrations::identity), // v89→90: + bracketColors (additive)
                    Map.entry(90, (Migration) ConfigMigrations::identity), // v90→91: + lspPasteImports (additive)
                    Map.entry(91, (Migration) ConfigMigrations::identity), // v91→92: + lspSmartSemicolon (additive)
                    Map.entry(92, (Migration) ConfigMigrations::identity), // v92→93: + gitPath (additive)
                    Map.entry(93, (Migration) ConfigMigrations::identity), // v93→94: + mavenArchetypeCatalogUrl
                    Map.entry(94, (Migration) ConfigMigrations::identity), // v94→95: + inlayHintMode (additive)
                    Map.entry(95, (Migration) ConfigMigrations::identity), // v95→96: + pomPreview (additive)
                    Map.entry(96, (Migration) ConfigMigrations::identity), // v96→97: + stickyScroll (additive)
                    Map.entry(97, (Migration) ConfigMigrations::identity), // v97→98: + symbolIndex (additive)
                    // v98→99: + paletteUsesSearchEverywhere (additive)
                    Map.entry(98, (Migration) ConfigMigrations::identity),
                    // v99→100: + extendedWindow (additive; default off reproduces the decorated window)
                    Map.entry(99, (Migration) ConfigMigrations::identity),
                    // v100→101: Recent moved from the fixed tail into the customizable cluster — put it back
                    // into a layout the user had already rearranged, which is used verbatim.
                    Map.entry(100, (Migration) ConfigMigrations::restoreRecentToToolbarLayout),
                    Map.entry(101, (Migration) ConfigMigrations::identity), // v101→102: + Astro LSP
                    Map.entry(102, (Migration) ConfigMigrations::identity), // v102→103: + Maven JDK
                    Map.entry(103, (Migration)
                            ConfigMigrations::identity), // v103→104: Codex + LM Studio providers; preserve choices
                    // v104→105: authorName/pluginRegistryUrl persist their raw (blank = follow the default)
                    // value; drop the accidental authorNameRaw key and the never-read ijhttpCommand.
                    Map.entry(104, (Migration) ConfigMigrations::retireUnusedSettingsKeys),
                    // v105→106: key-binding overrides belong to a keymap. keybindings/keybindingsMac stay where
                    // they are and now mean "the active keymap's" — which is the keymap every existing user's
                    // overrides were in force under — and the maps for the other keymaps start empty.
                    Map.entry(105, (Migration) ConfigMigrations::identity),
                    // v106→107: mavenArchetypeCatalogUrl persists its raw value too; a file that froze either
                    // built-in URL goes back to blank ("follow the default").
                    Map.entry(106, (Migration) ConfigMigrations::blankFrozenDefaultUrls),
                    // v107→108: + debugProgramConsole (additive; no earlier file could have chosen it, so
                    // every user gets the default — the debugged Java program can be typed to).
                    Map.entry(107, (Migration) ConfigMigrations::identity),
                    // v108→109: + codeLens (additive, off by default).
                    Map.entry(108, (Migration) ConfigMigrations::identity),
                    // v109→110: + gitPullMode (additive; absent means "ff-only", which is what pull did
                    // before the setting existed — nobody's pull starts rebasing or merging on upgrade).
                    Map.entry(109, (Migration) ConfigMigrations::identity),
                    // v110→111: + gitAutoFetch / gitAutoFetchMinutes (additive; absent means off — nobody's
                    // editor starts talking to a remote on upgrade — with the 10-minute default interval).
                    Map.entry(110, (Migration) ConfigMigrations::identity),
                    // v111→112: + crashRecovery (additive; nobody could have turned it off before it existed,
                    // so every user gets the default — unsaved text is kept for recovery).
                    Map.entry(111, (Migration) ConfigMigrations::identity)),
            // Keys that first appear in a settings file of the given version. Each one sits just after a
            // step that is not safe to repeat (v49→50 TODO keywords, v77→78 AI key split, v80→81 keybinding
            // split, v88→89 Projects on, v100→101 Recent in the toolbar), so a current-shape file without
            // a schemaVersion resumes after those steps instead of replaying all of them from v1.
            Map.of(
                    "autoRenameTag", 51,
                    "aiApiKeyOpenai", 78,
                    "keybindingsMac", 81,
                    "bracketColors", 90,
                    "astroLspEnabled", 102,
                    "mavenJdkHome", 103,
                    "aiApiKeyLmstudio", 104)),
    // v1 → v2 added the editor-group layout + OpenFile.group. Both default to the old single-group
    // behaviour, so the step is identity.
    // v1→v2 editor-group layout, v2→v3 RunConfiguration type/target, v3→v4 selectedRunConfig — all additive
    // with defaults reproducing the previous behaviour, so every step is identity.
    WORKSPACE(
            WorkspaceState.SCHEMA_VERSION,
            1,
            Map.ofEntries(
                    Map.entry(1, ConfigMigrations::identity),
                    Map.entry(2, ConfigMigrations::identity),
                    Map.entry(3, ConfigMigrations::identity),
                    Map.entry(4, ConfigMigrations::identity), // v4→5: + manualFoldRegions (additive)
                    Map.entry(5, ConfigMigrations::identity), // v5→6: + toolWindowSizes (additive)
                    // v6→7: RunConfiguration lost `kind` ("run"/"debug"). A removal, but still identity: the
                    // field is unknown on load and dropped on the next write, and a v6 reader copes with a
                    // v7 file for the same reason (a missing `kind` defaulted to "run" there anyway).
                    Map.entry(6, ConfigMigrations::identity),
                    // v7→8: a side can now hold two tool windows, so the open set became a per-side list.
                    Map.entry(7, ConfigMigrations::seedOpenToolWindows),
                    // v8→9: + floatingToolWindows / floatingToolWindowBounds (additive)
                    Map.entry(8, ConfigMigrations::identity),
                    // v9→10: + toolWindowPresentationModes (additive; missing means docked)
                    Map.entry(9, ConfigMigrations::identity),
                    // v10→v11: + projectMapFlow (additive; right-to-left is the default canvas layout)
                    Map.entry(10, ConfigMigrations::identity),
                    Map.entry(11, ConfigMigrations::identity))), // v11→12: + RunConfiguration.jdkHome
    // v1 → v2: Bookmark gained `mnemonic` (additive; absent ⇒ none). The bump is what makes an older build
    // set the file aside rather than rewrite it without the field.
    BOOKMARKS(BookmarkStore.SCHEMA_VERSION, 1, Map.of(1, ConfigMigrations::identity)),
    BREAKPOINTS(BreakpointStore.SCHEMA_VERSION, 1, Map.of()),
    // v1 → v2 added openProjectIds (the multi-window open-set), seeded from the old activeProjectId.
    PROJECTS(ProjectManager.Index.SCHEMA_VERSION, 1, Map.of(1, ConfigMigrations::seedOpenProjectIds)),
    /** Legacy {@code recent-files.json} was a bare JSON array (v0); v1 wraps it in an object. */
    RECENT(RecentFiles.SCHEMA_VERSION, 1, Map.of(0, ConfigMigrations::wrapRecentFilesArray)),
    // v1 → v2: TextAnchor gained a `length` component — additive (missing ⇒ 0, handled by the relocator).
    NOTES(NoteStore.SCHEMA_VERSION, 1, Map.of(1, ConfigMigrations::identity)),
    CONNECTIONS(ConnectionStore.SCHEMA_VERSION, 1, Map.of()),
    PLUGINS(PluginStore.SCHEMA_VERSION, 1, Map.of()),
    // v1 → v2 added the per-revision label (additive; absent rows default to "").
    // v2 → v3 added charset/bom/lineEnding on pre-delete revisions (additive; absent ⇒ unknown, restore as before).
    HISTORY(HistoryStore.SCHEMA_VERSION, 1, Map.of(1, ConfigMigrations::identity, 2, ConfigMigrations::identity)),
    SEARCH_HISTORY(SearchHistory.SCHEMA_VERSION, 1, Map.of()),
    // v1 → v2 backfilled agentId ("claude") on every session predating multi-agent support.
    AGENT_SESSIONS(AgentSessionHistory.SCHEMA_VERSION, 1, Map.of(1, ConfigMigrations::addDefaultAgentIdToSessions)),
    // v1 → v2: every macro gets a stored `id` (the id its key binding already uses), the auto-saved
    // "unnamed macro" entry is identified by `lastId` instead of by its translated name, and Enter/Tab
    // recorded as text become key steps.
    MACROS(MacroStore.SCHEMA_VERSION, 1, Map.of(1, ConfigMigrations::macrosGainIds)),
    ABBREVIATIONS(AbbrevStore.SCHEMA_VERSION, 1, Map.of()),
    /** Trusted workspace roots. Failing open to defaults (= nothing trusted) is the safe direction here. */
    TRUST(TrustStore.SCHEMA_VERSION, 1, Map.of());

    private final int currentVersion;
    private final int assumedLegacyVersion;
    private final Map<Integer, Migration> steps;
    /** Top-level key → the first schema version whose files contain it (see {@link #versionWithoutMarker}). */
    private final Map<String, Integer> versionEvidence;

    ConfigSchema(int currentVersion, int assumedLegacyVersion, Map<Integer, Migration> steps) {
        this(currentVersion, assumedLegacyVersion, steps, Map.of());
    }

    ConfigSchema(
            int currentVersion,
            int assumedLegacyVersion,
            Map<Integer, Migration> steps,
            Map<String, Integer> versionEvidence) {
        this.currentVersion = currentVersion;
        this.assumedLegacyVersion = assumedLegacyVersion;
        this.steps = steps;
        this.versionEvidence = versionEvidence;
    }

    public int currentVersion() {
        return currentVersion;
    }

    public int assumedLegacyVersion() {
        return assumedLegacyVersion;
    }

    /**
     * The version to assume for {@code tree} when it carries no {@code schemaVersion}: the newest version one
     * of its keys proves it has reached, else {@link #assumedLegacyVersion()}.
     *
     * <p>A file loses its marker through a hand edit, not through age, so it is usually in the current shape.
     * Replaying every migration from the baseline over such a file undid the user's choices: it turned
     * Projects back on, re-added removed TODO keywords and toolbar items, and on macOS replaced the Cmd
     * key-binding overrides. A key can only be in the file if a build that knew it wrote the file, so it is
     * safe evidence that the steps before it have already run. Never more than the current version.
     */
    public int versionWithoutMarker(com.fasterxml.jackson.databind.JsonNode tree) {
        int version = assumedLegacyVersion;
        if (tree != null && tree.isObject()) {
            for (Map.Entry<String, Integer> evidence : versionEvidence.entrySet()) {
                if (tree.has(evidence.getKey())) {
                    version = Math.max(version, evidence.getValue());
                }
            }
        }
        return Math.min(version, currentVersion);
    }

    /**
     * Whether a file of this kind that is not valid UTF-8 gets a {@code .corrupt.bak} copy of its original
     * bytes when it is loaded with replacements. Not the Local History index: a backup beside it stops
     * revision bodies from being collected for as long as it exists, and the index that was read still lists
     * every revision (only a path can hold a replaced character).
     */
    public boolean keepsCopyOfUndecodableFile() {
        return this != HISTORY;
    }

    /**
     * Top-level keys the app rewrites by itself, without the user changing anything: update-check bookkeeping
     * in the preferences, the open-window set in the projects index. A file that differs from the defaults
     * only in these still counts as untouched (see {@link ConfigMigrations#restoreSetAsideCopy}).
     */
    public java.util.Set<String> selfMaintainedKeys() {
        return switch (this) {
            case SETTINGS -> java.util.Set.of("lastUpdateCheckEpoch", "dismissedUpdateVersion");
            case PROJECTS -> java.util.Set.of("activeProjectId", "openProjectIds");
            default -> java.util.Set.of();
        };
    }

    /** The step that upgrades {@code fromVersion → fromVersion+1}, or {@code null} if none is registered. */
    public Migration step(int fromVersion) {
        return steps.get(fromVersion);
    }
}
