package com.editora.toolchain;

import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * One installed JDK, Maven or Gradle that a project can be matched to.
 *
 * @param tool {@code jdk}, {@code maven} or {@code gradle}
 * @param version as its installer names it: jkit's {@code 21.0.9+10}, SDKMAN's {@code 21.0.9}, a
 *     {@code release} file's {@code 21.0.9}
 * @param vendor jkit's vendor name ({@code temurin}, {@code zulu}, {@code corretto}, …); null for Maven and
 *     Gradle, and for a JDK whose vendor is not known
 * @param fx whether the JDK bundles JavaFX
 * @param home the folder to use as {@code JAVA_HOME}, {@code MAVEN_HOME} or {@code GRADLE_HOME}
 * @param source who installed it: {@link #JKIT}, {@link #SDKMAN} or {@link #OTHER}
 * @param id the installer's own name for it: jkit's folder ({@code 21.0.9+10-temurin}), SDKMAN's identifier
 *     ({@code 21.0.9-tem}), else the folder name
 */
public record ToolInstall(
        String tool, String version, @Nullable String vendor, boolean fx, Path home, String source, String id) {

    public static final String JDK = "jdk";
    public static final String MAVEN = "maven";
    public static final String GRADLE = "gradle";

    public static final String JKIT = "jkit";
    public static final String SDKMAN = "sdkman";
    public static final String OTHER = "other";

    /** The feature release: 21 for {@code 21.0.9+10}, 8 for {@code 1.8.0_472}, 3 for Maven 3.9.11; 0 unknown. */
    public int major() {
        return major(tool, version);
    }

    static int major(String tool, String version) {
        String[] parts = version.split("[^0-9]+", -1);
        int first = parts.length > 0 ? parse(parts[0]) : 0;
        if (JDK.equals(tool) && first == 1 && parts.length > 1) {
            return parse(parts[1]);
        }
        return first;
    }

    private static int parse(String s) {
        try {
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** {@code JDK 21.0.9+10 · temurin · jkit}, for a picker row. */
    public String label() {
        StringBuilder sb = new StringBuilder(JDK.equals(tool) ? "JDK" : MAVEN.equals(tool) ? "Maven" : "Gradle");
        sb.append(' ').append(version);
        if (vendor != null) {
            sb.append(" · ").append(vendor);
        }
        if (fx) {
            sb.append(" · JavaFX");
        }
        return sb.toString();
    }
}
