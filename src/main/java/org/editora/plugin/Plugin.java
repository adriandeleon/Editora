package org.editora.plugin;

import javafx.scene.control.Menu;
import javafx.scene.control.TextArea;

/**
 * Base interface for all editor plugins.
 * Plugins can extend the editor functionality by implementing this interface.
 */
public interface Plugin {
    
    /**
     * Gets the unique name of the plugin.
     * @return Plugin name
     */
    String getName();
    
    /**
     * Gets the version of the plugin.
     * @return Plugin version
     */
    String getVersion();
    
    /**
     * Gets the description of the plugin.
     * @return Plugin description
     */
    String getDescription();
    
    /**
     * Initializes the plugin with access to the text area.
     * Called when the plugin is loaded.
     * @param textArea The main editor text area
     */
    void initialize(TextArea textArea);
    
    /**
     * Returns the menu items this plugin wants to add to the editor.
     * @return Menu to be added to the editor's menu bar, or null if none
     */
    Menu getMenu();
    
    /**
     * Called when the plugin is being unloaded.
     * Use this to clean up resources.
     */
    void shutdown();
}
