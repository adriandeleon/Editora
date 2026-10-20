package com.editora.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.w3c.dom.Element;

/**
 * What a launch or a test run needs to know from a pom's {@code <build>} (reviews D11, D17): where the build
 * writes ({@code <build><directory>}), where Surefire and Failsafe write their reports
 * ({@code <reportsDirectory>}), and whether the Spring Boot Maven plugin is bound — with a
 * {@code <jvmArguments>} of its own, which beats the {@code -Dspring-boot.run.jvmArguments} a debug launch
 * passes. Read from the file's own {@code <build>}, its {@code <pluginManagement>} and every profile's, and
 * from the parents on disk ({@code <relativePath>}, default {@code ../pom.xml}; bounded), the nearest pom
 * winning — the values Maven itself would apply, short of a full effective pom. Tolerant: a pom that does not
 * parse contributes nothing. Pure given the text; {@link #read} does the file I/O.
 *
 * @param buildDirectory the build output folder, or {@code null} for Maven's {@code target}
 * @param surefireReportsDirectory Surefire's {@code reportsDirectory}, or {@code null} for the default
 * @param failsafeReportsDirectory Failsafe's {@code reportsDirectory}, or {@code null} for the default
 * @param springBoot whether {@code spring-boot-maven-plugin} is declared (plugins, management or a profile)
 * @param springBootJvmArguments the plugin's configured {@code <jvmArguments>}, or {@code null}
 */
