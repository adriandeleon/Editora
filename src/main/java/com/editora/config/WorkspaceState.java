package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Persisted workspace/session state (not user preferences): collapsed fold regions and tool-window
 * layout. Serialized as JSON to {@code workspace-state.json} (data stays JSON; preferences are TOML —
 * see {@link Settings}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkspaceState {

    /**
     * Current on-disk schema version of {@code workspace-state.json} / {@code projects/<id>.json}.
     *
     * <p>v1 → v2 added the editor-group layout ({@link #getEditorLayout()} plus {@code OpenFile.group}). Both
     * are additive with defaults that reproduce the old single-group behaviour, so the migration is identity.
     *
     * <p>v2 → v3 added {@code type}/{@code target} to {@link RunConfiguration}; {@code type} defaults to
     * {@code java}, which is what every pre-existing configuration was, so this is identity too.
     *
     * <p>v3 → v4 added {@code selectedRunConfig} (the toolbar selection); blank by default, identity.
     *
     * <p>v6 → v7 <em>removed</em> {@code kind} from {@link RunConfiguration}. Identity all the same: an
     * unknown field is ignored on load and dropped on the next write, and running or debugging is now the
     * caller's choice rather than something the entry declares.
     *
     * <p>v10 → v11 added {@code projectMapFlow}; right-to-left is the default canvas layout.
     */
    public static final int SCHEMA_VERSION = 11;

    private int schemaVersion = SCHEMA_VERSION;

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    // --- Tool window layout state (id of the open window per side, or "" if none) ---
    private String openLeftToolWindow = "";
    private String openRightToolWindow = "";
    private String openBottomToolWindow = "";
    /**
     * The tool windows open on each side: side name ("LEFT"/"RIGHT"/"BOTTOM") -> ids, in the order they
     * are stacked. Additive in schema v8, which is when a side stopped being able to hold only one.
     *
     * <p>The three single-id fields above are still written, carrying each side's <em>first</em> window, so
     * a v7 build reading a v8 file restores a sensible single-window layout rather than an empty one.
     */
    private Map<String, List<String>> openToolWindows = new LinkedHashMap<>();
    /**
     * Where the divider sits <em>inside</em> a split side: side name -> fraction. Distinct from
     * {@code toolWindowSizes}, which is how much of the editor the whole side takes — this is how the side
     * divides between the two windows sharing it, so it belongs to the side rather than to either window.
     */
    private Map<String, Double> toolWindowSplitDividers = new LinkedHashMap<>();

    /** Tool windows that were open in their own floating stage. Additive in schema v9. */
    private List<String> floatingToolWindows = new ArrayList<>();
    /** Where each floating tool window was last left: id -> [x, y, width, height]. Additive in schema v9. */
    private Map<String, List<Double>> floatingToolWindowBounds = new LinkedHashMap<>();
    /**
     * How each tool window was last presented: id -> "DOCKED"|"MAXIMIZED"|"FLOATING". Missing means
     * docked for compatibility with sessions written before schema v10. Unlike the open-window lists,
     * this survives closing the tool window so its next explicit open returns to the same presentation.
     */
    private Map<String, String> toolWindowPresentationModes = new LinkedHashMap<>();

    private double leftDividerPosition = 0.22;
    private double rightDividerPosition = 0.78;
    private double bottomDividerPosition = 0.72;
    /**
     * Per-tool-window size: id -> the split fraction it was last left at. Additive in schema v6; a window
     * absent here falls back to its side's value above, which is also kept up to date — so a window opened
     * for the first time still inherits a sensible width rather than a hardcoded default.
     */
    private Map<String, Double> toolWindowSizes = new LinkedHashMap<>();
    /** Per-tool-window side preference: id -> "LEFT"|"RIGHT"|"BOTTOM". Overrides the default side. */
    private Map<String, String> toolWindowSides = new LinkedHashMap<>();
    /** Per-tool-window visibility: id -> true/false. Missing = visible. */
    private Map<String, Boolean> toolWindowVisible = new LinkedHashMap<>();
    /** Tool-window stripe order: ids in display order. Ids absent here fall back to registration order. */
    private List<String> toolWindowOrder = new ArrayList<>();
    /** Persisted collapsed fold regions: absolute file path -> header line indices (0-based). */
    private Map<String, List<Integer>> foldedRegions = new LinkedHashMap<>();

    /** Per-file manual fold ranges as flattened {@code [s1, e1, s2, e2, …]} line pairs (see
     *  {@code editor/ManualFolds}); additive in schema v5, absent = none. */
    private Map<String, List<Integer>> manualFoldRegions = new LinkedHashMap<>();
    /** Persisted Markdown view mode per file: absolute path -> "EDITOR"|"SPLIT"|"PREVIEW". */
    private Map<String, String> markdownViewModes = new LinkedHashMap<>();
    /** Persisted Markwhen preview renderer per file: absolute path -> "TIMELINE"|"CALENDAR". */
    private Map<String, String> markwhenViews = new LinkedHashMap<>();
    /** Per-file spell-check dictionary override: absolute path -> language id (e.g. "en_GB"). */
    private Map<String, String> spellLanguages = new LinkedHashMap<>();
    /** Files the user pinned read-only ("View mode"): absolute paths. */
    private List<String> readOnlyFiles = new ArrayList<>();
    /** Debugger watch expressions (the Debug window's Watches node), re-evaluated on every stop. */
    private List<String> debugWatches = new ArrayList<>();
    /** Program arguments per runnable file (absolute path -> raw args string), shared by Run + Debug. */
    private Map<String, String> programArgs = new LinkedHashMap<>();
    /** Saved run/debug configurations for project main classes (this window's project). */
    private List<RunConfiguration> runConfigurations = new ArrayList<>();

    /** Name of the configuration selected in the toolbar, so the choice survives a restart. */
    private String selectedRunConfig = "";
    /** Last Project Map flow direction; blank/unknown values fall back to right-to-left. Additive in v11. */
    private String projectMapFlow = "RIGHT_TO_LEFT";
    /** The active HTTP Client environment name (for {@code .http} {@code {{var}}} resolution), or "". */
    private String httpEnvironment = "";

    // --- Zen (distraction-free) mode (per window). zenMode is a per-window effective overlay folded into
    //     the chrome/view-settings application (MainController.zenActive) — it hides chrome WITHOUT mutating
    //     the shared Settings prefs, so leaving Zen restores them untouched. Entering Zen also closes the
    //     open tool windows, snapshotting their ids into preZenToolWindows to reopen on exit. ---
    private boolean zenMode;
    private List<String> preZenToolWindows = new ArrayList<>();

    // --- Expert mode (per window): like Zen, but keeps the line-number gutter and the status bar. Same
    //     per-window effective-overlay model (folded in via MainController.expertActive); mutually exclusive
    //     with Zen. Entering it closes the open tool windows, snapshotting them into preExpertToolWindows. ---
    private boolean expertMode;
    private List<String> preExpertToolWindows = new ArrayList<>();

    /** Files open at last exit, in tab order. */
    private List<OpenFile> openFiles = new ArrayList<>();

    /** The editor-area split tree; null when unsplit (and for any session saved before groups existed). */
    private EditorGroupLayout editorLayout;
    /** Absolute path of the tab that was active at last exit ("" if none/untitled). */
    private String activeFile = "";

    // --- Main window bounds (0 width/height = "unset", use defaults). When maximized, the bounds
    //     hold the last non-maximized geometry so un-maximizing restores a sensible size. ---
    private double windowX;
    private double windowY;
    private double windowWidth;
    private double windowHeight;
    private boolean windowMaximized;

    /**
     * One persisted open file: its absolute path, the caret offset to restore, whether it was pinned, and
     * which editor group held it.
     */
    public static class OpenFile {
        private String path = "";
        private int caret;
        private boolean pinned;
        private int group;

        public OpenFile() {}

        public OpenFile(String path, int caret, boolean pinned) {
            this(path, caret, pinned, 0);
        }

        public OpenFile(String path, int caret, boolean pinned, int group) {
            this.path = path;
            this.caret = caret;
            this.pinned = pinned;
            this.group = Math.max(0, group);
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path == null ? "" : path;
        }

        public int getCaret() {
            return caret;
        }

        public void setCaret(int caret) {
            this.caret = caret;
        }

        public boolean isPinned() {
            return pinned;
        }

        public void setPinned(boolean pinned) {
            this.pinned = pinned;
        }

        /**
         * Which editor group holds this file, as the group's ordinal in depth-first order over
         * {@link WorkspaceState#getEditorLayout()}. Defaults to 0, so a session written before editor groups
         * existed — or one saved while unsplit — restores into the single group with no special case.
         */
        public int getGroup() {
            return group;
        }

        public void setGroup(int group) {
            this.group = Math.max(0, group);
        }
    }

    public String getOpenLeftToolWindow() {
        return openLeftToolWindow;
    }

    public void setOpenLeftToolWindow(String openLeftToolWindow) {
        this.openLeftToolWindow = openLeftToolWindow == null ? "" : openLeftToolWindow;
    }

    public String getOpenRightToolWindow() {
        return openRightToolWindow;
    }

    public void setOpenRightToolWindow(String openRightToolWindow) {
        this.openRightToolWindow = openRightToolWindow == null ? "" : openRightToolWindow;
    }

    public String getOpenBottomToolWindow() {
        return openBottomToolWindow;
    }

    public void setOpenBottomToolWindow(String openBottomToolWindow) {
        this.openBottomToolWindow = openBottomToolWindow == null ? "" : openBottomToolWindow;
    }

    public List<String> getFloatingToolWindows() {
        return floatingToolWindows;
    }

    public void setFloatingToolWindows(List<String> floatingToolWindows) {
        this.floatingToolWindows = floatingToolWindows == null ? new ArrayList<>() : floatingToolWindows;
    }

    public Map<String, List<Double>> getFloatingToolWindowBounds() {
        return floatingToolWindowBounds;
    }

    public void setFloatingToolWindowBounds(Map<String, List<Double>> floatingToolWindowBounds) {
        this.floatingToolWindowBounds =
                floatingToolWindowBounds == null ? new LinkedHashMap<>() : floatingToolWindowBounds;
    }

    public Map<String, String> getToolWindowPresentationModes() {
        return toolWindowPresentationModes;
    }

    public void setToolWindowPresentationModes(Map<String, String> toolWindowPresentationModes) {
        this.toolWindowPresentationModes =
                toolWindowPresentationModes == null ? new LinkedHashMap<>() : toolWindowPresentationModes;
    }

    public Map<String, List<String>> getOpenToolWindows() {
        return openToolWindows;
    }

    public void setOpenToolWindows(Map<String, List<String>> openToolWindows) {
        this.openToolWindows = openToolWindows == null ? new LinkedHashMap<>() : openToolWindows;
    }

    public Map<String, Double> getToolWindowSplitDividers() {
        return toolWindowSplitDividers;
    }

    public void setToolWindowSplitDividers(Map<String, Double> toolWindowSplitDividers) {
        this.toolWindowSplitDividers =
                toolWindowSplitDividers == null ? new LinkedHashMap<>() : toolWindowSplitDividers;
    }

    public Map<String, Double> getToolWindowSizes() {
        return toolWindowSizes;
    }

    public void setToolWindowSizes(Map<String, Double> toolWindowSizes) {
        this.toolWindowSizes = toolWindowSizes == null ? new LinkedHashMap<>() : toolWindowSizes;
    }

    public double getLeftDividerPosition() {
        return leftDividerPosition;
    }

    public void setLeftDividerPosition(double leftDividerPosition) {
        this.leftDividerPosition = leftDividerPosition;
    }

    public double getRightDividerPosition() {
        return rightDividerPosition;
    }

    public void setRightDividerPosition(double rightDividerPosition) {
        this.rightDividerPosition = rightDividerPosition;
    }

    public double getBottomDividerPosition() {
        return bottomDividerPosition;
    }

    public void setBottomDividerPosition(double bottomDividerPosition) {
        this.bottomDividerPosition = bottomDividerPosition;
    }

    public Map<String, String> getToolWindowSides() {
        return toolWindowSides;
    }

    public void setToolWindowSides(Map<String, String> toolWindowSides) {
        this.toolWindowSides = toolWindowSides == null ? new LinkedHashMap<>() : toolWindowSides;
    }

    public Map<String, Boolean> getToolWindowVisible() {
        return toolWindowVisible;
    }

    public void setToolWindowVisible(Map<String, Boolean> toolWindowVisible) {
        this.toolWindowVisible = toolWindowVisible == null ? new LinkedHashMap<>() : toolWindowVisible;
    }

    public List<String> getToolWindowOrder() {
        return toolWindowOrder;
    }

    public void setToolWindowOrder(List<String> toolWindowOrder) {
        this.toolWindowOrder = toolWindowOrder == null ? new ArrayList<>() : toolWindowOrder;
    }

    public Map<String, List<Integer>> getFoldedRegions() {
        return foldedRegions;
    }

    public void setFoldedRegions(Map<String, List<Integer>> foldedRegions) {
        this.foldedRegions = foldedRegions == null ? new LinkedHashMap<>() : foldedRegions;
    }

    public Map<String, List<Integer>> getManualFoldRegions() {
        return manualFoldRegions;
    }

    public void setManualFoldRegions(Map<String, List<Integer>> manualFoldRegions) {
        this.manualFoldRegions = manualFoldRegions == null ? new LinkedHashMap<>() : manualFoldRegions;
    }

    public Map<String, String> getMarkdownViewModes() {
        return markdownViewModes;
    }

    public Map<String, String> getMarkwhenViews() {
        return markwhenViews;
    }

    public void setMarkwhenViews(Map<String, String> markwhenViews) {
        this.markwhenViews = markwhenViews == null ? new LinkedHashMap<>() : markwhenViews;
    }

    public void setMarkdownViewModes(Map<String, String> markdownViewModes) {
        this.markdownViewModes = markdownViewModes == null ? new LinkedHashMap<>() : markdownViewModes;
    }

    public Map<String, String> getSpellLanguages() {
        return spellLanguages;
    }

    public void setSpellLanguages(Map<String, String> spellLanguages) {
        this.spellLanguages = spellLanguages == null ? new LinkedHashMap<>() : spellLanguages;
    }

    public List<String> getReadOnlyFiles() {
        return readOnlyFiles;
    }

    public void setReadOnlyFiles(List<String> readOnlyFiles) {
        this.readOnlyFiles = readOnlyFiles == null ? new ArrayList<>() : readOnlyFiles;
    }

    public List<String> getDebugWatches() {
        return debugWatches;
    }

    public List<RunConfiguration> getRunConfigurations() {
        return runConfigurations;
    }

    public void setRunConfigurations(List<RunConfiguration> runConfigurations) {
        this.runConfigurations = runConfigurations == null ? new ArrayList<>() : runConfigurations;
    }

    public String getSelectedRunConfig() {
        return selectedRunConfig;
    }

    public void setSelectedRunConfig(String selectedRunConfig) {
        this.selectedRunConfig = selectedRunConfig == null ? "" : selectedRunConfig;
    }

    public String getProjectMapFlow() {
        return projectMapFlow == null ? "RIGHT_TO_LEFT" : projectMapFlow;
    }

    public void setProjectMapFlow(String projectMapFlow) {
        this.projectMapFlow = projectMapFlow == null ? "RIGHT_TO_LEFT" : projectMapFlow;
    }

    public Map<String, String> getProgramArgs() {
        return programArgs;
    }

    public void setProgramArgs(Map<String, String> programArgs) {
        this.programArgs = programArgs == null ? new LinkedHashMap<>() : programArgs;
    }

    public void setDebugWatches(List<String> debugWatches) {
        this.debugWatches = debugWatches == null ? new ArrayList<>() : debugWatches;
    }

    public String getHttpEnvironment() {
        return httpEnvironment == null ? "" : httpEnvironment;
    }

    public void setHttpEnvironment(String httpEnvironment) {
        this.httpEnvironment = httpEnvironment == null ? "" : httpEnvironment;
    }

    public boolean isZenMode() {
        return zenMode;
    }

    public void setZenMode(boolean zenMode) {
        this.zenMode = zenMode;
    }

    public List<String> getPreZenToolWindows() {
        return preZenToolWindows;
    }

    public void setPreZenToolWindows(List<String> preZenToolWindows) {
        this.preZenToolWindows = preZenToolWindows == null ? new ArrayList<>() : preZenToolWindows;
    }

    public boolean isExpertMode() {
        return expertMode;
    }

    public void setExpertMode(boolean expertMode) {
        this.expertMode = expertMode;
    }

    public List<String> getPreExpertToolWindows() {
        return preExpertToolWindows;
    }

    public void setPreExpertToolWindows(List<String> preExpertToolWindows) {
        this.preExpertToolWindows = preExpertToolWindows == null ? new ArrayList<>() : preExpertToolWindows;
    }

    public List<OpenFile> getOpenFiles() {
        return openFiles;
    }

    public void setOpenFiles(List<OpenFile> openFiles) {
        this.openFiles = openFiles == null ? new ArrayList<>() : openFiles;
    }

    /**
     * The editor area's split layout, or {@code null} when it was a single group — which is also what an
     * older session file yields, so the unsplit case needs no migration. See {@link EditorGroupLayout}.
     */
    public EditorGroupLayout getEditorLayout() {
        return editorLayout;
    }

    public void setEditorLayout(EditorGroupLayout editorLayout) {
        this.editorLayout = editorLayout;
    }

    public String getActiveFile() {
        return activeFile;
    }

    public void setActiveFile(String activeFile) {
        this.activeFile = activeFile == null ? "" : activeFile;
    }

    public double getWindowX() {
        return windowX;
    }

    public void setWindowX(double windowX) {
        this.windowX = windowX;
    }

    public double getWindowY() {
        return windowY;
    }

    public void setWindowY(double windowY) {
        this.windowY = windowY;
    }

    public double getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(double windowWidth) {
        this.windowWidth = windowWidth;
    }

    public double getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(double windowHeight) {
        this.windowHeight = windowHeight;
    }

    public boolean isWindowMaximized() {
        return windowMaximized;
    }

    public void setWindowMaximized(boolean windowMaximized) {
        this.windowMaximized = windowMaximized;
    }
}
