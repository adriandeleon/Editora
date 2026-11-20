package com.editora.toolchain;

import org.jspecify.annotations.Nullable;

/**
 * SDKMAN's Java identifiers: {@code <version>[.fx|.crac]-<vendor suffix>}, as in {@code 21.0.9-tem},
 * {@code 21.0.9.fx-zulu}, {@code 22.3.r17-grl}. Pure.
 */
public final class SdkmanIds {

    private SdkmanIds() {}

    /** An identifier read: the version without its {@code .fx} mark, jkit's vendor, and JavaFX or not. */
    public record Parsed(String version, @Nullable String vendor, boolean fx) {}

    /**
     * {@code id} read, or null when it is not {@code <version>-<suffix>} with a suffix Editora knows. A suffix
     * Editora does not know still yields its version, with a null vendor, so the version alone can match.
     */
    public static @Nullable Parsed parse(String id) {
        String s = id.strip();
        int dash = s.lastIndexOf('-');
        if (dash <= 0 || dash == s.length() - 1 || !Character.isDigit(s.charAt(0))) {
            return null;
        }
        String version = s.substring(0, dash);
        String suffix = s.substring(dash + 1);
        if (!suffix.chars().allMatch(Character::isLetterOrDigit)
                || suffix.chars().allMatch(Character::isDigit)
                || (Vendors.fromSdkman(suffix) == null
                        && suffix.toLowerCase(java.util.Locale.ROOT)
                                .matches("ea|rc\\d*|beta\\d*|alpha\\d*|m\\d+|snapshot"))) {
            return null; // 26-ea is a version, not a vendor
        }
        boolean fx = suffix.endsWith("fx") && Vendors.fromSdkman(suffix) != null && !suffix.equals("fx");
        if (version.endsWith(".fx")) {
            fx = true;
            version = version.substring(0, version.length() - 3);
        } else if (version.endsWith(".crac")) {
            version = version.substring(0, version.length() - 5);
        }
        return new Parsed(version, Vendors.fromSdkman(suffix), fx);
    }

    /**
     * SDKMAN's identifier for a JDK, or null when SDKMAN has no suffix for {@code vendor}: {@code 21.0.9-tem}
     * for jkit's {@code 21.0.9+10} temurin (SDKMAN writes no build number), {@code 21.0.9.fx-zulu} with JavaFX.
     */
    public static @Nullable String format(String version, @Nullable String vendor, boolean fx) {
        String suffix = Vendors.toSdkman(vendor);
        if (suffix == null) {
            return null;
        }
        int plus = version.indexOf('+');
        String v = plus > 0 ? version.substring(0, plus) : version;
        return v + (fx ? ".fx" : "") + "-" + suffix;
    }
}
