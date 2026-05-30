package com.editora.config;

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
    /** Persisted collapsed fold regions: absolute file path -> header line indices (0-based). */
    private Map<String, List<Integer>> foldedRegions = new LinkedHashMap<>();

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

    public Map<String, List<Integer>> getFoldedRegions() {
        return foldedRegions;
    }

    public void setFoldedRegions(Map<String, List<Integer>> foldedRegions) {
        this.foldedRegions = foldedRegions == null ? new LinkedHashMap<>() : foldedRegions;
    }
}
