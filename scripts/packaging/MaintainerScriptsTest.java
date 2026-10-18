package packaging;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;
import static lib.Assert.assertFalse;
import static lib.Assert.assertNotContains;
import static lib.Assert.assertTrue;
import static lib.Assert.assumeTrue;
import static lib.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lib.Proc;
import lib.Repo;
import lib.TempDir;
import lib.Test;

/**
 * Runs the .deb maintainer scripts ({@code packaging/linux/postinst}, {@code postrm}) against a scratch root.
 *
 * <p>Both scripts address every path under {@code $DPKG_ROOT}, which dpkg leaves empty for a real install, so
 * pointing it at a temporary directory exercises the real code without root and without touching the machine.
 */
class MaintainerScriptsTest implements AutoCloseable {

    private static final Path LINUX = Repo.root().resolve("packaging").resolve("linux");
    private static final String EXPERT = "editora-Editora-expert.desktop";

    private static final String PRIMARY_DESKTOP =
            """
            [Desktop Entry]
            Type=Application
            Name=Editora
            Comment=A keyboard-driven, cross-platform programmer's text editor
            Exec=/opt/editora/bin/Editora %F
            Icon=/opt/editora/lib/Editora.png
            StartupWMClass=com.editora.App
            MimeType=text/plain;
            """;

    // A system file as another package and the administrator left it: other editors' defaults for types Editora
    // claims, a default for a type it does not claim, and a second section it must not touch.
    private static final String SHARED =
            """
            [Default Applications]
            text/plain=org.gnome.TextEditor.desktop
            text/html=firefox-esr.desktop
            application/json=code.desktop
            image/png=org.gnome.Loupe.desktop

            [Added Associations]
            text/plain=vim.desktop;
            """;

    private final TempDir scratch;
    private final Path root;
    private final Path apps;
    private final Path mimeapps;
    private final Path state;

