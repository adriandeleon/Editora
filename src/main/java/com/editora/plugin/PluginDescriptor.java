package com.editora.plugin;

import java.nio.file.Path;
import java.util.List;

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
    private final LoadProblem problem;

    /**
     * Why a plugin failed to load, as a kind and its values, so the UI can say it in the user's language.
     * {@code detail} is the underlying message (a parser's or the JVM's, in English), for a tooltip or a log.
     */
    public record LoadProblem(Kind kind, List<String> args, String detail) {

        public enum Kind {
            /** {@code plugin.json} is not valid JSON or not a manifest; args: line, column (blank when unknown). */
            MANIFEST_INVALID,
            /** {@code plugin.json} could not be read from disk. */
            MANIFEST_UNREADABLE,
            /** The manifest's id is not a safe folder-like name, or a reserved one; args: the id. */
            ID_NOT_ALLOWED,
            /** The plugin's Java code could not be loaded. */
            CODE_NOT_LOADED
        }

        public LoadProblem {
            args = args == null ? List.of() : List.copyOf(args);
            detail = detail == null ? "" : detail;
        }

        /** The English text kept for logs and older callers. */
        String english() {
            return switch (kind) {
                case MANIFEST_INVALID, MANIFEST_UNREADABLE -> "Invalid plugin.json: " + detail;
                case ID_NOT_ALLOWED -> "Invalid plugin id in plugin.json: \"" + args.getFirst() + "\"";
                case CODE_NOT_LOADED -> "Failed to build class loader: " + detail;
            };
        }
    }

    public PluginDescriptor(
            PluginManifest manifest, Path dir, boolean enabled, ClassLoader classLoader, String loadError) {
        this(manifest, dir, enabled, classLoader, loadError, null);
    }

    /** A descriptor whose load failed for {@code problem} (null: it did not fail). */
    public static PluginDescriptor withProblem(
            PluginManifest manifest, Path dir, boolean enabled, ClassLoader classLoader, LoadProblem problem) {
        return new PluginDescriptor(
                manifest, dir, enabled, classLoader, problem == null ? null : problem.english(), problem);
    }

    private PluginDescriptor(
            PluginManifest manifest,
            Path dir,
            boolean enabled,
            ClassLoader classLoader,
            String loadError,
            LoadProblem problem) {
        this.manifest = manifest;
        this.dir = dir;
        this.enabled = enabled;
        this.classLoader = classLoader;
        this.loadError = loadError;
        this.problem = problem;
    }

    /** Why it failed to load, when it did and the reason is known by kind; else null. */
    public LoadProblem problem() {
        return problem;
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
