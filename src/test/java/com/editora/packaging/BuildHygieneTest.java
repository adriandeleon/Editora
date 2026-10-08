package com.editora.packaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards build rules that live in {@code pom.xml} and contributor docs, where nothing compiles them.
 *
 * <ul>
 *   <li>"{@code clean} is mandatory for {@code -Pdist}" was documentation only, and a release once shipped a
 *       dead keyboard from a stale {@code target/classes}. The packaging profiles now run the clean
 *       themselves; this pins that they keep doing so — and that the fat-jar one never grows into a full
 *       clean, which would delete the installers the release workflow has just built.
 *   <li>The fat jar's licence handling (shade transformers).
 *   <li>The vendored RichTextFX jar against its recorded checksum.
 *   <li>The coverage floors quoted in {@code docs/testing.md} against the ones the build enforces.
 * </ul>
 */
class BuildHygieneTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir"));
    private static Document pom;

    @BeforeAll
    static void parsePom() throws Exception {
        pom = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(REPO.resolve("pom.xml").toFile());
    }

    // ---- small DOM helpers ------------------------------------------------------------------------------

    private static List<Element> children(Node parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e && name.equals(e.getNodeName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element child(Node parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static String text(Node parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }

    private static Element profile(String id) {
        for (Element p : children(child(pom.getDocumentElement(), "profiles"), "profile")) {
            if (id.equals(text(p, "id"))) {
                return p;
            }
        }
        throw new AssertionError("no <profile> with id " + id);
    }

    private static Element plugin(Element profile, String artifactId) {
        for (Element p : children(child(child(profile, "build"), "plugins"), "plugin")) {
            if (artifactId.equals(text(p, "artifactId"))) {
                return p;
            }
        }
        return null;
    }

    /** The one execution of maven-clean-plugin's {@code clean} goal bound to {@code initialize}. */
    private static Element cleanAtInitialize(String profileId) {
        Element plugin = plugin(profile(profileId), "maven-clean-plugin");
        assertNotNull(
                plugin,
                "the " + profileId + " profile no longer runs maven-clean-plugin: a build without an explicit "
                        + "`clean` would package stale classes again");
        for (Element execution : children(child(plugin, "executions"), "execution")) {
            boolean cleans = children(child(execution, "goals"), "goal").stream()
                    .anyMatch(g -> "clean".equals(g.getTextContent().trim()));
            if (cleans && "initialize".equals(text(execution, "phase"))) {
                return execution;
            }
        }
        throw new AssertionError(profileId + ": maven-clean-plugin has no `clean` execution bound to `initialize`");
    }

    // ---- the clean rule ---------------------------------------------------------------------------------

    @Test
    void theDistProfileCleansTheWholeBuildDirectoryBeforeItBuilds() {
        Element execution = cleanAtInitialize("dist");
        assertTrue(
                child(execution, "configuration") == null,
                "dist must do a FULL clean (no filesets/excludes): jlink and jpackage both read from target/");
    }

    @Test
    void theFatJarProfileCleansOnlyTheCompiledClasses() {
        Element configuration = child(cleanAtInitialize("fatjar"), "configuration");
        assertNotNull(configuration, "fatjar's clean must be restricted to filesets");
        assertEquals(
                "true",
                text(configuration, "excludeDefaultDirectories"),
                "fatjar must NOT wipe target/: release.yml runs it right after -Pdist and then stages "
                        + "target/dist and target/aot-image");
        List<String> directories = new ArrayList<>();
        for (Element fileset : children(child(configuration, "filesets"), "fileset")) {
            directories.add(text(fileset, "directory"));
        }
        assertTrue(
                directories.contains("${project.build.outputDirectory}"),
                "fatjar must delete the compiled classes it shades: " + directories);
        for (String directory : directories) {
            assertFalse(
                    directory.equals("${project.build.directory}")
                            || directory.endsWith("/dist")
                            || directory.endsWith("/aot-image"),
                    "fatjar's clean would delete the installers the release has just built: " + directory);
        }
    }

    // ---- the fat jar's licences -------------------------------------------------------------------------

    @Test
    void theFatJarMergesNoticesAndKeepsEditorasOwnLicence() {
        Element shade = plugin(profile("fatjar"), "maven-shade-plugin");
        assertNotNull(shade);
        List<String> transformers = new ArrayList<>();
        NodeList nodes = shade.getElementsByTagName("transformer");
        for (int i = 0; i < nodes.getLength(); i++) {
            transformers.add(((Element) nodes.item(i)).getAttribute("implementation"));
        }
        String prefix = "org.apache.maven.plugins.shade.resource.";
        for (String needed : List.of(
                "ApacheLicenseResourceTransformer", "ApacheNoticeResourceTransformer", "IncludeResourceTransformer")) {
            assertTrue(transformers.contains(prefix + needed), "the fat jar lost its " + needed + ": " + transformers);
        }
    }

    @Test
    void licenseAndNoticeArePackagedFromTheRepositoryRootIntoAPathShadeDoesNotRewrite() {
        boolean found = false;
        for (Element resource : children(child(child(pom.getDocumentElement(), "build"), "resources"), "resource")) {
            if ("META-INF/editora".equals(text(resource, "targetPath"))) {
                List<String> includes = children(child(resource, "includes"), "include").stream()
                        .map(e -> e.getTextContent().trim())
                        .toList();
                assertEquals(List.of("LICENSE", "NOTICE"), includes);
                found = true;
            }
        }
        assertTrue(found, "pom.xml no longer packages LICENSE and NOTICE into META-INF/editora/");
    }

    // ---- the vendored RichTextFX fork -------------------------------------------------------------------

    @Test
    void theVendoredRichTextFxJarMatchesItsRecordedChecksumAndIsTheOnlyVersion() throws Exception {
        String version = text(child(pom.getDocumentElement(), "properties"), "richtextfx.version");
        Path artifact = REPO.resolve(Path.of("m2-repo", "io", "github", "adriandeleon", "richtextfx"));
        Path dir = artifact.resolve(version);
        assertTrue(Files.isDirectory(dir), "pom.xml asks for richtextfx " + version + " but " + dir + " is missing");
        for (String extension : List.of("jar", "pom")) {
            Path file = dir.resolve("richtextfx-" + version + "." + extension);
            Path recorded = dir.resolve(file.getFileName() + ".sha256");
            assertTrue(Files.isRegularFile(recorded), "no recorded checksum for the vendored " + file.getFileName());
            String actual = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            assertEquals(
                    Files.readString(recorded).trim(),
                    actual,
                    file.getFileName() + " is not the file whose checksum was recorded — if the fork was "
                            + "rebuilt on purpose, update the .sha256 beside it");
        }
        try (var versions = Files.list(artifact)) {
            assertEquals(
                    List.of(version),
                    versions.filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .toList(),
                    "m2-repo carries RichTextFX versions the pom does not use");
        }
    }

    // ---- docs that quote the build ----------------------------------------------------------------------

    /** {@code short package or class name -> minimum line coverage}, straight from the jacoco-check rules. */
    private static Map<String, String> enforcedCoverageFloors() {
        Map<String, String> floors = new LinkedHashMap<>();
        NodeList rules = pom.getElementsByTagName("rule");
        for (int i = 0; i < rules.getLength(); i++) {
            Element rule = (Element) rules.item(i);
            if (child(rule, "element") == null || child(rule, "limits") == null) {
                continue; // an enforcer rule, not a JaCoCo one
            }
            if (child(rule, "includes") == null) {
                continue; // the whole-build rule: it names no package, and the guide states it in prose
            }
            String minimum = text(child(child(rule, "limits"), "limit"), "minimum");
            for (Element include : children(child(rule, "includes"), "include")) {
                floors.put(include.getTextContent().trim().replaceFirst("^com\\.editora\\.", ""), minimum);
            }
        }
        return floors;
    }

    @Test
    void theCoverageFloorsQuotedInTheTestingGuideAreTheOnesTheBuildEnforces() throws IOException {
        String guide = Files.readString(REPO.resolve(Path.of("docs", "testing.md")));
        String section = guide.substring(guide.indexOf("## Coverage"));
        Map<String, String> floors = enforcedCoverageFloors();
        assertTrue(floors.size() >= 9, "expected the JaCoCo rules, found " + floors);
        Pattern number = Pattern.compile("\\d\\.\\d\\d");
        List<String> wrong = new ArrayList<>();
        floors.forEach((name, minimum) -> {
            int at = section.indexOf("`" + name + "`");
            Matcher m = number.matcher(at < 0 ? "" : section.substring(at));
            if (!m.find() || !m.group().equals(minimum)) {
                wrong.add(name + " (pom.xml enforces " + minimum + ")");
            }
        });
        assertTrue(
                wrong.isEmpty(),
                "docs/testing.md → Coverage quotes a floor that pom.xml does not enforce, or omits one: " + wrong);
    }
}
