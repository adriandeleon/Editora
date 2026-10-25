package com.editora.install;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Which jdtls a folder holds, read from its core bundle's name ({@code plugins/org.eclipse.jdt.ls.core_
 * 1.61.0.202607301809.jar}), and whether it is older than the one Editora pins ({@link InstallCatalog#JDTLS_VERSION}).
 *
 * <p>An install is idempotent on the folder, not on the version: a user who installed jdtls before the pin was
 * bumped kept that server for good — a pre-release 1.61 snapshot here — while Editora's jdtls behaviour is
 * measured against the pinned build (Java review D8). Doctor names the version, and an older install of
 * Editora's own counts as missing, so Install replaces it.
 */
public record JdtlsVersion(int major, int minor, int patch, long build) implements Comparable<JdtlsVersion> {

    private static final Pattern CORE =
            Pattern.compile("org\\.eclipse\\.jdt\\.ls\\.core_(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)\\.jar");

    /** The version a core bundle's file name gives, or null for any other name. Pure. */
    public static @Nullable JdtlsVersion ofBundle(String fileName) {
        Matcher m = CORE.matcher(fileName == null ? "" : fileName);
        if (!m.matches() || m.group(4).length() > 18) {
            return null;
        }
        return new JdtlsVersion(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Long.parseLong(m.group(4)));
    }

    /** The pinned version ({@link InstallCatalog#JDTLS_VERSION}, {@link InstallCatalog#JDTLS_BUILD}). */
    public static JdtlsVersion pinned() {
        String[] v = InstallCatalog.JDTLS_VERSION.split("\\.");
        return new JdtlsVersion(
                Integer.parseInt(v[0]),
                Integer.parseInt(v[1]),
                Integer.parseInt(v[2]),
                Long.parseLong(InstallCatalog.JDTLS_BUILD));
    }

    /**
     * The jdtls installed at {@code home} (the folder holding {@code bin/} and {@code plugins/}), or at the home
     * of the launcher {@code home} names ({@code …/bin/jdtls}, symlinks followed). Null when it cannot be told.
     */
    public static @Nullable JdtlsVersion installed(Path home) {
        try {
            Path dir = home.toRealPath();
            if (Files.isRegularFile(dir)) {
                Path bin = dir.getParent();
                dir = bin == null ? dir : bin.getParent();
            }
            if (dir == null || !Files.isDirectory(dir.resolve("plugins"))) {
                return null;
            }
            try (Stream<Path> plugins = Files.list(dir.resolve("plugins"))) {
                return plugins.map(p -> ofBundle(p.getFileName().toString()))
                        .filter(java.util.Objects::nonNull)
                        .max(JdtlsVersion::compareTo)
                        .orElse(null);
            }
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public int compareTo(JdtlsVersion o) {
        int c = Integer.compare(major, o.major);
        c = c != 0 ? c : Integer.compare(minor, o.minor);
        c = c != 0 ? c : Integer.compare(patch, o.patch);
        return c != 0 ? c : Long.compare(build, o.build);
    }

    /** {@code 1.61.0 (2026-07-30 build)}. */
    public String label() {
        String b = Long.toString(build);
        String date = b.length() >= 8 ? b.substring(0, 4) + "-" + b.substring(4, 6) + "-" + b.substring(6, 8) : b;
        return major + "." + minor + "." + patch + " (" + date + " build)";
    }
}
