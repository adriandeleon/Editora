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

    // --- Tool window layout state (id of the open window per side, or "" if none) ---
    private String openLeftToolWindow = "";
    private String openRightToolWindow = "";
    private String openBottomToolWindow = "";
    private double leftDividerPosition = 0.22;
    private double rightDividerPosition = 0.78;
    private double bottomDividerPosition = 0.72;
    /** Per-tool-window side preference: id -> "LEFT"|"RIGHT"|"BOTTOM". Overrides the default side. */
    private Map<String, String> toolWindowSides = new LinkedHashMap<>();
    /** Per-tool-window visibility: id -> true/false. Missing = visible. */
    private Map<String, Boolean> toolWindowVisible = new LinkedHashMap<>();
    /** Tool-window stripe order: ids in display order. Ids absent here fall back to registration order. */
    private List<String> toolWindowOrder = new ArrayList<>();
    /** Persisted collapsed fold regions: absolute file path -> header line indices (0-based). */
    private Map<String, List<Integer>> foldedRegions = new LinkedHashMap<>();
    /** Persisted Markdown view mode per file: absolute path -> "EDITOR"|"SPLIT"|"PREVIEW". */
    private Map<String, String> markdownViewModes = new LinkedHashMap<>();
    /** Files the user pinned read-only ("View mode"): absolute paths. */
    private List<String> readOnlyFiles = new ArrayList<>();

    // --- Zen (distraction-free) mode. Entering Zen snapshots the user's view/chrome prefs into
    //     preZenView (key -> value) and the open tool windows into preZenToolWindows, then turns
    //     those prefs off — so while in Zen the normal toggles can re-enable individual items, and
    //     leaving Zen restores the snapshot exactly. ---
    private boolean zenMode;
    private List<String> preZenToolWindows = new ArrayList<>();
    private Map<String, Boolean> preZenView = new LinkedHashMap<>();

    /** Files open at last exit, in tab order. */
    private List<OpenFile> openFiles = new ArrayList<>();
    /** Absolute path of the tab that was active at last exit ("" if none/untitled). */
    private String activeFile = "";

    // --- Main window bounds (0 width/height = "unset", use defaults). When maximized, the bounds
    //     hold the last non-maximized geometry so un-maximizing restores a sensible size. ---
    private double windowX;
    private double windowY;
    private double windowWidth;
    private double windowHeight;
    private boolean windowMaximized;

    /** One persisted open file: its absolute path, the caret offset to restore, and whether it was pinned. */
    public static class OpenFile {
        private String path = "";
        private int caret;
        private boolean pinned;

        public OpenFile() {
        }

        public OpenFile(String path, int caret, boolean pinned) {
            this.path = path;
            this.caret = caret;
            this.pinned = pinned;
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

    public Map<String, String> getMarkdownViewModes() {
        return markdownViewModes;
    }

    public void setMarkdownViewModes(Map<String, String> markdownViewModes) {
        this.markdownViewModes = markdownViewModes == null ? new LinkedHashMap<>() : markdownViewModes;
    }

    public List<String> getReadOnlyFiles() {
        return readOnlyFiles;
    }

    public void setReadOnlyFiles(List<String> readOnlyFiles) {
        this.readOnlyFiles = readOnlyFiles == null ? new ArrayList<>() : readOnlyFiles;
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

    public Map<String, Boolean> getPreZenView() {
        return preZenView;
    }

    public void setPreZenView(Map<String, Boolean> preZenView) {
        this.preZenView = preZenView == null ? new LinkedHashMap<>() : preZenView;
    }

    public List<OpenFile> getOpenFiles() {
        return openFiles;
    }

    public void setOpenFiles(List<OpenFile> openFiles) {
        this.openFiles = openFiles == null ? new ArrayList<>() : openFiles;
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
