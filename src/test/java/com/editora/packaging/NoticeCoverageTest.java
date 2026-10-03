package com.editora.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the licence material Editora has to ship with every build.
 *
 * <p>Two things went unnoticed for a long time because nothing checks them: no artifact carried the MIT
 * licence or the {@code NOTICE} file at all, and {@code NOTICE} itself had stopped tracking the pom — eight
 * bundled libraries (commonmark, LSP4J, JLaTeXMath, SnakeYAML, …) were missing from it. A dependency is added
 * in one file and attributed in another, with no compiler between them, so this test is the link.
 */
class NoticeCoverageTest {

    private static final Path REPO = Path.of(System.getProperty("user.dir"));

    /**
     * Every dependency declared in {@code pom.xml} that ships — main build and profiles (the {@code native}
     * profile adds the static JavaFX libraries), anything but {@code test} scope — as {@code groupId:artifactId}.
     */
    private static List<String> shippedDependencies() throws Exception {
        Document pom = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(REPO.resolve("pom.xml").toFile());
        List<String> out = new ArrayList<>();
        NodeList all = pom.getElementsByTagName("dependency");
        for (int i = 0; i < all.getLength(); i++) {
            Element dependency = (Element) all.item(i);
            Node owner = dependency.getParentNode().getParentNode();
            // <project><dependencies> or <profile><dependencies>; a plugin's own <dependencies> does not ship.
            if (!"project".equals(owner.getNodeName()) && !"profile".equals(owner.getNodeName())) {
                continue;
            }
            if ("test".equals(child(dependency, "scope"))) {
                continue;
            }
            out.add(child(dependency, "groupId") + ":" + child(dependency, "artifactId"));
        }
        return out;
    }

    private static String child(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (name.equals(children.item(i).getNodeName())) {
                return children.item(i).getTextContent().trim();
            }
        }
        return null;
    }

    @Test
    void everyShippedDependencyIsNamedInNotice() throws Exception {
        String notice = Files.readString(REPO.resolve("NOTICE"));
        List<String> dependencies = shippedDependencies();
        assertTrue(dependencies.size() > 20, "the pom parse found suspiciously few dependencies: " + dependencies);
        List<String> missing = new ArrayList<>();
        for (String coordinates : dependencies) {
            if (!notice.contains(coordinates)) {
                missing.add(coordinates);
            }
        }
        assertTrue(
                missing.isEmpty(),
                "these runtime dependencies are declared in pom.xml but NOTICE does not mention them by their "
                        + "groupId:artifactId — add an entry (name, licence, URL) for each: " + missing);
    }

    @Test
    void theApplicationJarCarriesLicenseAndNoticeFromTheRepositoryRoot() throws IOException {
        // The same resources jlink puts into the installers' runtime image and shade into the fat jar.
        assertArrayEquals(
                Files.readAllBytes(REPO.resolve("LICENSE")),
                resource("/META-INF/editora/LICENSE"),
                "the packaged LICENSE is not the repository's");
        assertArrayEquals(
                Files.readAllBytes(REPO.resolve("NOTICE")),
                resource("/META-INF/editora/NOTICE"),
                "the packaged NOTICE is not the repository's");
        String apache = new String(resource("/META-INF/editora/licenses/Apache-2.0.txt"), StandardCharsets.US_ASCII);
        assertTrue(apache.contains("Apache License") && apache.contains("Version 2.0, January 2004"));
    }

    @Test
    void everyBundledFontFamilyShipsItsOpenFontLicence() throws IOException {
        Path fonts = REPO.resolve(Path.of("src", "main", "resources", "com", "editora", "fonts"));
        List<String> families = new ArrayList<>();
        try (var dirs = Files.list(fonts)) {
            dirs.filter(Files::isDirectory)
                    .forEach(d -> families.add(d.getFileName().toString()));
        }
        assertFalse(families.isEmpty(), "no font families found under " + fonts);
        String notice = Files.readString(REPO.resolve("NOTICE"));
        assertTrue(notice.contains("OFL.txt"), "NOTICE should say where each family's licence file is");
        for (String family : families) {
            String text = new String(resource("/com/editora/fonts/" + family + "/OFL.txt"), StandardCharsets.UTF_8);
            // OFL-1.1 condition 2: every copy carries the copyright notice AND the licence.
            assertTrue(
                    text.contains("SIL OPEN FONT LICENSE Version 1.1"),
                    family + "/OFL.txt is not the SIL Open Font License 1.1");
            assertTrue(text.contains("Copyright") || text.contains("©"), family + "/OFL.txt has no copyright notice");
        }
    }

    @Test
    void theDeliveriesBuiltOutsideTheJarAreHandedTheLicenceToo() throws IOException {
        String aot = Files.readString(REPO.resolve(Path.of("scripts", "aot_build.java")));
        assertTrue(aot.contains("\"--license-file\""), "the jpackage installer wrap no longer passes --license-file");
        for (String script : List.of("build-tarball.sh", "build-appimage.sh")) {
            String text = Files.readString(REPO.resolve(Path.of("scripts", script)));
            assertTrue(
                    text.contains("for doc in LICENSE NOTICE"),
                    "scripts/" + script + " no longer copies LICENSE and NOTICE into its bundle");
        }
        String nativeArchive = Files.readString(REPO.resolve(Path.of("scripts", "native", "package-release.py")));
        assertTrue(
                nativeArchive.contains("'LICENSE', 'NOTICE'"),
                "scripts/native/package-release.py no longer adds LICENSE and NOTICE to the archive");
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = NoticeCoverageTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name + " is not on the classpath — it would be missing from every artifact");
            return in.readAllBytes();
        }
    }
}