public record PomBuildConfig(
        String buildDirectory,
        String surefireReportsDirectory,
        String failsafeReportsDirectory,
        boolean springBoot,
        String springBootJvmArguments) {

    /** Nothing configured: Maven's defaults throughout. */
    public static final PomBuildConfig DEFAULTS = new PomBuildConfig(null, null, null, false, null);

    /** How many parents on disk are followed. */
    static final int MAX_PARENTS = 6;

    /** The Spring Boot Maven plugin's artifactId (its groupId is {@code org.springframework.boot}). */
    public static final String SPRING_BOOT_PLUGIN = "spring-boot-maven-plugin";

    /**
     * Reads {@code pom} and its parents on disk. {@code pom} may be a folder (its {@code pom.xml}) or the file.
     */
    public static PomBuildConfig read(Path pom) {
        if (pom == null) {
            return DEFAULTS;
        }
        Path file = Files.isDirectory(pom) ? pom.resolve("pom.xml") : pom;
        List<Path> seen = new ArrayList<>();
        PomBuildConfig merged = DEFAULTS;
        Path current = file;
        for (int hop = 0; current != null && hop <= MAX_PARENTS; hop++) {
            Path normalized = current.toAbsolutePath().normalize();
            if (seen.contains(normalized)) {
                break; // a cycle through <relativePath>
            }
            seen.add(normalized);
            String text = readOrNull(normalized);
            if (text == null) {
                break;
            }
            Path dir = normalized.getParent();
            PomBuildConfig here = parse(text, dir == null ? "" : dir.toString());
            merged = merged.withDefaultsFrom(here); // the nearest pom already read wins
            current = parentOf(text, dir);
        }
        return merged;
    }

    /**
     * Reads one pom's text. {@code basedir} names the pom's folder as Maven does ({@code ${basedir}},
     * {@code ${project.basedir}}); it may be blank when unknown.
     */
    public static PomBuildConfig parse(String xml, String basedir) {
        if (xml == null || xml.isBlank()) {
            return DEFAULTS;
        }
        Element project;
        try {
            project = PomParser.parseDocument(xml).getDocumentElement();
        } catch (PomParseException | RuntimeException e) {
            return DEFAULTS;
        }
        if (project == null || !"project".equals(project.getTagName())) {
            return DEFAULTS;
        }
        Map<String, String> props = new LinkedHashMap<>();
        Element propertiesEl = PomParser.firstChildElement(project, "properties");
        if (propertiesEl != null) {
            for (org.w3c.dom.Node n = propertiesEl.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    props.put(n.getNodeName(), PomParser.strip(PomParser.text((Element) n)));
                }
            }
        }
        String base = basedir == null ? "" : basedir;
        if (!base.isEmpty()) {
            props.putIfAbsent("basedir", base);
            props.putIfAbsent("project.basedir", base);
        }
        Element build = PomParser.firstChildElement(project, "build");
        String directory = resolved(PomParser.firstChildText(build, "directory"), props);
        props.putIfAbsent("project.build.directory", directory != null ? directory : join(base, "target"));

        String surefire = null;
        String failsafe = null;
        boolean boot = false;
        String jvmArguments = null;
        List<Element> pluginLists = new ArrayList<>();
        addPluginLists(build, pluginLists);
        Element profiles = PomParser.firstChildElement(project, "profiles");
        for (Element profile : PomParser.childElements(profiles, "profile")) {
            addPluginLists(PomParser.firstChildElement(profile, "build"), pluginLists);
        }
        for (Element plugins : pluginLists) {
            for (Element plugin : PomParser.childElements(plugins, "plugin")) {
                String artifactId = PomParser.strip(PomParser.firstChildText(plugin, "artifactId"));
                Element configuration = PomParser.firstChildElement(plugin, "configuration");
                switch (artifactId) {
                    case "maven-surefire-plugin" -> {
                        String dir = resolved(PomParser.firstChildText(configuration, "reportsDirectory"), props);
                        surefire = surefire != null ? surefire : dir;
                    }
                    case "maven-failsafe-plugin" -> {
                        String dir = resolved(PomParser.firstChildText(configuration, "reportsDirectory"), props);
                        failsafe = failsafe != null ? failsafe : dir;
                    }
                    case SPRING_BOOT_PLUGIN -> {
                        boot = true;
                        String args = resolved(PomParser.firstChildText(configuration, "jvmArguments"), props);
                        jvmArguments = jvmArguments != null ? jvmArguments : args;
                    }
                    default -> {}
                }
            }
        }
        return new PomBuildConfig(directory, surefire, failsafe, boot, jvmArguments);
    }

    /** {@code this} with what it leaves unset taken from {@code parent} (a parent pom's values). */
    PomBuildConfig withDefaultsFrom(PomBuildConfig parent) {
        return new PomBuildConfig(
                buildDirectory != null ? buildDirectory : parent.buildDirectory(),
                surefireReportsDirectory != null ? surefireReportsDirectory : parent.surefireReportsDirectory(),
                failsafeReportsDirectory != null ? failsafeReportsDirectory : parent.failsafeReportsDirectory(),
                springBoot || parent.springBoot(),
                springBootJvmArguments != null ? springBootJvmArguments : parent.springBootJvmArguments());
    }

    /** The build output folder of the module at {@code root}: {@code buildDirectory} or {@code root/target}. */
    public Path buildDirectory(Path root) {
        return buildDirectory == null
                ? root.resolve("target")
                : root.resolve(buildDirectory).normalize();
    }

    /** Surefire's report folder of the module at {@code root}. */
    public Path surefireReports(Path root) {
        return surefireReportsDirectory == null
                ? buildDirectory(root).resolve("surefire-reports")
                : root.resolve(surefireReportsDirectory).normalize();
    }

    /** Failsafe's report folder of the module at {@code root}. */
    public Path failsafeReports(Path root) {
        return failsafeReportsDirectory == null
                ? buildDirectory(root).resolve("failsafe-reports")
                : root.resolve(failsafeReportsDirectory).normalize();
    }

    /** The parent pom {@code text} (read from {@code dir}) names on disk, or {@code null} for none. */
    static Path parentOf(String text, Path dir) {
        if (dir == null) {
            return null;
        }
        Element project;
        try {
            project = PomParser.parseDocument(text).getDocumentElement();
        } catch (PomParseException | RuntimeException e) {
            return null;
        }
        Element parent = PomParser.firstChildElement(project, "parent");
        if (parent == null) {
            return null;
        }
        String relative = PomParser.strip(PomParser.firstChildText(parent, "relativePath"));
        if (PomParser.firstChildElement(parent, "relativePath") != null && relative.isEmpty()) {
            return null; // an empty <relativePath/> means "from the repository", never a file beside this one
        }
        try {
            Path candidate = dir.resolve(relative.isEmpty() ? "../pom.xml" : relative.replace('\\', '/'))
                    .normalize();
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve("pom.xml");
            }
            return Files.isRegularFile(candidate) ? candidate : null;
        } catch (RuntimeException badName) {
            return null;
        }
    }

    private static void addPluginLists(Element build, List<Element> into) {
        if (build == null) {
            return;
        }
        Element plugins = PomParser.firstChildElement(build, "plugins");
        if (plugins != null) {
            into.add(plugins);
        }
        Element managed =
                PomParser.firstChildElement(PomParser.firstChildElement(build, "pluginManagement"), "plugins");
        if (managed != null) {
            into.add(managed);
        }
    }

    private static String resolved(String value, Map<String, String> props) {
        String text = PomParser.strip(value);
        return text.isEmpty() ? null : PomSummary.resolve(text, props);
    }

    private static String join(String base, String child) {
        return base.isEmpty() ? child : base + java.io.File.separator + child;
    }

    private static String readOrNull(Path pom) {
        try {
            return Files.isRegularFile(pom) ? Files.readString(pom) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
