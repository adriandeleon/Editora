package com.editora.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.editora.plugin.PluginInstaller;
import org.w3c.dom.Element;

/**
 * Reads the <b>goals a declared Maven plugin actually offers</b> out of the plugin's own descriptor
 * ({@code META-INF/maven/plugin.xml} inside its jar in the local repository).
 *
 * <p>This exists because {@link PomModel} is a literal pom.xml read, so {@link MavenActionsProvider} can only
 * list a goal that appears inside an explicit {@code <execution>}. A plugin declared for
 * <em>direct invocation</em> — {@code javafx:run}, {@code spring-boot:run}, {@code exec:java}, {@code
 * quarkus:dev} — carries only {@code <configuration>} and therefore contributed no row at all, even though
 * running it is the whole reason it is in the pom. The descriptor is the authority on what a plugin can run,
 * so we read it rather than keeping a curated table of well-known goals (which covers only the plugins
 * somebody remembered, and rots).
 *
 * <p><b>Local repository only, never the network.</b> A plugin not yet downloaded simply contributes nothing —
 * the tasks tree is a convenience, and a build-tool panel must not perform a network fetch on a project open.
 * Reads are cached per {@code (jar, mtime)} because {@code BuildTool.MAVEN.parse} re-runs on every save, tab
 * switch and focus-regain.
 */
public final class MavenPluginGoals {

    private static final Logger LOG = Logger.getLogger(MavenPluginGoals.class.getName());

    /** A plugin descriptor is generated metadata, but it lives in a user-supplied jar — cap the read. */
    private static final long MAX_DESCRIPTOR_BYTES = 4L * 1024 * 1024;

    /** A goal tooltip is the plugin author's prose; keep it to a glanceable length. */
    private static final int MAX_DESCRIPTION_CHARS = 200;

    /** Belt-and-braces against a pathological pom: never hand the tree an unbounded number of rows. */
    private static final int MAX_GOALS_PER_PLUGIN = 60;

    private static final int MAX_CACHE_ENTRIES = 256;

    private static final Map<String, Descriptor> CACHE = new ConcurrentHashMap<>();

    private MavenPluginGoals() {}

    /** One invokable goal: its name and the mojo's own description ({@code ""} when it declares none). */
    public record Goal(String name, String description) {}

    /**
     * A plugin's descriptor slice: the {@code <goalPrefix>} the CLI uses ({@code javafx}, {@code spotless})
     * and the goals it declares, in descriptor order.
     */
    public record Descriptor(String goalPrefix, List<Goal> goals) {}

    // --- descriptor parsing (pure) ---------------------------------------------------------------

    /**
     * Parses a {@code META-INF/maven/plugin.xml} document. Returns {@code null} when it is malformed or
     * declares no usable goal prefix — a plugin we cannot name goals for is one we must not list.
     *
     * <p>Both reads are deliberately <b>direct-child</b> lookups: {@code <mojo>} contains a {@code
     * <parameters>} subtree whose entries carry their own {@code <description>} elements, so a descendant
     * search would attach a random parameter's prose to the goal (and many mojos — {@code javafx:run} among
     * them — declare no description of their own at all).
     */
    public static Descriptor parseDescriptor(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        Element root;
        try {
            root = PomParser.parseDocument(xml).getDocumentElement();
        } catch (PomParseException e) {
            return null;
        }
        if (root == null) {
            return null;
        }
        String prefix = PomParser.strip(PomParser.firstChildText(root, "goalPrefix"));
        if (prefix.isEmpty()) {
            return null;
        }
        List<Goal> goals = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Element mojos = PomParser.firstChildElement(root, "mojos");
        for (Element mojo : PomParser.childElements(mojos, "mojo")) {
            String name = PomParser.strip(PomParser.firstChildText(mojo, "goal"));
            if (name.isEmpty() || !seen.add(name) || goals.size() >= MAX_GOALS_PER_PLUGIN) {
                continue;
            }
            goals.add(new Goal(name, shortDescription(PomParser.firstChildText(mojo, "description"))));
        }
        return goals.isEmpty() ? null : new Descriptor(prefix, List.copyOf(goals));
    }

    /** Collapses a descriptor's (often multi-line, HTML-ish) prose into one capped tooltip line. */
    static String shortDescription(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
        if (text.length() <= MAX_DESCRIPTION_CHARS) {
            return text;
        }
        return text.substring(0, MAX_DESCRIPTION_CHARS).strip() + "…";
    }

    // --- local repository layout (pure) ----------------------------------------------------------

    /**
     * The {@code <localRepository>} declared in a {@code settings.xml}, or {@code null} when it declares
     * none. {@code ${user.home}} — the one property Maven's own docs use in this element — is expanded;
     * any other unexpanded property makes the value unusable, so it is rejected rather than turned into a
     * path with a literal {@code ${...}} segment in it.
     */
    public static String localRepositoryFromSettings(String settingsXml, String userHome) {
        if (settingsXml == null || settingsXml.isBlank()) {
            return null;
        }
        Element root;
        try {
            root = PomParser.parseDocument(settingsXml).getDocumentElement();
        } catch (PomParseException e) {
            return null;
        }
        String value = PomParser.strip(PomParser.firstChildText(root, "localRepository"));
        if (value.isEmpty()) {
            return null;
        }
        value = value.replace("${user.home}", userHome == null ? "" : userHome);
        return value.contains("${") ? null : value;
    }

