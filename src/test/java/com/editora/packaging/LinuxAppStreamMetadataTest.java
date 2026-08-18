package com.editora.packaging;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

import com.editora.AppInfo;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@code packaging/linux/com.editora.Editora.metainfo.xml} — the AppStream metadata a software
 * centre describes the installed .deb from.
 *
 * <p>Without it, GNOME Software falls back to the dpkg control fields and the app page reads
 * "editora / Editora", "Unknown License" and "No details for this release". The failure mode this pins is
 * subtler than the file being absent: the metadata is only *connected* to the installed application when
 * its {@code <launchable>} names the .desktop file the postinst actually writes, and those two live in
 * different files with nothing linking them. Get that wrong and everything still installs, validates and
 * looks correct — the software centre simply goes on showing the fallback, which is exactly the symptom
 * being fixed here.
 *
 * <p>The licence and homepage are pinned to {@link AppInfo} for the same reason: a software centre showing
 * a licence that contradicts the About dialog is worse than showing none.
 */
class LinuxAppStreamMetadataTest {

    private static final Path METAINFO = Path.of("packaging", "linux", "com.editora.Editora.metainfo.xml");
    private static final Path POSTINST = Path.of("packaging", "linux", "postinst");

    private static Document parse() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        // Same hardening as maven/PomParser — this is build input, but the habit is cheap.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(METAINFO.toFile());
    }

    private static String text(Document doc, String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    @Test
    void theMetadataDeclaresWhatTheSoftwareCentreShows() throws Exception {
        assertTrue(Files.isRegularFile(METAINFO), METAINFO + " is missing — the .deb would be undescribed");
        Document doc = parse();
        assertEquals("com.editora.Editora", text(doc, "id"));
        assertEquals(AppInfo.NAME, text(doc, "name"));
        assertTrue(text(doc, "summary").length() > 10, "a summary is what replaces the bare package name");
        // "Unknown License" on the app page is precisely this element being absent.
        assertEquals("MIT", text(doc, "project_license"));
        assertTrue(text(doc, "metadata_license").length() > 2, "AppStream requires the metadata's own licence");
    }

    @Test
    void theLaunchableNamesTheDesktopFileThePostinstActuallyInstalls() throws Exception {
        String declared = text(parse(), "launchable");
        String postinst = Files.readString(POSTINST);
        assertTrue(
                postinst.contains("/usr/share/applications/" + declared),
                "the metainfo points at '" + declared + "' but the postinst installs no such .desktop file — "
                        + "the software centre would silently keep showing the dpkg fallback");
    }

    @Test
    void theHomepageAndLicenceMatchWhatTheApplicationItselfReports() throws Exception {
        Document doc = parse();
        NodeList urls = doc.getElementsByTagName("url");
        String homepage = "";
        for (int i = 0; i < urls.getLength(); i++) {
            var node = urls.item(i);
            var type = node.getAttributes().getNamedItem("type");
            if (type != null && "homepage".equals(type.getNodeValue())) {
                homepage = node.getTextContent().trim();
            }
        }
        assertEquals(AppInfo.HOMEPAGE, homepage, "the advertised homepage must match AppInfo.HOMEPAGE");
        assertTrue(
                AppInfo.LICENSE.startsWith("MIT"),
                "project_license is pinned to MIT above; AppInfo says " + AppInfo.LICENSE);
    }

    @Test
    void theReleaseBlockIsBuildSubstitutedRatherThanHandMaintained() throws Exception {
        String raw = Files.readString(METAINFO);
        // A hand-edited <releases> is the thing that rots back into "No details for this release"; the
        // build fills these in from the version actually being packaged.
        assertTrue(raw.contains("@VERSION@"), "the release version must be substituted at build time");
        assertTrue(raw.contains("@DATE@"), "the release date must be substituted at build time");
        assertTrue(
                Files.readString(Path.of("scripts", "aot_build.java")).contains("@VERSION@"),
                "nothing substitutes @VERSION@ — the shipped metadata would carry the literal placeholder");
    }
}
