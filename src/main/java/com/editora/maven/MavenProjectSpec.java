package com.editora.maven;

import java.nio.file.Path;

/**
 * Everything the wizard collected: which archetype to generate from, the new project's coordinates, and the
 * folder to generate into. Pure — {@link #firstProblem()} is what gates the form's OK button.
 *
 * @param parentDir the directory Maven runs in; the project lands in {@code parentDir/artifactId}
 */
public record MavenProjectSpec(
        MavenArchetype archetype,
        String groupId,
        String artifactId,
        String version,
        String packageName,
        Path parentDir) {

    public MavenProjectSpec {
        groupId = groupId == null ? "" : groupId.strip();
        artifactId = artifactId == null ? "" : artifactId.strip();
        version = version == null ? "" : version.strip();
        packageName = packageName == null ? "" : packageName.strip();
    }

    /** Where the generated project will be, per {@code archetype:generate}'s own naming. */
    public Path projectDir() {
        return MavenCoordinates.projectDir(parentDir, artifactId);
    }

    /**
     * The first thing wrong with this spec as an i18n key suffix, or {@code null} when it is usable.
     * Returned as a key rather than a message so {@code maven/} stays free of the message catalog.
     */
    public String firstProblem() {
        if (archetype == null) {
            return "archetype";
        }
        if (!MavenCoordinates.isValidGroupId(groupId)) {
            return "groupId";
        }
        if (!MavenCoordinates.isValidArtifactId(artifactId)) {
            return "artifactId";
        }
        if (!MavenCoordinates.isValidVersion(version)) {
            return "version";
        }
        if (packageName.isEmpty()) {
            return "package";
        }
        if (parentDir == null) {
            return "location";
        }
        return null;
    }

    public boolean isValid() {
        return firstProblem() == null;
    }
}