    MaintainerScriptsTest() throws IOException {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "the maintainer scripts are POSIX shell");
        scratch = new TempDir();
        root = scratch.path();
        apps = root.resolve("usr/share/applications");
        Files.createDirectories(apps);
        mimeapps = apps.resolve("mimeapps.list");
        state = root.resolve("var/lib/editora/mimeapps.replaced");
        payload(root.resolve("opt/editora"));
    }

    @Override
    public void close() {
        scratch.close();
    }

    private static void payload(Path app) throws IOException {
        Files.createDirectories(app.resolve("bin"));
        Files.createDirectories(app.resolve("lib/icons"));
        Files.writeString(app.resolve("bin/Editora"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(app.resolve("bin/Editora"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(app.resolve("lib/editora-Editora.desktop"), PRIMARY_DESKTOP);
        Files.writeString(app.resolve("lib/com.editora.Editora.metainfo.xml"), "<component/>\n");
        Files.write(app.resolve("lib/icons/editora-48.png"), "png".getBytes(StandardCharsets.UTF_8));
    }

    private Proc.Result runScript(String name, String action) {
        Proc.Result result = Proc.run(
                List.of("sh", LINUX.resolve(name).toString(), action),
                Map.of("DPKG_ROOT", root.toString(), "PATH", System.getenv("PATH")),
                null,
                true);
        assertEquals(0, result.code(), result.err());
        return result;
    }

    /** The [Default Applications] section as a map. */
    private Map<String, String> defaults() throws IOException {
        var found = new LinkedHashMap<String, String>();
        boolean inside = false;
        for (String line : Files.readAllLines(mimeapps)) {
            if (line.startsWith("[")) {
                inside = line.equals("[Default Applications]");
            } else if (inside && line.contains("=")) {
                int equals = line.indexOf('=');
                String key = line.substring(0, equals);
                if (found.containsKey(key)) {
                    fail("duplicate default for " + key);
                }
                found.put(key, line.substring(equals + 1));
            }
        }
        return found;
    }

    private List<String> leftovers() throws IOException {
        try (Stream<Path> entries = Files.list(apps)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(".mimeapps"))
                    .toList();
        }
    }

    private static String mode(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    private static List<String> sortedLines(Path file) throws IOException {
        return Files.readAllLines(file).stream().sorted().toList();
    }

    @Test
    void installRegistersTheCommandTheEntriesAndTheExpertDefaults() throws IOException {
        runScript("postinst", "configure");
        assertEquals(
                Path.of("/opt/editora/bin/Editora"), Files.readSymbolicLink(root.resolve("usr/bin/editora")));
        assertTrue(Files.isRegularFile(apps.resolve("editora-Editora.desktop")), "primary entry missing");
        String expert = Files.readString(apps.resolve(EXPERT));
        assertContains(expert, "Exec=/opt/editora/bin/Editora --expert --single-window --no-session %F");
        assertNotContains(expert, "StartupWMClass");
        assertTrue(
                Files.isRegularFile(root.resolve("usr/share/metainfo/com.editora.Editora.metainfo.xml")),
                "metainfo missing");
        assertTrue(
                Files.isRegularFile(root.resolve("usr/share/icons/hicolor/48x48/apps/editora.png")), "icon missing");
        Map<String, String> defaults = defaults();
        assertEquals(EXPERT, defaults.get("text/plain"));
        assertEquals(EXPERT, defaults.get("application/json"));
        assertEquals(28L, defaults.values().stream().filter(EXPERT::equals).count());
        assertFalse(Files.exists(state), "nothing was displaced, so nothing to remember");
        assertEquals(List.of(), leftovers());
    }

    @Test
    void otherApplicationsDefaultsSurviveAnInstallAndARemoval() throws IOException {
        Files.writeString(mimeapps, SHARED);
        runScript("postinst", "configure");
        Map<String, String> defaults = defaults();
        assertEquals(EXPERT, defaults.get("text/plain")); // the deliberate system default
        assertEquals("firefox-esr.desktop", defaults.get("text/html"));
        assertEquals("org.gnome.Loupe.desktop", defaults.get("image/png"));
        assertContains(Files.readString(mimeapps), "[Added Associations]\ntext/plain=vim.desktop;");
        assertEquals(
                List.of("application/json=code.desktop", "text/plain=org.gnome.TextEditor.desktop"),
                sortedLines(state));
        assertEquals("rw-r--r--", mode(mimeapps));

        runScript("postrm", "remove");
        assertEquals(
                Map.of(
                        "text/plain", "org.gnome.TextEditor.desktop",
                        "text/html", "firefox-esr.desktop",
                        "application/json", "code.desktop",
                        "image/png", "org.gnome.Loupe.desktop"),
                defaults());
        assertContains(Files.readString(mimeapps), "[Added Associations]\ntext/plain=vim.desktop;");
        assertNotContains(Files.readString(mimeapps), EXPERT);
        assertFalse(Files.exists(state), "the remembered defaults survived the removal");
        assertFalse(Files.exists(state.getParent()), "the state directory survived the removal");
        assertEquals("rw-r--r--", mode(mimeapps));
        assertEquals(List.of(), leftovers());
    }

    @Test
    void anUpgradeKeepsTheRememberedDefaults() throws IOException {
        Files.writeString(mimeapps, SHARED);
        runScript("postinst", "configure");
        String before = Files.readString(mimeapps);
        runScript("postinst", "configure"); // the upgrade: our lines are already there
        assertEquals(before, Files.readString(mimeapps), "configure must be idempotent");
        assertEquals(
                List.of("application/json=code.desktop", "text/plain=org.gnome.TextEditor.desktop"),
                sortedLines(state));
        runScript("postrm", "remove");
        assertEquals("org.gnome.TextEditor.desktop", defaults().get("text/plain"));
    }

    @Test
    void aDefaultChosenAfterInstallIsNotOverriddenByTheRestore() throws IOException {
        Files.writeString(mimeapps, SHARED);
        runScript("postinst", "configure");
        // The administrator later points text/plain somewhere else, in place of our line.
        Files.writeString(
                mimeapps, Files.readString(mimeapps).replace("text/plain=" + EXPERT, "text/plain=kate.desktop"));
        runScript("postrm", "remove");
        Map<String, String> defaults = defaults();
        assertEquals("kate.desktop", defaults.get("text/plain"));
        assertEquals("code.desktop", defaults.get("application/json"));
    }

    @Test
    void aFileTheInstallCreatedIsRemovedAgain() throws IOException {
        runScript("postinst", "configure");
        assertTrue(Files.isRegularFile(mimeapps), "postinst did not create mimeapps.list");
        runScript("postrm", "purge");
        assertFalse(Files.exists(mimeapps), "mimeapps.list survived the purge");
        assertFalse(
                Files.exists(root.resolve("usr/bin/editora"), LinkOption.NOFOLLOW_LINKS),
                "the command survived the purge");
        assertFalse(Files.exists(apps.resolve("editora-Editora.desktop")), "the primary entry survived the purge");
        assertFalse(Files.exists(apps.resolve(EXPERT)), "the expert entry survived the purge");
        assertFalse(
                Files.exists(root.resolve("usr/share/icons/hicolor/48x48/apps/editora.png")),
                "the icon survived the purge");
    }

    @Test
    void onlyThePackagesOwnInstallDirectoryIsUsed() throws IOException {
        // Sorts before /opt/editora, so the old /opt/*/bin/Editora glob picked it.
        payload(root.resolve("opt/aaa-other"));
        Files.writeString(
                root.resolve("opt/aaa-other/lib/editora-Editora.desktop"),
                PRIMARY_DESKTOP.replace("/opt/editora", "/opt/aaa-other"));
        runScript("postinst", "configure");
        assertEquals(
                Path.of("/opt/editora/bin/Editora"), Files.readSymbolicLink(root.resolve("usr/bin/editora")));
        assertContains(Files.readString(apps.resolve("editora-Editora.desktop")), "Exec=/opt/editora/bin/Editora %F");
    }

    @Test
    void aForeignUsrBinEditoraIsLeftAlone() throws IOException {
        Path usrBin = root.resolve("usr/bin");
        Files.createDirectories(usrBin);
        for (String kind : List.of("file", "link")) {
            Path target = usrBin.resolve("editora");
            if (kind.equals("file")) {
                Files.writeString(target, "#!/bin/sh\necho another editora\n");
            } else {
                Files.createSymbolicLink(target, Path.of("/usr/local/libexec/another-editora"));
            }
            Proc.Result result = runScript("postinst", "configure");
            assertContains(result.err(), "not replacing it");
            if (kind.equals("file")) {
                assertFalse(Files.isSymbolicLink(target), "the foreign file was replaced by a link");
                assertContains(Files.readString(target), "another editora");
            } else {
                assertEquals(Path.of("/usr/local/libexec/another-editora"), Files.readSymbolicLink(target));
            }
            runScript("postrm", "remove");
            assertTrue(
                    Files.exists(target, LinkOption.NOFOLLOW_LINKS),
                    "postrm removed a command that is not ours (" + kind + ")");
            Files.delete(target);
        }
    }

    @Test
    void ourOwnStaleLinkIsRefreshed() throws IOException {
        Path usrBin = root.resolve("usr/bin");
        Files.createDirectories(usrBin);
        Files.createSymbolicLink(usrBin.resolve("editora"), Path.of("/opt/editora/bin/OldLauncher"));
        runScript("postinst", "configure");
        assertEquals(Path.of("/opt/editora/bin/Editora"), Files.readSymbolicLink(usrBin.resolve("editora")));
    }

    @Test
    void theScriptsParseUnderAPosixShell() {
        for (String name : List.of("postinst", "postrm", "tarball-install.sh")) {
            Proc.Result check = Proc.run(List.of("sh", "-n", LINUX.resolve(name).toString()));
            assertEquals(0, check.code(), name + ": " + check.err());
        }
    }
}
