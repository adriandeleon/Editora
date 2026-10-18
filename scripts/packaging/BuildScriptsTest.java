package packaging;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;
import static lib.Assert.assertFalse;
import static lib.Assert.assertMatches;
import static lib.Assert.assertNotContains;
import static lib.Assert.assertNotEquals;
import static lib.Assert.assertTrue;
import static lib.Assert.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import lib.Proc;
import lib.Repo;
import lib.TempDir;
import lib.Test;

/**
 * Tests for the shell scripts in {@code scripts/} that build release artifacts or manage worktrees.
 *
 * <p>They run the real scripts against throwaway directories. Nothing is downloaded: {@code curl} is replaced by
 * a stand-in first on PATH, so the AppImage tests exercise the verify-before-run logic without the network and
 * without executing anything fetched from it.
 *
 * <p>Each group of tests skips on its own: the AppImage tests need Linux on x86_64 or aarch64 with
 * {@code sha256sum}, the tarball test needs Linux (GNU tar), and the worktree tests need git and a POSIX shell.
 */
class BuildScriptsTest implements AutoCloseable {

    private static final Path SCRIPTS = Repo.scripts();
    private static final Path REPO = Repo.root();
    private static final boolean LINUX = System.getProperty("os.name").equals("Linux");
    private static final boolean POSIX = !System.getProperty("os.name").startsWith("Windows");

    // What the stand-in curl "downloads": a fake appimagetool that records how it was called and writes the
    // output file, and a fake runtime.
    private static final String FAKE_TOOL =
            """
            #!/bin/sh
            printf '%s\\n' "$@" > "$FAKE_TOOL_LOG"
            for last; do :; done
            ls "$4/usr/share/licenses/editora" > "$FAKE_TOOL_LOG.licenses" 2>/dev/null
            echo appimage > "$last"
            """;
    private static final String FAKE_RUNTIME = "runtime-bytes\n";
    private static final String FAKE_CURL =
            """
            #!/bin/sh
            # curl -fsSL --retry 3 -o <dest> <url>
            while [ $# -gt 0 ]; do
              case "$1" in
                -o) dest="$2"; shift ;;
                http*) url="$1" ;;
              esac
              shift
            done
            printf '%s\\n' "$url" >> "$FAKE_CURL_LOG"
            case "$url" in
              *appimagetool*) cp "$FAKE_PAYLOADS/tool" "$dest" ;;
              *) cp "$FAKE_PAYLOADS/runtime" "$dest" ;;
            esac
            """;

    private final TempDir scratch = new TempDir();
    private final Path root = scratch.path();

    @Override
    public void close() {
        scratch.close();
    }

    // ---- build-appimage.sh ------------------------------------------------------------------------------

    /** The AppImage fixture: a fake image, an icon, the curl stand-in and its payloads. */
    private final class AppImage {
        final Path image;
        final Path icon = root.resolve("icon.png");
        final Path shims = root.resolve("shims");
        final Path payloads = root.resolve("payloads");
        final Path toolLog = root.resolve("tool.log");
        final Path curlLog = root.resolve("curl.log");
        final Path out = root.resolve("dist");

        AppImage() throws IOException {
            String machine = LINUX ? machine() : "";
            assumeTrue(
                    LINUX && (machine.equals("x86_64") || machine.equals("aarch64")) && which("sha256sum") != null,
                    "build-appimage.sh only runs on the Linux release legs");
            image = fakeAppImage(root);
            Files.write(icon, "png".getBytes(StandardCharsets.UTF_8));
            Files.createDirectory(shims);
            Files.createDirectory(payloads);
            executable(shims.resolve("curl"), FAKE_CURL);
            Files.writeString(payloads.resolve("tool"), FAKE_TOOL);
            Files.writeString(payloads.resolve("runtime"), FAKE_RUNTIME);
        }

        Proc.Result run(Path script) {
            var env = Map.of(
                    "PATH", shims + java.io.File.pathSeparator + System.getenv("PATH"),
                    "FAKE_PAYLOADS", payloads.toString(),
                    "FAKE_TOOL_LOG", toolLog.toString(),
                    "FAKE_CURL_LOG", curlLog.toString(),
                    "TMPDIR", root.toString());
            return Proc.run(
                    List.of("bash", script.toString(), image.toString(), icon.toString(), out.toString()),
                    env,
                    null,
                    false);
        }
    }

