package com.editora.build;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/**
 * Detects which Node package manager to invoke for a project, in the order npm/yarn/pnpm/bun tooling itself
 * recommends: the {@code package.json} {@code packageManager} field (corepack — authoritative) first, then the
 * lockfile on disk, else the default {@code npm}. Pure — the caller does the {@code Files.exists} lockfile
 * probes and reads the {@code packageManager} field (via {@link NpmProject}) and passes them in.
 */
public final class NpmPackageManager {

    private NpmPackageManager() {}

    /** The package-manager command (one of {@code npm}/{@code yarn}/{@code pnpm}/{@code bun}). */
    public static String detect(
            String packageManagerField, boolean npmLock, boolean yarnLock, boolean pnpmLock, boolean bunLock) {
        String fromField = fromField(packageManagerField);
        if (fromField != null) {
            return fromField;
        }
        if (pnpmLock) {
            return "pnpm";
        }
        if (yarnLock) {
            return "yarn";
        }
        if (bunLock) {
            return "bun";
        }
        return "npm"; // package-lock.json or no lockfile at all
    }

    /** The manager named by a {@code "pnpm@8.6.0"}-style {@code packageManager} field, or {@code null}. */
    static @Nullable String fromField(@Nullable String field) {
        if (field == null || field.isBlank()) {
            return null;
        }
        int at = field.indexOf('@');
        String name = (at > 0 ? field.substring(0, at) : field).strip().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "npm", "yarn", "pnpm", "bun" -> name;
            default -> null;
        };
    }

    /**
     * A detected manager and what said so.
     *
     * @param manager {@code npm}/{@code yarn}/{@code pnpm}/{@code bun}
     * @param reason the file that decided it ({@code pnpm-lock.yaml}, {@code package.json} for its
     *     {@code packageManager} field), or "" when nothing did (npm by default)
     * @param root the folder holding that file, or null
     */
    public record Detection(
            String manager, String reason, @Nullable Path root) {
        /** {@code pnpm (pnpm-lock.yaml)} — for Settings and Doctor. */
        public String describe() {
            return reason.isEmpty() ? manager : manager + " (" + reason + ")";
        }
    }

    /** How many folders above a package are searched for its workspace root's lockfile or field. */
    static final int MAX_LEVELS = 8;

    /**
     * Detects the manager for the package in {@code dir}, looking upward for the workspace root that decides it:
     * a member of a pnpm/yarn/bun/npm workspace has no lockfile or {@code packageManager} field of its own, and
     * deciding from the member alone ran {@code npm} (and offered {@code npm install}) inside a pnpm monorepo.
     * The nearest folder with a {@code packageManager} field, a lockfile or a {@code pnpm-workspace.yaml} wins;
     * the walk stops at {@code stop} (the project root, inclusive) and never reaches {@code home}. Pure apart
     * from the lookups given.
     */
    public static Detection detect(
            Path dir,
            @Nullable Path stop,
            Path home,
            Predicate<Path> exists,
            Function<Path, Optional<String>> readPackageJson) {
        Path top = stop == null ? null : stop.toAbsolutePath().normalize();
        Path homeDir = home == null ? null : home.toAbsolutePath().normalize();
        Path d = dir == null ? null : dir.toAbsolutePath().normalize();
        for (int level = 0; d != null && level < MAX_LEVELS; level++) {
            if (level > 0 && (d.equals(homeDir) || (top != null && !d.startsWith(top)))) {
                break;
            }
            Optional<String> json = readPackageJson.apply(d.resolve("package.json"));
            if (json.isPresent()) {
                String field = null;
                try {
                    field = NpmProject.parse(json.get()).packageManagerField();
                } catch (IllegalArgumentException malformed) {
                    // a broken package.json decides nothing; keep looking
                }
                String fromField = fromField(field);
                if (fromField != null) {
                    return new Detection(fromField, "package.json", d);
                }
            }
            for (String[] lock : LOCKFILES) {
                if (exists.test(d.resolve(lock[0]))) {
                    return new Detection(lock[1], lock[0], d);
                }
            }
            if (d.equals(top)) {
                break;
            }
            d = d.getParent();
        }
        return new Detection("npm", "", null);
    }

    /** Lockfile → manager, in the precedence {@link #detect(String, boolean, boolean, boolean, boolean)} uses. */
    private static final String[][] LOCKFILES = {
        {"pnpm-lock.yaml", "pnpm"},
        {"pnpm-workspace.yaml", "pnpm"},
        {"yarn.lock", "yarn"},
        {"bun.lock", "bun"},
        {"bun.lockb", "bun"},
        {"package-lock.json", "npm"},
        {"npm-shrinkwrap.json", "npm"}
    };

    /**
     * The argv prefix for {@code detection}: the user's command override when it is for that manager (its name is
     * the manager's, or it is not a package manager at all — a wrapper script), else the detected manager. An
     * override of {@code npm} used to make every pnpm and yarn project run npm.
     */
    public static boolean overrideApplies(String override, String manager) {
        if (override == null || override.isBlank()) {
            return false;
        }
        String first = override.strip().split("\\s+")[0].replace('\\', '/');
        String base = first.substring(first.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        base = base.replaceFirst("\\.(cmd|exe|bat)$", "");
        boolean isManager = base.equals("npm") || base.equals("yarn") || base.equals("pnpm") || base.equals("bun");
        return !isManager || base.equals(manager);
    }
}
