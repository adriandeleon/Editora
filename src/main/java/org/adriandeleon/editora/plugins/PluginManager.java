package org.adriandeleon.editora.plugins;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public final class PluginManager {
    private final Path pluginsDirectory;
    private final List<EditoraPlugin> loadedPlugins = new ArrayList<>();
    private EditoraContext activeContext;
    private URLClassLoader activeClassLoader;

    public PluginManager(Path pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory;
    }

    public void loadPlugins(EditoraContext context) {
        unloadPlugins();
        loadedPlugins.clear();
        activeContext = context;

        if (Files.notExists(pluginsDirectory)) {
            return;
        }

        try {
            List<URL> pluginJars;
            try (var stream = Files.list(pluginsDirectory)) {
                pluginJars = stream
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .map(this::toUrl)
                        .toList();
            }

            if (pluginJars.isEmpty()) {
                return;
            }

            URLClassLoader classLoader = new URLClassLoader(pluginJars.toArray(URL[]::new), getClass().getClassLoader());
            activeClassLoader = classLoader;
            ServiceLoader<EditoraPlugin> loader = ServiceLoader.load(EditoraPlugin.class, classLoader);
            for (EditoraPlugin plugin : loader) {
                try {
                    plugin.onLoad(context);
                    loadedPlugins.add(plugin);
                } catch (Throwable ignored) {
                    // Individual plugins should never prevent the editor from starting.
                }
            }
        } catch (IOException ignored) {
            // Initial bootstrap keeps plugin loading fail-soft so the editor still opens.
        }
    }

    public void unloadPlugins() {
        if (activeContext != null) {
            for (EditoraPlugin plugin : List.copyOf(loadedPlugins)) {
                try {
                    plugin.onUnload(activeContext);
                } catch (Throwable ignored) {
                    // Plugin unload is also fail-soft.
                }
            }
        }

        loadedPlugins.clear();

        if (activeClassLoader != null) {
            try {
                activeClassLoader.close();
            } catch (IOException ignored) {
                // Ignore close problems during fail-soft plugin reloads.
            }
            activeClassLoader = null;
        }
    }

    public Path getPluginsDirectory() {
        return pluginsDirectory;
    }

    public List<EditoraPlugin> getLoadedPlugins() {
        return List.copyOf(loadedPlugins);
    }

    private URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not convert plugin path to URL: " + path, exception);
        }
    }
}