    @Test
    void aDownloadThatDoesNotMatchItsPinnedHashIsNeverRun() throws IOException {
        var fixture = new AppImage();
        // The real script, with its real pinned hashes: the stand-in payloads cannot match them.
        Proc.Result result = fixture.run(SCRIPTS.resolve("build-appimage.sh"));
        assertNotEquals(0, result.code(), "exit code");
        assertContains(result.err(), "CHECKSUM MISMATCH");
        assertFalse(Files.exists(fixture.toolLog), "the unverified appimagetool was executed");
        if (Files.exists(fixture.out)) {
            try (Stream<Path> files = Files.list(fixture.out)) {
                assertEquals(
                        List.of(),
                        files.filter(p -> p.getFileName().toString().endsWith(".AppImage"))
                                .toList());
            }
        }
    }

    @Test
    void theDownloadsAreVersionedReleasesNotTheRollingOne() throws IOException {
        var fixture = new AppImage();
        fixture.run(SCRIPTS.resolve("build-appimage.sh"));
        List<String> urls = words(Files.readString(fixture.curlLog));
        assertEquals(1, urls.size(), "the script must stop at the first mismatch");
        assertMatches("/AppImage/appimagetool/releases/download/\\d[\\d.]*/appimagetool-", urls.get(0));
        assertNotContains(urls.get(0), "continuous");
    }

    @Test
    void verifiedDownloadsBuildTheImageWithThePinnedRuntimeAndTheLicences() throws IOException {
        var fixture = new AppImage();
        // A copy of the script whose pinned hashes are those of the stand-in payloads. It keeps the scripts/ +
        // repository-root layout, because the script finds LICENSE and NOTICE relative to itself.
        String text = Files.readString(SCRIPTS.resolve("build-appimage.sh"));
        String arch = machine();
        String block = text.substring(text.indexOf("  " + arch + ")") + arch.length() + 3);
        block = block.substring(0, block.indexOf(";;"));
        String toolHash = between(block, "APPIMAGETOOL_SHA256=\"", "\"");
        String runtimeHash = between(block, "TYPE2_RUNTIME_SHA256=\"", "\"");
        text = text.replace(toolHash, sha256(FAKE_TOOL)).replace(runtimeHash, sha256(FAKE_RUNTIME));
        Path copy = root.resolve("repo").resolve("scripts");
        Files.createDirectories(copy);
        Files.writeString(copy.resolve("build-appimage.sh"), text);
        for (String document : List.of("LICENSE", "NOTICE")) {
            Files.copy(REPO.resolve(document), copy.getParent().resolve(document));
        }

        Proc.Result result = fixture.run(copy.resolve("build-appimage.sh"));
        assertEquals(0, result.code(), result.err());
        List<String> arguments = Files.readAllLines(fixture.toolLog);
        assertEquals("--no-appstream", arguments.get(0));
        assertEquals("--runtime-file", arguments.get(1));
        assertTrue(arguments.get(2).endsWith("/runtime-" + arch), arguments.toString());
        assertTrue(arguments.get(3).endsWith("/Editora.AppDir"), arguments.toString());
        assertEquals(fixture.out.resolve("Editora-" + arch + ".AppImage").toString(), arguments.get(4));
        assertEquals(
                List.of("LICENSE", "NOTICE"), words(Files.readString(Path.of(fixture.toolLog + ".licenses"))));
        List<String> urls = words(Files.readString(fixture.curlLog));
        assertEquals(2, urls.size());
        assertMatches("/AppImage/type2-runtime/releases/download/\\d+/runtime-", urls.get(1));
        assertTrue(Files.isRegularFile(fixture.out.resolve("Editora-" + arch + ".AppImage")), "no AppImage built");
    }

    @Test
    void anArchitectureWithoutPinnedHashesIsRefused() throws IOException {
        var fixture = new AppImage();
        executable(fixture.shims.resolve("uname"), "#!/bin/sh\necho riscv64\n");
        Proc.Result result = fixture.run(SCRIPTS.resolve("build-appimage.sh"));
        assertNotEquals(0, result.code(), "exit code");
        assertContains(result.err(), "refusing to build");
        assertFalse(Files.exists(fixture.curlLog), "nothing may be downloaded for an unpinned architecture");
    }

    // ---- build-tarball.sh -------------------------------------------------------------------------------

