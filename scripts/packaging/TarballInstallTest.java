package packaging;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;
import static lib.Assert.assertFalse;
import static lib.Assert.assertNotContains;
import static lib.Assert.assertNotEquals;
import static lib.Assert.assertTrue;
import static lib.Assert.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lib.Proc;
import lib.Repo;
import lib.TempDir;
import lib.Test;

/**
 * Runs {@code packaging/linux/tarball-install.sh} for real, against throwaway directories.
 *
 * <p>Everything a user-mode install touches hangs off {@code $HOME}, and {@code --destdir} stages a system-mode
 * install, so neither needs root. Root-only behaviour (the ownership fix) is observed through {@code id} and
 * {@code chown} stand-ins placed first on PATH.
 *
 * <p>The Python version of the reserved-characters test also had GLib (through the {@code gi} bindings) parse
 * the generated Exec line back into one program path. The JDK has no equivalent, so that check is gone; the
 * exact expected Exec line and {@code desktop-file-validate} (when it is installed) remain.
 */
class TarballInstallTest implements AutoCloseable {

    private static final Path INSTALLER = Repo.root().resolve("packaging/linux/tarball-install.sh");

    private final TempDir scratch;
    private final Path root;
    private final Path bundle;
    private final Path home;

    TarballInstallTest() throws IOException {
        assumeTrue(
                !System.getProperty("os.name").startsWith("Windows"),
                "the tarball installer is a POSIX shell script");
        scratch = new TempDir();
        root = scratch.path();
        // The extracted tarball: install.sh beside the Editora/ app image and the licence texts.
        bundle = root.resolve("editora-x86_64");
        Files.createDirectories(bundle.resolve("Editora/bin"));
        Files.createDirectory(bundle.resolve("Editora/lib"));
        Path launcher = bundle.resolve("Editora/bin/Editora");
        Files.writeString(launcher, "#!/bin/sh\necho launched\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxrwxr-x"));
        Files.write(bundle.resolve("Editora/lib/Editora.png"), "png".getBytes(StandardCharsets.UTF_8));
        Files.writeString(bundle.resolve("LICENSE"), "MIT\n");
        Files.writeString(bundle.resolve("NOTICE"), "notice\n");
        Files.copy(INSTALLER, bundle.resolve("install.sh"));
        home = root.resolve("home");
        Files.createDirectory(home);
    }

    @Override
    public void close() {
        scratch.close();
    }

    private Proc.Result install(String... args) {
        return installWithPath(null, args);
    }

    /** Runs install.sh with only HOME and PATH set, {@code pathPrefix} (when not null) first on PATH. */
    private Proc.Result installWithPath(Path pathPrefix, String... args) {
        var env = new HashMap<String, String>();
        env.put("HOME", home.toString());
        env.put("PATH", (pathPrefix == null ? "" : pathPrefix + File.pathSeparator) + System.getenv("PATH"));
        var command = new ArrayList<String>(List.of("sh", bundle.resolve("install.sh").toString()));
        command.addAll(List.of(args));
        return Proc.run(command, env, null, true);
    }

    private Path desktopFile(Path base) {
        return base.resolve("applications/editora.desktop");
    }

    private String desktopEntry() throws IOException {
        return desktopEntry(home.resolve(".local/share"));
    }

    private String desktopEntry(Path base) throws IOException {
        return Files.readString(desktopFile(base));
    }

    private static void executable(Path file, String text) throws IOException {
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void aUserInstallWiresTheCommandTheMenuEntryAndTheLicences() throws IOException {
        Proc.Result result = install("--user");
        assertEquals(0, result.code(), result.err());
        Path app = home.resolve(".local/editora");
        assertTrue(Files.isRegularFile(app.resolve("bin/Editora")), "the launcher was not installed");
        assertEquals("MIT\n", Files.readString(app.resolve("LICENSE")));
        assertEquals("notice\n", Files.readString(app.resolve("NOTICE")));
        Path link = home.resolve(".local/bin/editora");
        assertEquals(app.resolve("bin/Editora"), Files.readSymbolicLink(link));
        String entry = desktopEntry();
        // No reserved character in the path, so the Exec line is exactly what it always was.
        assertContains(entry, "Exec=" + app + "/bin/Editora %F\n");
        assertContains(entry, "Icon=" + app + "/lib/Editora.png\n");
    }

    @Test
    void reinstallingOverAPreviousInstallAndUninstallingWork() throws IOException {
        assertEquals(0, install("--user").code(), "first install");
        Path stale = home.resolve(".local/editora/lib/stale.aot");
        Files.writeString(stale, "old");
        assertEquals(0, install("--user").code(), "reinstall");
        assertFalse(Files.exists(stale), "a reinstall replaces the previous image");
        Proc.Result result = install("--user", "--uninstall");
        assertEquals(0, result.code(), result.err());
        assertFalse(Files.exists(home.resolve(".local/editora")), "the image survived the uninstall");
        assertFalse(
                Files.exists(home.resolve(".local/bin/editora"), LinkOption.NOFOLLOW_LINKS),
                "the command survived the uninstall");
        assertFalse(
                Files.exists(home.resolve(".local/share/applications/editora.desktop")),
                "the menu entry survived the uninstall");
    }

    @Test
    void aPrefixHoldingSomeoneElsesDirectoryIsRefused() throws IOException {
        Path prefix = root.resolve("srv");
        Path foreign = prefix.resolve("editora");
        Files.createDirectories(foreign);
        Files.writeString(foreign.resolve("thesis.tex"), "irreplaceable");
        for (String[] args : List.of(
                new String[] {"--user", "--prefix", prefix.toString()},
                new String[] {"--user", "--prefix", prefix.toString(), "--uninstall"})) {
            Proc.Result result = install(args);
            String label = String.join(" ", args);
            assertNotEquals(0, result.code(), label);
            assertContains(result.err(), "refusing");
            assertEquals("irreplaceable", Files.readString(foreign.resolve("thesis.tex")), label);
        }
    }

    @Test
    void anEmptyDirectoryAtThePrefixIsAccepted() throws IOException {
        Path prefix = root.resolve("apps");
        Files.createDirectories(prefix.resolve("editora"));
        Proc.Result result = install("--user", "--prefix", prefix.toString());
        assertEquals(0, result.code(), result.err());
        assertTrue(Files.isRegularFile(prefix.resolve("editora/bin/Editora")), "the launcher was not installed");
    }

    @Test
    void aPrefixWithReservedCharactersIsQuotedInTheExecLine() throws IOException {
        Path prefix = root.resolve("My Apps $x");
        Proc.Result result = install("--user", "--prefix=" + prefix);
        assertEquals(0, result.code(), result.err());
        String entry = desktopEntry();
        // Desktop Entry spec: double-quote the argument and write a literal $ as \\$ (the value's own escaping
        // layer doubles the backslash). The field code stays outside the quotes.
        String expected = prefix.resolve("editora/bin/Editora").toString().replace("$", "\\\\$");
        assertContains(entry, "Exec=\"" + expected + "\" %F\n");
        Path validator = which("desktop-file-validate");
        if (validator != null) {
            Proc.Result check = Proc.run(List.of(
                    validator.toString(), desktopFile(home.resolve(".local/share")).toString()));
            assertEquals(0, check.code(), check.out() + check.err());
        }
    }

    @Test
    void aForeignEditoraCommandIsNotOverwritten() throws IOException {
        Path binary = home.resolve(".local/bin/editora");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "#!/bin/sh\necho some other editora\n");
        Proc.Result result = install("--user");
        assertEquals(0, result.code(), result.err());
        assertFalse(Files.isSymbolicLink(binary), "the foreign command was replaced by a link");
        assertContains(Files.readString(binary), "some other editora");
        assertContains(result.err(), "leaving it alone");
    }

    @Test
    void aSystemInstallIsHandedToRootAndIsNotGroupOrWorldWritable() throws IOException {
        // Stand-ins: `id -u` says root, and `chown` records what it was asked to do.
        Path shims = root.resolve("shims");
        Files.createDirectory(shims);
        Path log = root.resolve("chown.log");
        executable(
                shims.resolve("id"),
                "#!/bin/sh\nif [ \"$1\" = \"-u\" ]; then echo 0; else exec /usr/bin/id \"$@\"; fi\n");
        executable(shims.resolve("chown"), "#!/bin/sh\nprintf \"%s\\n\" \"$*\" >> \"" + log + "\"\n");
        // The image as a user with umask 002 unpacks it: group-writable throughout.
        Path image = bundle.resolve("Editora");
        try (Stream<Path> paths = Files.walk(image)) {
            for (Path path : paths.filter(p -> !p.equals(image)).toList()) {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                permissions.add(PosixFilePermission.GROUP_WRITE);
                permissions.add(PosixFilePermission.OTHERS_WRITE);
                Files.setPosixFilePermissions(path, permissions);
            }
        }
        Path stage = root.resolve("stage");
        Proc.Result result = installWithPath(shims, "--destdir", stage.toString());
        assertEquals(0, result.code(), result.err());
        Path app = stage.resolve("opt/editora");
        assertEquals(List.of("-R -h 0:0 " + app), Files.readAllLines(log));
        List<String> writable;
        try (Stream<Path> paths = Files.walk(app)) {
            writable = paths.filter(p -> !Files.isSymbolicLink(p))
                    .filter(p -> {
                        try {
                            Set<PosixFilePermission> permissions =
                                    Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
                            return permissions.contains(PosixFilePermission.GROUP_WRITE)
                                    || permissions.contains(PosixFilePermission.OTHERS_WRITE);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
        }
        assertEquals(List.of(), writable, "group/other-writable files left in a system install");
        // Staged under --destdir, but the command and the menu entry name the real, un-prefixed paths.
        assertEquals(
                Path.of("/opt/editora/bin/Editora"), Files.readSymbolicLink(stage.resolve("usr/local/bin/editora")));
        assertContains(desktopEntry(stage.resolve("usr/share")), "Exec=/opt/editora/bin/Editora %F\n");
    }

    @Test
    void aUserInstallIsNotChowned() throws IOException {
        Path shims = root.resolve("shims");
        Files.createDirectory(shims);
        Path log = root.resolve("chown.log");
        executable(shims.resolve("chown"), "#!/bin/sh\nprintf \"%s\\n\" \"$*\" >> \"" + log + "\"\n");
        assertEquals(0, installWithPath(shims, "--user").code(), "install");
        assertFalse(Files.exists(log), "a user install ran chown");
    }

    @Test
    void helpPrintsOnlyTheUsageHeader() {
        Proc.Result result = install("--help");
        assertEquals(0, result.code(), result.err());
        assertContains(result.out(), "--prefix DIR");
        assertNotContains(result.out(), "set -eu");
        assertNotContains(result.out(), "PROG=");
    }

    /** The first executable called {@code name} on PATH, or null. */
    private static Path which(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isEmpty()) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}
