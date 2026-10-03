package com.editora.process;

import java.util.Map;

/**
 * Test-only assertions about the environment a launcher hands its child. Shared by the per-launcher tests in
 * other packages; test sources only.
 */
public final class ChildEnv {

    private ChildEnv() {}

    /** The value of {@code PATH} in {@code env}, whatever its case (Windows spells it {@code Path}). */
    public static String path(Map<String, String> env) {
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("PATH")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Whether {@code env} is the <em>user-facing</em> child environment: the locale exactly as this JVM
     * inherited it — in particular no forced {@code LC_ALL=C} — plus Editora's augmented PATH.
     *
     * <p>Compared against this JVM's own environment rather than against "absent", so the check also holds
     * on a machine (or CI lane) that exports {@code LC_ALL} itself.
     */
    public static boolean inheritsUserLocale(Map<String, String> env) {
        return java.util.Objects.equals(System.getenv("LC_ALL"), env.get("LC_ALL"))
                && java.util.Objects.equals(System.getenv("LANG"), env.get("LANG"))
                && ProcessRunner.augmentedPath().equals(path(env));
    }
}
