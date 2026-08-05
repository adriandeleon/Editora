package com.editora.maven;

/**
 * One Maven archetype: the coordinates {@code archetype:generate} needs, plus display text.
 *
 * <p>Instances come from two places — the bundled curated list ({@link ArchetypeCatalog}) and, on demand,
 * Maven Central's {@code archetype-catalog.xml} ({@link ArchetypeCatalogParser}). {@code curated} records
 * which, because a non-curated archetype is third-party code the user asked for by name and gets a consent
 * prompt before it runs.
 *
 * @param repository optional non-central repository the archetype lives in ({@code null}/blank for central)
 */
public record MavenArchetype(
        String groupId, String artifactId, String version, String description, String repository, boolean curated) {

    public MavenArchetype {
        groupId = groupId == null ? "" : groupId.strip();
        artifactId = artifactId == null ? "" : artifactId.strip();
        version = version == null ? "" : version.strip();
        description = description == null ? "" : description.strip();
        repository = repository == null ? "" : repository.strip();
    }

    /** {@code groupId:artifactId:version} — the form a user types for a custom archetype. */
    public String gav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    /** Identity for de-duplicating the curated list against a fetched catalog. */
    public String key() {
        return groupId + ":" + artifactId;
    }

    public boolean hasRepository() {
        return !repository.isEmpty();
    }
}
