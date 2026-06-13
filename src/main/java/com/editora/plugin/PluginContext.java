package com.editora.plugin;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.scene.layout.Region;

/**
 * The capability surface handed to a {@link Plugin#start(PluginContext)}. A fresh context is created
 * <em>per window</em> (Editora is multi-window), so commands and tool-window content nodes a plugin
 * registers belong to that window — a plugin's {@code start} runs once for each open window.
 *
 * <p>Everything registered here lives only for the session; toggling a plugin (or the master "Enable
 * plugins" setting) takes effect on the next app launch.
 */
public interface PluginContext {

    /** Registers a palette command (id is namespaced by the manager as {@code plugin.<pluginId>.<id>}). */
    void registerCommand(String id, String title, Runnable action);

    /** Binds a chord (e.g. {@code "C-c x"}) to a command id (a plugin's or a built-in's). */
    void bindKey(String chord, String commandId);

    /** Registers a dockable tool window with the given content node + a toggle command id. */
    void registerToolWindow(String id, String title, ToolWindowSide side, Region content, String commandId);

    /** Adds an item to the editor's right-click menu; the action receives the {@link ActiveEditor}. */
    void addEditorMenuItem(String label, Consumer<ActiveEditor> action);

    /** Adds a clickable status-bar segment that runs {@code commandId} when clicked. */
    void addStatusBarSegment(String label, String commandId);

    /** The active editor facade for this window. */
    ActiveEditor activeEditor();

    /** This plugin's install directory ({@code <configDir>/plugins/<id>/}). */
    Path pluginDir();

    /** A writable per-plugin data directory ({@code <configDir>/plugins/<id>/data/}), created on demand. */
    Path dataDir();

    /** Editora's config directory ({@code ~/.editora} or {@code ~/.editora-dev}). */
    Path configDir();

    /** Writes a message to the app's debug log (visible via View: Debug Log). */
    void log(String message);

    /** Shows a transient message in the status-bar echo area. */
    void setStatus(String message);
}