    @Test
    void theTarballCarriesTheImageTheInstallerAndTheLicencesInAStableOrder() throws IOException {
        assumeTrue(LINUX, "build-tarball.sh needs GNU tar (the Linux release legs)");
        Path image = fakeAppImage(root);
        Proc.Result result = Proc.run(
                List.of("bash", SCRIPTS.resolve("build-tarball.sh").toString(), image.toString(),
                        root.resolve("dist").toString()),
                Map.of("TMPDIR", root.toString()),
                null,
                false);
        assertEquals(0, result.code(), result.err());
        String arch = machine();
        List<TarMember> members = readTarGz(root.resolve("dist").resolve("Editora-" + arch + ".tar.gz"));
        List<String> names = members.stream().map(TarMember::name).toList();
        String top = "editora-" + arch;
        for (String expected : List.of(
                "Editora/bin/Editora", "Editora/lib/app/editora.aot", "install.sh", "README.txt", "LICENSE",
                "NOTICE")) {
            assertContains(names, top + "/" + expected, null);
        }
        assertEquals(
                names.stream().sorted().toList(), names, "members are written in name order, not readdir order");
        assertTrue(members.stream().allMatch(m -> m.uid() == 0 && m.gid() == 0), "archive members are root-owned");
        TarMember install = members.stream()
                .filter(m -> m.name().equals(top + "/install.sh"))
                .findFirst()
                .orElseThrow();
        assertTrue((install.mode() & 0111) != 0, "install.sh must be executable");
    }

    /** One entry of a tar archive: its name (a directory's without the trailing slash), owner and mode. */
    private record TarMember(String name, long uid, long gid, int mode) {}

