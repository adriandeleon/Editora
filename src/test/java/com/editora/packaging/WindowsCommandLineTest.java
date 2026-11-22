package com.editora.packaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards Editora's command line on Windows: the console launcher {@code editora-cli.exe} beside the GUI
 * {@code Editora.exe}, and the MSI putting the install folder on the PATH. Neither can be built off Windows, so
 * this pins what the Windows build is handed: a drift here ships an installer whose command line silently prints
 * nothing, or that leaves its PATH entry behind on uninstall. The Windows CI job ("Terminal image", MSI leg)
 * installs the real MSI and runs the launcher.
 */
@Tag("guard")
class WindowsCommandLineTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    @Test
    void theConsoleLauncherIsAConsoleProgram() throws IOException {
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(ROOT.resolve("packaging/windows/editora-cli.properties"))) {
            p.load(in);
        }
        assertEquals("true", p.getProperty("win-console"));
        assertEquals(1, p.size(), "everything else is inherited from Editora.exe (module, Java options, AOT cache)");
    }

    @Test
    void aWindowsProfileDeclaredAfterDistAddsItToTheDistAppImage() throws IOException {
        String pom = Files.readString(ROOT.resolve("pom.xml"));
        int dist = pom.indexOf("<id>dist</id>");
        int cli = pom.indexOf("<id>windows-cli-launcher</id>");
        assertTrue(dist > 0, "the dist profile exists");
        // Maven injects active profiles in declaration order. Declared before dist (as it first was, in
        // os-windows), this jpackage plugin came first in the build and dist's aot-train-and-deliver ran
        // ahead of jpackage: no image, nothing delivered, a green build with an empty target/dist.
        assertTrue(cli > dist, "the launcher's profile is declared after dist");
        String profile = pom.substring(cli, pom.indexOf("</profile>", cli));
        assertTrue(profile.contains("<family>windows</family>"), profile);
        assertTrue(profile.contains("<name>editora-cli</name>"), profile);
        assertTrue(profile.contains("packaging/windows/editora-cli.properties"), profile);
        // Merged by execution id: a different id would add a second, goal-less execution and no launcher.
        assertTrue(profile.contains("<id>jpackage-app-image</id>"), profile);
        String distProfile = pom.substring(dist, pom.indexOf("</profile>", dist));
        assertTrue(distProfile.contains("<id>jpackage-app-image</id>"), "dist's app-image execution has the same id");
        for (String early : List.of("os-mac", "os-windows", "os-linux")) {
            int at = pom.indexOf("<id>" + early + "</id>");
            String before = pom.substring(at, pom.indexOf("</profile>", at));
            assertFalse(
                    before.contains("<artifactId>jpackage-maven-plugin</artifactId>"),
                    early + " (declared before dist) declares no jpackage");
        }
    }

    @Test
    void theMsiPutsTheInstallFolderOnThePathAndTakesItOff() throws Exception {
        Path dir = ROOT.resolve("packaging/windows/msi");
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(
                    List.of("os-condition.wxf"),
                    files.map(f -> f.getFileName().toString()).toList(),
                    "the resource dir overrides that one jpackage resource and nothing else");
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc =
                f.newDocumentBuilder().parse(dir.resolve("os-condition.wxf").toFile());

        Element condition = only(doc, "Condition");
        assertTrue(condition.getTextContent().contains("$(var.JpExecutableOSVersion)"), "jpackage's OS check stays");
        Element group = only(doc, "ComponentGroup");
        assertEquals("FragmentOsCondition", group.getAttribute("Id"), "the group jpackage's main feature installs");

        Element component = only(doc, "Component");
        assertEquals("INSTALLDIR", component.getAttribute("Directory"));
        assertTrue(
                component.getAttribute("Guid").matches("\\{[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12}}"),
                "a fixed GUID, the same in every release");
        assertEquals("yes", only(doc, "RegistryValue").getAttribute("KeyPath"));

        Element env = only(doc, "Environment");
        assertEquals("PATH", env.getAttribute("Name"));
        assertEquals("[INSTALLDIR]", env.getAttribute("Value"));
        assertEquals("last", env.getAttribute("Part"), "appended: never shadows another program");
        assertEquals("no", env.getAttribute("Permanent"), "removed on uninstall");
        assertEquals("yes", env.getAttribute("System"), "the MSI installs per machine");
    }

    @Test
    void theMsiWrapIsHandedThatResourceDir() throws IOException {
        String aot = Files.readString(ROOT.resolve("scripts/aot_build.java"));
        assertTrue(aot.contains("\"packaging\", \"windows\", \"msi\""), "aot_build.java resolves the folder");
        assertTrue(aot.contains("cmd.add(\"--resource-dir\");"), "and passes it to the MSI wrap");
    }

    private static Element only(Document doc, String name) {
        NodeList list = doc.getElementsByTagNameNS("*", name);
        assertEquals(1, list.getLength(), name);
        return (Element) list.item(0);
    }
}
