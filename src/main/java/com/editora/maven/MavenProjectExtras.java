package com.editora.maven;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Advanced-section answers from the New Maven Project dialog.
 *
 * <p>Two kinds. {@code properties} are what the archetype itself asks for ({@link ArchetypeDescriptor}) and
 * are passed as {@code -Dkey=value}; {@code url} and {@code javaRelease} are things quickstart bakes into
 * its pom template rather than (for the URL) accepting as a property, so they are a post-generation pom
 * edit — except that an archetype which declares {@link ArchetypeDescriptor#JAVA_RELEASE_KEY} gets the
 * release as a property too, and the edit is then unnecessary (see {@link #editsPom}).
 *
 * @param url the project's {@code <url>}, or blank to keep the archetype's
 * @param javaRelease the {@code maven.compiler.release} property, or blank to keep the archetype's
 * @param updateVersions whether to bring dependencies and plugins up to their latest stable versions
 * @param properties archetype properties to pass, key → value; a blank value is not passed (the archetype's
 *     default applies)
 */
public record MavenProjectExtras(
        String url, String javaRelease, boolean updateVersions, Map<String, String> properties) {

    /** Change nothing — what the dialog produces when the Advanced section is left alone. */
    public static final MavenProjectExtras NONE = new MavenProjectExtras("", "", false);

    public MavenProjectExtras(String url, String javaRelease, boolean updateVersions) {
        this(url, javaRelease, updateVersions, Map.of());
    }

    public MavenProjectExtras {
        url = url == null ? "" : url.strip();
        javaRelease = javaRelease == null ? "" : javaRelease.strip();
        Map<String, String> kept = new LinkedHashMap<>();
        if (properties != null) {
            properties.forEach((k, v) -> {
                if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                    kept.put(k.strip(), v.strip());
                }
            });
        }
        properties = Map.copyOf(kept);
    }

    /** Whether anything here needs the generated pom rewritten in place. */
    public boolean editsPom() {
        return !url.isBlank() || !javaRelease.isBlank();
    }

    /** Whether anything here needs work at all beyond plain generation. */
    public boolean isNoop() {
        return !editsPom() && !updateVersions && properties.isEmpty();
    }

    /**
     * The {@code -D} properties for the command line: the archetype's own, plus the Java release under
     * {@link ArchetypeDescriptor#JAVA_RELEASE_KEY} when the archetype takes it that way. In that case the
     * pom edit for the release is skipped — {@link #forArchetype} returns the extras with it blanked — so
     * the release is set once, by the archetype, where it belongs.
     */
    public MavenProjectExtras forArchetype(ArchetypeDescriptor descriptor) {
        if (descriptor == null || !descriptor.takesJavaRelease() || javaRelease.isBlank()) {
            return this;
        }
        Map<String, String> all = new LinkedHashMap<>(properties);
        all.put(ArchetypeDescriptor.JAVA_RELEASE_KEY, javaRelease);
        return new MavenProjectExtras(url, "", updateVersions, all);
    }
}
