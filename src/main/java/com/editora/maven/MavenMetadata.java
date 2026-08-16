package com.editora.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.editora.plugin.PluginInstaller;

/**
 * Reads the latest usable version out of a repository's {@code maven-metadata.xml}.
 *
 * <p>Metadata rather than the Central search API: it is the canonical file the repository itself publishes,
 * needs no API key and has no rate limit, and it is a plain GET at a path derived from the coordinates.
 *
 * <p><b>The {@code <release>} element is deliberately not trusted.</b> It means "the newest non-snapshot
 * that was published", which for several Apache plugins has at times been a milestone — maven-surefire-plugin
 * published {@code 3.0.0-M<n>} as its release for years. Offering a milestone as "the latest version" to a
 * brand-new project is worse than offering nothing, so the full version list is read and filtered instead.
 */
public final class MavenMetadata {

    private MavenMetadata() {}

    /** Bits that mark a version as not-for-general-use, matched case-insensitively. */
    private static final String[] UNSTABLE = {
        "snapshot", "alpha", "beta", "-rc", ".rc", "-cr", ".cr", "-m", "preview", "-ea", "dev", "incubat"
    };

    /**
     * The highest stable version in the metadata, or null when there is none.
     *
     * <p>Parsed by scanning for {@code <version>} elements rather than through a DOM: the file is a flat
     * list, this avoids standing up a parser (and its XXE hardening) for it, and a malformed or truncated
     * download degrades to "no answer" rather than an exception.
     */
    public static String latestStable(String metadataXml) {
        String best = null;
        for (String v : versions(metadataXml)) {
            if (isStable(v) && (best == null || PluginInstaller.compareVersions(v, best) > 0)) {
                best = v;
            }
        }
        return best;
    }

    /** Every {@code <version>} listed, in document order. */
    static List<String> versions(String metadataXml) {
        List<String> out = new ArrayList<>();
        if (metadataXml == null) {
            return out;
        }
        int i = 0;
        while (true) {
            int open = metadataXml.indexOf("<version>", i);
            if (open < 0) {
                break;
            }
            int close = metadataXml.indexOf("</version>", open);
            if (close < 0) {
                break;
            }
            String v = metadataXml.substring(open + "<version>".length(), close).trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
            i = close + "</version>".length();
        }
        return out;
    }

    /**
     * Whether a version is one to hand somebody without asking.
     *
     * <p>Conservative by design: a false negative costs an update that could have happened, a false positive
     * puts a milestone into a new project's pom. {@code "-m"} is matched with its dash so a version like
     * {@code 3.5.2} or a plain {@code 1.0m} shape cannot be caught by an unanchored "m".
     */
    public static boolean isStable(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String v = version.toLowerCase(Locale.ROOT);
        for (String bad : UNSTABLE) {
            if (v.contains(bad)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code maven-metadata.xml} path for an artifact, relative to a repository root.
     *
     * <p>Group dots become path segments — the layout every Maven repository uses.
     */
    public static String metadataPath(String groupId, String artifactId) {
        if (groupId == null || artifactId == null || groupId.isBlank() || artifactId.isBlank()) {
            throw new IllegalArgumentException("groupId and artifactId are required");
        }
        return groupId.strip().replace('.', '/') + "/" + artifactId.strip() + "/maven-metadata.xml";
    }
}
