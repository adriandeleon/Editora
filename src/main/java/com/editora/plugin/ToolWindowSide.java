package com.editora.plugin;

/**
 * Where a plugin's tool window docks. Maps 1:1 to {@code ui.ToolWindow.Side}, but lives in the plugin API
 * package so a plugin never needs to depend on {@code com.editora.ui}.
 */
public enum ToolWindowSide {
    LEFT, RIGHT, BOTTOM
}
