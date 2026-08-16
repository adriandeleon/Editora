package com.editora.maven;

/**
 * The Advanced-section answers from the New Maven Project dialog: things the archetype bakes into its pom
 * template rather than accepting as a property, so they can only be applied <em>after</em> generation.
 *
 * <p>{@code archetype:generate} takes the archetype coordinates plus groupId/artifactId/version/package and
 * nothing else (see {@link ArchetypeGenerate#argv}); quickstart writes its {@code <url>} and
 * {@code maven.compiler.release} straight into the template. Passing {@code -Durl=…} would be silently
 * ignored, so these are a post-generation pom edit instead.
 *
 * @param url the project's {@code <url>}, or blank to keep the archetype's
 * @param javaRelease the {@code maven.compiler.release} property, or blank to keep the archetype's
 * @param updateVersions whether to bring dependencies and plugins up to their latest stable versions
 */
public record MavenProjectExtras(String url, String javaRelease, boolean updateVersions) {

    /** Change nothing — what the dialog produces when the Advanced section is left alone. */
    public static final MavenProjectExtras NONE = new MavenProjectExtras("", "", false);

    public MavenProjectExtras {
        url = url == null ? "" : url.strip();
        javaRelease = javaRelease == null ? "" : javaRelease.strip();
    }

    /** Whether anything here needs the generated pom rewritten in place. */
    public boolean editsPom() {
        return !url.isBlank() || !javaRelease.isBlank();
    }

    /** Whether anything here needs work at all beyond plain generation. */
    public boolean isNoop() {
        return !editsPom() && !updateVersions;
    }
}
