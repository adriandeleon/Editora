package com.editora.plugin;

import java.nio.file.Path;

/**
 * A discovered plugin: its {@link PluginManifest}, install directory, enabled state, the class loader used
 * for its Java code (null for declarative-only or disabled plugins), and a non-null {@code loadError} when
 * discovery/loading failed (shown in Settings; the plugin is otherwise skipped). One bad plugin never
 * blocks the others.
 */
public final class PluginDescriptor {

    private final PluginManifest manifest;
    private final Path dir;
    private final boolean enabled;
    private final ClassLoader classLoader;
    private final String loadError;

    public PluginDescriptor(
            PluginManifest manifest, Path dir, boolean enabled, ClassLoader classLoader, String loadError) {
        this.manifest = manifest;
        this.dir = dir;
        this.enabled = enabled;
        this.classLoader = classLoader;
        this.loadError = loadError;
    }

    public PluginManifest manifest() {
        return manifest;
    }

    public String id() {
        return manifest.id;
    }

    public Path dir() {
        return dir;
    }

    public boolean enabled() {
        return enabled;
    }

    /** The plugin's class loader (parent = the app loader), or null when declarative-only / disabled. */
    public ClassLoader classLoader() {
        return classLoader;
    }

    /** A human-readable error when the plugin failed to load, else null. */
    public String loadError() {
        return loadError;
    }

    public boolean hasJavaEntry() {
        return manifest.main != null && !manifest.main.isBlank();
    }
}
