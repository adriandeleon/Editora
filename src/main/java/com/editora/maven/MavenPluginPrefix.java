package com.editora.maven;

/**
 * Derives the short goal-prefix Maven uses on the CLI for a plugin (e.g. {@code mvn spotless:check}) from
 * its groupId/artifactId, following Maven's own naming convention: an artifactId of the form
 * {@code maven-<name>-plugin} (the Apache convention) or {@code <name>-maven-plugin} (the third-party
 * convention) yields the prefix {@code <name>}. An artifactId following neither convention (e.g. a custom
 * plugin with an arbitrary name) falls back to the bare artifactId.
 */
public final class MavenPluginPrefix {

    private static final String MAVEN_DASH = "maven-";
    private static final String DASH_PLUGIN = "-plugin";
    private static final String DASH_MAVEN_DASH_PLUGIN = "-maven-plugin";

    private MavenPluginPrefix() {}

    public static String derive(String groupId, String artifactId) {
        if (artifactId == null) {
            return "";
        }
        if (artifactId.endsWith(DASH_MAVEN_DASH_PLUGIN)) {
            return artifactId.substring(0, artifactId.length() - DASH_MAVEN_DASH_PLUGIN.length());
        }
        if (artifactId.startsWith(MAVEN_DASH) && artifactId.endsWith(DASH_PLUGIN)) {
            return artifactId.substring(MAVEN_DASH.length(), artifactId.length() - DASH_PLUGIN.length());
        }
        return artifactId;
    }
}
