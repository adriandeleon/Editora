package com.editora.build;

import java.util.Locale;

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
    static String fromField(String field) {
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
}
