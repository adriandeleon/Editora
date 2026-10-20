package com.editora.maven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * What an archetype asks for beyond the four coordinates: its {@code META-INF/maven/archetype-metadata.xml}
 * {@code <requiredProperties>}, each with the default {@code archetype:generate -B} would silently apply.
 *
 * <p>Read from the archetype jar in the local repository when it is there — it is after the first
 * generation from it, and for the curated entries usually long before, since {@code mvn} caches what it
 * downloads. The wizard shows the properties as form rows and passes them as {@code -Dkey=value}, so
 * {@code javafx-archetype-simple}'s {@code javafx-version} (default 13) is a choice rather than a surprise,
 * and a property with no default — which would fail batch mode with no explanation — is asked for up front.
 *
 * <p>Pure parse plus one bounded jar read; no network. An archetype not yet in the local repository has no
 * descriptor to show, and its defaults apply as they always did.
 */
public record ArchetypeDescriptor(List<RequiredProperty> requiredProperties) {

    private static final Logger LOG = Logger.getLogger(ArchetypeDescriptor.class.getName());

    private static final String ENTRY = "META-INF/maven/archetype-metadata.xml";

    private static final long MAX_DESCRIPTOR_BYTES = 1L * 1024 * 1024;

    /** The property quickstart (1.5) and its siblings take the Java release through. */
    public static final String JAVA_RELEASE_KEY = "javaCompilerVersion";

    /** Properties the wizard always passes itself; an archetype re-declaring one is not asked again. */
    private static final Set<String> RESERVED = Set.of("groupId", "artifactId", "version", "package");

    /** Nothing declared: every archetype that has no descriptor, or one without {@code <requiredProperties>}. */
    public static final ArchetypeDescriptor NONE = new ArchetypeDescriptor(List.of());

    /** One {@code <requiredProperty>}: its key and its {@code <defaultValue>} ({@code null} when it has none). */
    public record RequiredProperty(String key, @Nullable String defaultValue) {
        public boolean hasDefault() {
            return defaultValue != null;
        }
    }

    public ArchetypeDescriptor {
        requiredProperties = requiredProperties == null ? List.of() : List.copyOf(requiredProperties);
    }

    /** Whether the archetype takes the Java release as a property, so the wizard need not edit the pom for it. */
    public boolean takesJavaRelease() {
        return requiredProperties.stream().anyMatch(p -> JAVA_RELEASE_KEY.equals(p.key()));
    }

    /** The properties to show as rows: everything but the Java release, which has a row of its own. */
    public List<RequiredProperty> extraProperties() {
        return requiredProperties.stream()
                .filter(p -> !JAVA_RELEASE_KEY.equals(p.key()))
                .toList();
    }

    /** Parses a descriptor; a malformed one is {@link #NONE} rather than an error — the defaults then apply. */
    public static ArchetypeDescriptor parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return NONE;
        }
        try {
            Element root = PomParser.parseDocument(xml).getDocumentElement();
            List<RequiredProperty> out = new ArrayList<>();
            for (Element block : PomParser.childElements(root, "requiredProperties")) {
                for (Element property : PomParser.childElements(block, "requiredProperty")) {
                    String key = PomParser.strip(property.getAttribute("key"));
                    if (key == null || key.isEmpty() || RESERVED.contains(key)) {
                        continue;
                    }
                    List<Element> defaults = PomParser.childElements(property, "defaultValue");
                    String value = defaults.isEmpty()
                            ? null
                            : String.valueOf(defaults.get(0).getTextContent()).strip();
                    out.add(new RequiredProperty(key, value));
                }
            }
            return new ArchetypeDescriptor(out);
        } catch (PomParseException | RuntimeException e) {
            LOG.log(Level.FINE, "Unreadable archetype descriptor", e);
            return NONE;
        }
    }

    /**
     * The descriptor of {@code archetype}'s jar under {@code localRepo}, or {@link #NONE} when the jar is not
     * there (not downloaded yet) or carries no readable descriptor.
     */
    public static ArchetypeDescriptor readFromLocalRepository(Path localRepo, MavenArchetype archetype) {
        if (localRepo == null || archetype == null) {
            return NONE;
        }
        Path jar =
                MavenPluginGoals.jarPath(localRepo, archetype.groupId(), archetype.artifactId(), archetype.version());
        if (jar == null || !Files.isRegularFile(jar)) {
            return NONE;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null || entry.getSize() > MAX_DESCRIPTOR_BYTES) {
                return NONE;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parse(new String(in.readNBytes((int) MAX_DESCRIPTOR_BYTES), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Unreadable archetype jar: " + jar, e);
            return NONE;
        }
    }
}