    /** The local repository directory: {@code settings.xml}'s override, else {@code <userHome>/.m2/repository}. */
    public static Path localRepository(Path userHome) {
        Path settings = userHome.resolve(".m2").resolve("settings.xml");
        if (Files.isRegularFile(settings)) {
            try {
                String override = localRepositoryFromSettings(
                        Files.readString(settings, StandardCharsets.UTF_8), userHome.toString());
                if (override != null) {
                    return Path.of(override);
                }
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Unreadable settings.xml, using the default local repository", e);
            }
        }
        return userHome.resolve(".m2").resolve("repository");
    }

    /** Where a plugin jar lives under {@code localRepo}, per the standard repository layout. */
    public static Path jarPath(Path localRepo, String groupId, String artifactId, String version) {
        Path dir = artifactDirectory(localRepo, groupId, artifactId);
        return dir == null ? null : dir.resolve(version).resolve(artifactId + "-" + version + ".jar");
    }

    private static Path artifactDirectory(Path localRepo, String groupId, String artifactId) {
        if (localRepo == null || isUnusable(groupId) || isUnusable(artifactId)) {
            return null;
        }
        Path dir = localRepo;
        for (String segment : groupId.split("\\.")) {
            if (isUnusable(segment)) {
                return null;
            }
            dir = dir.resolve(segment);
        }
        return dir.resolve(artifactId);
    }

    /**
     * A coordinate segment we refuse to turn into a path: blank, an unexpanded {@code ${property}}, or
     * anything with a separator or {@code ..} in it. The pom is repository content, so a coordinate must
     * never be able to address a file outside the local repository.
     */
    private static boolean isUnusable(String segment) {
        return segment == null
                || segment.isBlank()
                || segment.contains("${")
                || segment.contains("/")
                || segment.contains("\\")
                || segment.contains("..");
    }

    // --- reading (touches the filesystem) --------------------------------------------------------

    /**
     * Every declared plugin's descriptor, in pom order, skipping the ones not resolvable locally. Reads
     * {@link PomModel#plugins()} only — a profile's plugins are contributed by that profile and are not
     * necessarily active.
     */
    public static List<Descriptor> readAll(PomModel model, Path localRepo) {
        List<Descriptor> out = new ArrayList<>();
        Set<String> prefixes = new LinkedHashSet<>();
        for (PomModel.Plugin plugin : model.plugins()) {
            Descriptor d = read(plugin, localRepo);
            if (d != null && prefixes.add(d.goalPrefix())) {
                out.add(d);
            }
        }
        return List.copyOf(out);
    }

    /** One plugin's descriptor, or {@code null} when its jar is not in the local repository / unreadable. */
    public static Descriptor read(PomModel.Plugin plugin, Path localRepo) {
        String version = resolveVersion(plugin, localRepo);
        if (version == null) {
            return null;
        }
        Path jar = jarPath(localRepo, plugin.groupId(), plugin.artifactId(), version);
        if (jar == null || !Files.isRegularFile(jar)) {
            return null;
        }
        String key;
        try {
            key = jar + "@" + Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return null;
        }
        Descriptor cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Descriptor parsed = parseDescriptor(readDescriptor(jar));
        if (parsed != null) {
            if (CACHE.size() >= MAX_CACHE_ENTRIES) {
                CACHE.clear();
            }
            CACHE.put(key, parsed);
        }
        return parsed;
    }

    /**
     * The version to look up: the declared one, or — when the pom leaves it to a parent / {@code
     * <pluginManagement>} / a property we cannot resolve — the newest version present locally. Guessing the
     * newest local version is safe here because the descriptor is only used to <em>name</em> goals; the build
     * itself still runs through Maven, which resolves the real version.
     */
    private static String resolveVersion(PomModel.Plugin plugin, Path localRepo) {
        String declared = PomParser.strip(plugin.version());
        if (!declared.isEmpty() && !declared.contains("${")) {
            return declared;
        }
        Path dir = artifactDirectory(localRepo, plugin.groupId(), plugin.artifactId());
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        String best = null;
        try (var entries = Files.list(dir)) {
            for (Path candidate : (Iterable<Path>) entries.filter(Files::isDirectory)::iterator) {
                String version = candidate.getFileName().toString();
                if (!Files.isRegularFile(candidate.resolve(plugin.artifactId() + "-" + version + ".jar"))) {
                    continue;
                }
                if (best == null || PluginInstaller.compareVersions(version, best) > 0) {
                    best = version;
                }
            }
        } catch (IOException | UncheckedIOException e) {
            return null;
        }
        return best;
    }

    private static String readDescriptor(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/maven/plugin.xml");
            if (entry == null || entry.getSize() > MAX_DESCRIPTOR_BYTES) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes((int) MAX_DESCRIPTOR_BYTES);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Unreadable plugin descriptor: " + jar, e);
            return null;
        }
    }
}
