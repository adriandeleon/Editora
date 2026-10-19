package com.editora.plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A discovered plugin: its {@link PluginManifest}, install directory, enabled state, the class loader used
 * for its Java code (null for declarative-only or disabled plugins, and for every plugin found after launch:
 * see {@link PluginManager#launchSet()}), and a non-null {@code loadError} when discovery/loading failed (shown
 * in Settings; the plugin is otherwise skipped). One bad plugin never blocks the others.
 *
 * <p>{@link #digest()} fingerprints what the consent dialog disclosed (main class, jars, commands, keymaps):
 * the user's consent is stored with it, and a plugin whose capabilities changed since is not loaded until it is
 * enabled again.
 */
public final class PluginDescriptor {

    private final PluginManifest manifest;
    private final Path dir;
    private final boolean enabled;
    private final ClassLoader classLoader;
    private final String loadError;
    private final LoadProblem problem;
    private final List<String> jars;
    private final String digest;

    /**
     * Why a plugin failed to load (or, for the runtime kinds, to start), as a kind and its values, so the UI can
     * say it in the user's language. {@code detail} is the underlying message (a parser's or the JVM's, in
     * English), for a tooltip or a log.
     */
    public record LoadProblem(Kind kind, List<String> args, String detail) {

        public enum Kind {
            /** {@code plugin.json} is not valid JSON or not a manifest; args: line, column (blank when unknown). */
            MANIFEST_INVALID,
            /** {@code plugin.json} could not be read from disk. */
            MANIFEST_UNREADABLE,
            /** The manifest's id is not a safe folder-like name, or a reserved one; args: the id. */
            ID_NOT_ALLOWED,
            /** The manifest's id is not the name of its folder; args: the declared id, the folder name. */
            ID_MISMATCH,
            /** The plugin's Java code could not be loaded. */
            CODE_NOT_LOADED,
            /** Runtime: the {@code main} class is missing from the plugin's jars; args: the class name. */
            MAIN_NOT_FOUND,
            /** Runtime: the {@code main} class is not a {@link Plugin} or cannot be built; args: the class name. */
            MAIN_NOT_A_PLUGIN,
            /** Runtime: its {@link Plugin#start} threw; its contributions were taken back. */
            START_FAILED,
            /** Runtime: its {@link Plugin#start} took long on the UI thread; args: milliseconds. A warning. */
            SLOW_START
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
                case ID_MISMATCH ->
                    "plugin.json says id \"" + args.get(0) + "\" but its folder is \"" + args.get(1) + "\"";
                case CODE_NOT_LOADED -> "Failed to build class loader: " + detail;
                case MAIN_NOT_FOUND -> "Main class not found: " + args.getFirst();
                case MAIN_NOT_A_PLUGIN -> "Main class is not a plugin: " + args.getFirst();
                case START_FAILED -> "start() failed: " + detail;
                case SLOW_START -> "start() took " + args.getFirst() + " ms";
            };
        }
    }

    public PluginDescriptor(
            PluginManifest manifest, Path dir, boolean enabled, ClassLoader classLoader, String loadError) {
        this(manifest, dir, enabled, classLoader, loadError, null, List.of());
    }

    /** A descriptor whose load failed for {@code problem} (null: it did not fail). */
    public static PluginDescriptor withProblem(
            PluginManifest manifest, Path dir, boolean enabled, ClassLoader classLoader, LoadProblem problem) {
        return withProblem(manifest, dir, enabled, classLoader, problem, List.of());
    }

    /** As above, with the plugin's jar files ({@code x.jar}, {@code lib/y.jar}, relative to its folder). */
    static PluginDescriptor withProblem(
            PluginManifest manifest,
            Path dir,
            boolean enabled,
            ClassLoader classLoader,
            LoadProblem problem,
            List<String> jars) {
        return new PluginDescriptor(
                manifest, dir, enabled, classLoader, problem == null ? null : problem.english(), problem, jars);
    }

    private PluginDescriptor(
            PluginManifest manifest,
            Path dir,
            boolean enabled,
            ClassLoader classLoader,
            String loadError,
            LoadProblem problem,
            List<String> jars) {
        this.manifest = manifest;
        this.dir = dir;
        this.enabled = enabled;
        this.classLoader = classLoader;
        this.loadError = loadError;
        this.problem = problem;
        this.jars = jars == null ? List.of() : List.copyOf(jars);
        this.digest = digest(manifest, this.jars);
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

    /** The name to show: the manifest's name, or the id when it has none. */
    public String displayName() {
        return manifest.name == null || manifest.name.isBlank() ? manifest.id : manifest.name.strip();
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

    /** The jar files its class loader reads, relative to its folder ({@code x.jar}, {@code lib/y.jar}). */
    public List<String> jars() {
        return jars;
    }

    /** The fingerprint of what enabling it discloses — see {@link #digest(PluginManifest, List)}. */
    public String digest() {
        return digest;
    }

    /**
     * A SHA-256 over what the consent dialog shows for a plugin: its {@code main} class, its jar names, each
     * declared command (id, title, argv, dir) and every keymap entry. The name, version and description are
     * left out — they disclose nothing the plugin can do — so a release that only bumps its version keeps the
     * consent, while one that adds code, a command or a chord is asked about again. Pure.
     */
    public static String digest(PluginManifest m, List<String> jars) {
        StringBuilder sb = new StringBuilder("editora-plugin-consent/1\n");
        field(sb, "main", m.main);
        List<String> sortedJars = new ArrayList<>(jars == null ? List.of() : jars);
        sortedJars.sort(null);
        for (String jar : sortedJars) {
            field(sb, "jar", jar);
        }
        if (m.commands != null) {
            for (PluginManifest.DeclaredCommand c : m.commands) {
                if (c == null) {
                    continue;
                }
                field(sb, "cmd", c.id);
                field(sb, "title", c.title);
                for (String arg : c.run == null ? List.<String>of() : c.run) {
                    field(sb, "arg", arg);
                }
                field(sb, "dir", c.dir);
            }
        }
        keymap(sb, "", m.keymap);
        if (m.keymaps != null) {
            new TreeMap<>(m.keymaps).forEach((name, chords) -> keymap(sb, name, chords));
        }
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void keymap(StringBuilder sb, String name, Map<String, String> chords) {
        if (chords == null) {
            return;
        }
        new TreeMap<>(chords).forEach((chord, command) -> {
            field(sb, "key", name);
            field(sb, "chord", chord);
            field(sb, "to", command);
        });
    }

    /** One length-prefixed field, so no value can be read as two (or as the next field). */
    private static void field(StringBuilder sb, String tag, String value) {
        String v = value == null ? "" : value;
        sb.append(tag).append(' ').append(v.length()).append(':').append(v).append('\n');
    }
}
