package org.editora.plugin;

import javafx.scene.control.MenuBar;
import javafx.scene.control.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Manages the loading, initialization, and lifecycle of plugins.
 */
public class PluginManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);
    private final List<Plugin> loadedPlugins = new ArrayList<>();
    private final File pluginDirectory;
    private TextArea textArea;
    private MenuBar menuBar;
    
    public PluginManager(String pluginDirectoryPath) {
        this.pluginDirectory = new File(pluginDirectoryPath);
        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs();
            logger.info("Created plugin directory: {}", pluginDirectory.getAbsolutePath());
        }
    }
    
    /**
     * Sets the text area reference for plugins.
     * @param textArea The main editor text area
     */
    public void setTextArea(TextArea textArea) {
        this.textArea = textArea;
    }
    
    /**
     * Sets the menu bar reference for adding plugin menus.
     * @param menuBar The main menu bar
     */
    public void setMenuBar(MenuBar menuBar) {
        this.menuBar = menuBar;
    }
    
    /**
     * Loads all plugins from the plugin directory.
     */
    public void loadPlugins() {
        if (textArea == null) {
            logger.error("TextArea not set. Call setTextArea() before loading plugins.");
            return;
        }
        
        File[] jarFiles = pluginDirectory.listFiles((dir, name) -> name.endsWith(".jar"));
        
        if (jarFiles == null || jarFiles.length == 0) {
            logger.info("No plugin JAR files found in {}", pluginDirectory.getAbsolutePath());
            return;
        }
        
        for (File jarFile : jarFiles) {
            try {
                loadPlugin(jarFile);
            } catch (Exception e) {
                logger.error("Failed to load plugin from {}", jarFile.getName(), e);
            }
        }
        
        logger.info("Loaded {} plugin(s)", loadedPlugins.size());
    }
    
    /**
     * Loads a single plugin from a JAR file.
     * @param jarFile The JAR file containing the plugin
     */
    private void loadPlugin(File jarFile) throws Exception {
        logger.info("Loading plugin from: {}", jarFile.getName());
        
        // Create class loader for the plugin JAR
        URL[] urls = { jarFile.toURI().toURL() };
        URLClassLoader classLoader = new URLClassLoader(urls, getClass().getClassLoader());
        
        // Look for classes implementing Plugin interface
        try (JarFile jar = new JarFile(jarFile)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().endsWith(".class")) {
                    String className = entry.getName()
                        .replace('/', '.')
                        .replace(".class", "");
                    
                    try {
                        Class<?> clazz = classLoader.loadClass(className);
                        if (Plugin.class.isAssignableFrom(clazz) && !clazz.isInterface()) {
                            Plugin plugin = (Plugin) clazz.getDeclaredConstructor().newInstance();
                            plugin.initialize(textArea);
                            loadedPlugins.add(plugin);
                            
                            // Add plugin menu if available
                            if (menuBar != null && plugin.getMenu() != null) {
                                menuBar.getMenus().add(plugin.getMenu());
                            }
                            
                            logger.info("Successfully loaded plugin: {} v{}", 
                                plugin.getName(), plugin.getVersion());
                        }
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        // Skip classes that can't be loaded
                    }
                }
            }
        }
    }
    
    /**
     * Gets all loaded plugins.
     * @return Unmodifiable list of loaded plugins
     */
    public List<Plugin> getLoadedPlugins() {
        return Collections.unmodifiableList(loadedPlugins);
    }
    
    /**
     * Shuts down all loaded plugins.
     */
    public void shutdownPlugins() {
        logger.info("Shutting down {} plugin(s)", loadedPlugins.size());
        for (Plugin plugin : loadedPlugins) {
            try {
                plugin.shutdown();
                logger.info("Shutdown plugin: {}", plugin.getName());
            } catch (Exception e) {
                logger.error("Error shutting down plugin: {}", plugin.getName(), e);
            }
        }
        loadedPlugins.clear();
    }
}
