package com.editora.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The persisted set of <b>trusted workspace roots</b> (in {@code trusted-folders.json}) — folders whose
 * repo-shipped build wrapper ({@code ./mvnw}, {@code ./gradlew}) the user has agreed may execute with their
 * privileges. A folder is <b>untrusted by default</b>: only paths explicitly recorded {@code true} here are
 * trusted, so a fresh install, a corrupt file (which
 * {@link com.editora.config.migration.ConfigMigrations#readVersioned} fails open to defaults on), and a repo
 * cloned five minutes ago all land on the safe answer. Schema-versioned via
 * {@link com.editora.config.migration.ConfigSchema#TRUST}.
 *
 * <p>Trust is <b>inherited by subfolders</b>, the VS Code semantic: trusting {@code ~/src/app} covers the
 * Gradle build root at {@code ~/src/app/server}, so a multi-module repo asks once rather than once per
 * module. Containment is decided on {@link Path} components, never on the key strings — {@code ~/src/app2}
 * merely <em>starts with</em> the string {@code ~/src/app} and must not inherit its trust.
 *
 * <p>Keys are canonical (symlink-resolved) absolute paths, so the same folder reached through a symlink is
 * one entry rather than two, and a symlink cannot be used to present an untrusted folder under a trusted
 * name.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrustStore {

    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;

    /** Canonical absolute folder path → trusted. An absent path ⇒ untrusted. */
    public Map<String, Boolean> roots = new LinkedHashMap<>();

    /**
     * Whether {@code root} may run a repo-shipped wrapper: true when it, or any ancestor of it, is explicitly
     * trusted. A null path is never trusted.
     */
    public boolean isTrusted(Path root) {
        if (root == null) {
            return false;
        }
        Path key = PathKeys.canonical(root);
        for (Map.Entry<String, Boolean> e : roots.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) {
                continue;
            }
            Path trusted = parse(e.getKey());
            if (trusted != null && key.startsWith(trusted)) {
                return true;
            }
        }
        return false;
    }

    /** Records {@code root} (canonicalized) as trusted. */
    public void trust(Path root) {
        if (root != null) {
            roots.put(PathKeys.canonical(root).toString(), true);
        }
    }

    /** Drops an explicit entry for {@code root}. Note an ancestor's trust, if any, still covers it. */
    public void revoke(Path root) {
        if (root != null) {
            roots.remove(PathKeys.canonical(root).toString());
        }
    }

    /** Drops every entry. */
    public void revokeAll() {
        roots.clear();
    }

    /** The explicitly-trusted paths, in the order they were trusted — for the Settings list. */
    public List<String> trustedRoots() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : roots.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static Path parse(String key) {
        try {
            return key == null || key.isBlank() ? null : Path.of(key);
        } catch (InvalidPathException e) {
            return null; // a key written by another OS — never matches, never throws
        }
    }
}
