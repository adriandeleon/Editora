package com.editora.toolchain;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * What a project asks for one tool, as {@code .sdkmanrc} or {@code .editora/settings.json} writes it, and the
 * install that answers it. Accepted for a JDK:
 *
 * <ul>
 *   <li>an SDKMAN identifier: {@code 21.0.9-tem}, {@code 21.0.9.fx-zulu}
 *   <li>a jkit install id: {@code 21.0.9+10-temurin}, {@code 21.0.9+10-zulu-fx}, {@code 25.0.1+8-graalvm-ce}
 *   <li>a version or a prefix, any vendor: {@code 21}, {@code 21.0.9}
 *   <li>a folder: {@code /opt/jdk-21} (a personal choice; the other forms travel with the repository)
 * </ul>
 *
 * Maven and Gradle take a version or a prefix ({@code 3.9.11}, {@code 8}) or a folder. Pure.
 *
 * @param tool {@code jdk}, {@code maven} or {@code gradle}
 * @param raw as written
 * @param version the version or prefix asked for; empty when {@link #path} is set
 * @param vendor jkit's vendor name, or null for any
 * @param fx true/false when the request says, null when it does not
 * @param path a folder asked for by its path
 */
public record ToolRequest(
        String tool,
        String raw,
        String version,
        @Nullable String vendor,
        @Nullable Boolean fx,
        @Nullable Path path) {

    /** {@code raw} read for {@code tool}; null when it is blank. */
    public static @Nullable ToolRequest parse(String tool, @Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.strip();
        if (looksLikePath(s)) {
            String expanded = s.startsWith("~") ? System.getProperty("user.home", "~") + s.substring(1) : s;
            return new ToolRequest(tool, s, "", null, null, Path.of(expanded));
        }
        if (!ToolInstall.JDK.equals(tool) && !ToolInstall.GRAALVM.equals(tool)) {
            return new ToolRequest(tool, s, s, null, null, null);
        }
        // jkit's id: <version>-<vendor>[-fx], the vendor possibly dashed itself (graalvm-ce, liberica-nik)
        boolean fxMark = s.toLowerCase(Locale.ROOT).endsWith("-fx");
        String body = fxMark ? s.substring(0, s.length() - 3) : s;
        String jkitVendor = longestVendorSuffix(body);
        if (jkitVendor != null) {
            String version = body.substring(0, body.length() - jkitVendor.length() - 1);
            return new ToolRequest(tool, s, version, Vendors.normalize(jkitVendor), fxMark, null);
        }
        SdkmanIds.Parsed sdkman = SdkmanIds.parse(s);
        if (sdkman != null) {
            return new ToolRequest(tool, s, sdkman.version(), sdkman.vendor(), sdkman.fx(), null);
        }
        return new ToolRequest(tool, s, s, null, null, null);
    }

    /**
     * A pin as jkit reads it ({@code jkit status}): a version or prefix (null for the newest), a vendor, JavaFX.
     * A pin without {@code fx = true} does not ask for a JavaFX build, but takes one when that is all there is.
     */
    public static ToolRequest of(JkitCli.PinSpec pin) {
        String vendor = pin.vendor() == null || pin.vendor().isBlank() ? null : Vendors.normalize(pin.vendor());
        return new ToolRequest(
                pin.tool(),
                pin.label(),
                pin.version() == null ? "" : pin.version(),
                vendor,
                pin.fx() ? Boolean.TRUE : null,
                null);
    }

    private static boolean looksLikePath(String s) {
        return s.startsWith("/")
                || s.startsWith("~")
                || s.startsWith("\\\\")
                || (s.length() > 2 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':');
    }

    /** The jkit vendor name {@code body} ends with ({@code -temurin}, {@code -graalvm-ce}), longest first. */
    private static @Nullable String longestVendorSuffix(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        String best = null;
        for (String vendor : Vendors.SDKMAN.values()) {
            if (lower.endsWith("-" + vendor) && (best == null || vendor.length() > best.length())) {
                best = vendor;
            }
        }
        for (String extra : List.of("redhat", "openlogic", "zulu-prime", "adoptium")) {
            if (lower.endsWith("-" + extra) && (best == null || extra.length() > best.length())) {
                best = extra;
            }
        }
        if (best == null || body.length() <= best.length() + 1 || !Character.isDigit(body.charAt(0))) {
            return null;
        }
        return best;
    }

    /** Whether {@code install} answers this request (the path, or version, vendor and JavaFX). */
    public boolean matches(ToolInstall install) {
        boolean sameTool = ToolInstall.GRAALVM.equals(tool) ? install.graalvm() : tool.equals(install.tool());
        if (!sameTool) {
            return false;
        }
        if (path != null) {
            return same(path, install.home());
        }
        if (vendor != null && !vendor.equals(install.vendor())) {
            return false;
        }
        if (fx != null && fx != install.fx()) {
            return false;
        }
        return Versions.matches(install.version(), version);
    }

    private static boolean same(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (java.io.IOException | RuntimeException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    /**
     * The install that answers this request best, or null when none does: the one the request names exactly
     * (SDKMAN's identifier, jkit's id), then an exact version over a prefix, a plain build over a JavaFX one
     * when the request does not say, jkit's and SDKMAN's own over other installs, and the newest.
     */
    public @Nullable ToolInstall best(List<ToolInstall> installs) {
        Comparator<ToolInstall> order = Comparator.<ToolInstall>comparingInt(i -> i.id().equals(raw) ? 0 : 1)
                .thenComparingInt(i -> exactVersion(i) ? 0 : 1)
                .thenComparingInt(i -> fx == null && i.fx() ? 1 : 0)
                .thenComparingInt(i -> ToolInstall.OTHER.equals(i.source()) ? 1 : 0)
                .thenComparing(i -> Versions.key(i.version()), Comparator.reverseOrder());
        return installs.stream().filter(this::matches).min(order).orElse(null);
    }

    private boolean exactVersion(ToolInstall i) {
        String v = i.version();
        int plus = v.indexOf('+');
        return v.equals(version) || (plus > 0 && v.substring(0, plus).equals(version));
    }

    /** How jkit names this request: {@code jdk@21.0.9 temurin}, for a {@code jkit install} command. */
    public @Nullable List<String> jkitInstallArgs() {
        if (path != null) {
            return null;
        }
        if (version.isEmpty()) {
            List<String> latest = new java.util.ArrayList<>(List.of("install", tool + "@latest"));
            if (vendor != null) {
                latest.add(vendor);
            }
            return List.copyOf(latest);
        }
        List<String> args = new java.util.ArrayList<>(List.of("install", tool + "@" + version));
        if (vendor != null) {
            args.add(vendor);
        }
        if (Boolean.TRUE.equals(fx)) {
            args.add("--fx");
        }
        return List.copyOf(args);
    }
}