    /**
     * Reads the member list of a {@code .tar.gz}: 512-byte ustar headers, with GNU long names
     * ({@code ././@LongLink}, type {@code L}) and pax {@code path} records applied to the next member.
     */
    private static List<TarMember> readTarGz(Path archive) throws IOException {
        var members = new ArrayList<TarMember>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) {
            String longName = null;
            while (true) {
                byte[] header = in.readNBytes(512);
                if (header.length < 512 || isZero(header)) {
                    break;
                }
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                byte[] data = in.readNBytes((int) size);
                in.skipNBytes((512 - size % 512) % 512);
                switch (type) {
                    case 'L' -> longName = cString(data, 0, data.length);
                    case 'x' -> {
                        String path = paxPath(data);
                        if (path != null) {
                            longName = path;
                        }
                    }
                    case 'g', 'K' -> {}
                    default -> {
                        String name = longName;
                        if (name == null) {
                            name = cString(header, 0, 100);
                            if ("ustar".equals(cString(header, 257, 6)) && header[345] != 0) {
                                name = cString(header, 345, 155) + "/" + name;
                            }
                        }
                        longName = null;
                        // Python's tarfile, which the original test used, reports directories without the slash.
                        while (name.length() > 1 && name.endsWith("/")) {
                            name = name.substring(0, name.length() - 1);
                        }
                        members.add(new TarMember(
                                name, octal(header, 108, 8), octal(header, 116, 8), (int) octal(header, 100, 8)));
                    }
                }
            }
        }
        return members;
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** A numeric header field: octal digits, or GNU base-256 when the high bit of the first byte is set. */
    private static long octal(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = header[offset] & 0x7f;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (header[offset + i] & 0xff);
            }
            return value;
        }
        String digits = cString(header, offset, length).trim();
        return digits.isEmpty() ? 0 : Long.parseLong(digits, 8);
    }

    private static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    /** The {@code path} value of a pax extended header ({@code "<len> path=<value>\n"} records), or null. */
    private static String paxPath(byte[] data) {
        int at = 0;
        while (at < data.length) {
            int space = at;
            while (space < data.length && data[space] != ' ') {
                space++;
            }
            if (space >= data.length) {
                break;
            }
            int length = Integer.parseInt(new String(data, at, space - at, StandardCharsets.US_ASCII));
            String record = new String(data, space + 1, length - (space - at) - 2, StandardCharsets.UTF_8);
            if (record.startsWith("path=")) {
                return record.substring("path=".length());
            }
            at += length;
        }
        return null;
    }

    // ---- worktree.sh ------------------------------------------------------------------------------------

    /** The worktree fixture: a repository with one commit at {@code <scratch>/Project}. */
    private final class Worktrees {
        final Path main = root.resolve("Project");
        final Map<String, String> env = new HashMap<>(Map.of(
                "GIT_AUTHOR_NAME", "t",
                "GIT_AUTHOR_EMAIL", "t@example.invalid",
                "GIT_COMMITTER_NAME", "t",
                "GIT_COMMITTER_EMAIL", "t@example.invalid",
                "GIT_CONFIG_GLOBAL", "/dev/null",
                "GIT_CONFIG_SYSTEM", "/dev/null"));

        Worktrees() throws IOException {
            assumeTrue(which("git") != null && POSIX, "worktree.sh needs git and bash");
            Files.createDirectory(main);
            git(main, "init", "-q", "-b", "master");
            Files.writeString(main.resolve("file.txt"), "one\n");
            git(main, "add", "file.txt");
            git(main, "commit", "-q", "-m", "first");
        }

        String git(Path cwd, String... args) {
            var command = new ArrayList<String>(List.of("git"));
            command.addAll(Arrays.asList(args));
            Proc.Result result = Proc.run(command, env, cwd, false);
            if (result.code() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " exited " + result.code() + ": "
                        + result.err().strip());
            }
            return result.out();
        }

        Proc.Result worktree(Path cwd, String... args) {
            var command = new ArrayList<String>(List.of("bash", SCRIPTS.resolve("worktree.sh").toString()));
            command.addAll(Arrays.asList(args));
            return Proc.run(command, env, cwd, false);
        }
    }

    @Test
    void worktreesAlwaysGoBesideTheMainCheckout() throws IOException {
        var repo = new Worktrees();
        Proc.Result first = repo.worktree(repo.main, "new", "feat/one", "HEAD");
        assertEquals(0, first.code(), first.err());
        Path one = root.resolve("Project-worktrees").resolve("feat-one");
        assertTrue(Files.isRegularFile(one.resolve("file.txt")), "feat-one was not created beside Project");

        // Run from INSIDE that worktree: it used to create feat-one-worktrees/feat-two beside it.
        Proc.Result second = repo.worktree(one, "new", "feat/two", "HEAD");
        assertEquals(0, second.code(), second.err());
        assertTrue(
                Files.isRegularFile(root.resolve("Project-worktrees/feat-two/file.txt")),
                "feat-two was not created beside Project");
        assertFalse(Files.exists(root.resolve("Project-worktrees/feat-one-worktrees")), "nested worktrees dir");
        try (Stream<Path> entries = Files.list(root)) {
            assertEquals(
                    List.of("Project", "Project-worktrees"),
                    entries.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void rmKeepsABranchWithUnmergedCommitsUnlessForced() throws IOException {
        var repo = new Worktrees();
        repo.worktree(repo.main, "new", "feat/work", "HEAD");
        Path tree = root.resolve("Project-worktrees").resolve("feat-work");
        Files.writeString(tree.resolve("new.txt"), "unmerged work\n");
        repo.git(tree, "add", "new.txt");
        repo.git(tree, "commit", "-q", "-m", "work");

        Proc.Result kept = repo.worktree(repo.main, "rm", "feat/work");
        assertNotEquals(0, kept.code(), "exit code");
        assertContains(kept.err(), "NOT fully merged");
        assertFalse(Files.exists(tree), "the worktree itself is removed");
        assertTrue(
                repo.git(repo.main, "branch", "--list", "feat/work").contains("feat/work"),
                "the unmerged branch was deleted");

        // A fresh worktree on that surviving branch, removed with --force this time.
        repo.git(repo.main, "worktree", "add", "-q", tree.toString(), "feat/work");
        Proc.Result forced = repo.worktree(repo.main, "rm", "feat/work", "--force");
        assertEquals(0, forced.code(), forced.err());
        assertEquals("", repo.git(repo.main, "branch", "--list", "feat/work").strip());
    }

    @Test
    void rmDeletesAMergedBranch() throws IOException {
        var repo = new Worktrees();
        repo.worktree(repo.main, "new", "feat/done", "HEAD");
        Proc.Result result = repo.worktree(repo.main, "rm", "feat/done");
        assertEquals(0, result.code(), result.err());
        assertEquals("", repo.git(repo.main, "branch", "--list", "feat/done").strip());
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static Path fakeAppImage(Path root) throws IOException {
        Path image = root.resolve("aot-image").resolve("Editora");
        Files.createDirectories(image.resolve("bin"));
        Files.createDirectories(image.resolve("lib/app"));
        executable(image.resolve("bin/Editora"), "#!/bin/sh\n");
        Files.write(image.resolve("lib/app/editora.aot"), "cache".getBytes(StandardCharsets.UTF_8));
        return image;
    }

    private static void executable(Path file, String text) throws IOException {
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /**
     * The machine name as {@code uname -m} prints it, which is what the scripts use: Java's {@code os.arch} says
     * {@code amd64} where they say {@code x86_64}.
     */
    private static String machine() {
        return Proc.check(List.of("uname", "-m")).strip();
    }

    /** The first executable called {@code name} on PATH, or null. */
    private static Path which(String name) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isEmpty()) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start) + start.length();
        return text.substring(from, text.indexOf(end, from));
    }

    private static List<String> words(String text) {
        String stripped = text.strip();
        return stripped.isEmpty() ? List.of() : List.of(stripped.split("\\s+"));
    }
}
