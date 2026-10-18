package nativeimage;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;
import static lib.Assert.assertNotContains;
import static lib.Assert.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;
import lib.Test;
import lib.TempDir;

class PackageReleaseTest {

    /** A member of a tar archive: its permission bits and contents. */
    private record Member(int mode, byte[] data) {}

    @Test
    void platformArchivesIncludeLauncherAndAdjacentLibrary() throws Exception {
        for (List<String> testCase : List.of(
                List.of("linux-x64", "", "libawt.so"),
                List.of("macos-arm64", "", "libawt.dylib"),
                List.of("windows-x64", ".exe", "awt.dll"))) {
            String target = testCase.get(0);
            String library = testCase.get(2);
            try (var scratch = new TempDir()) {
                Path root = scratch.path();
                Path binary = root.resolve("editora-native" + testCase.get(1));
                Files.writeString(binary, "binary");
                Files.writeString(root.resolve(library), "library");
                Path archive = PackageRelease.pack(binary, "0.18.6-SNAPSHOT", target, root.resolve("out"));
                String name = "Editora-0.18.6-SNAPSHOT-" + target + "-native-experimental";
                List<String> members;
                if (target.startsWith("windows-")) {
                    String launcher;
                    try (var bundle = new ZipFile(archive.toFile())) {
                        members = bundle.stream().map(e -> e.getName()).toList();
                        var entry = bundle.getEntry(name + "/run-editora-native.cmd");
                        try (InputStream in = bundle.getInputStream(entry)) {
                            launcher = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                    assertContains(launcher, "%APPDATA%");
                    assertContains(launcher, "\r\nexit /b %errorlevel%\r\n");
                    assertContains(members, name + "/editora-native.exe", target);
                } else {
                    Map<String, Member> tar = readTarGz(archive);
                    members = List.copyOf(tar.keySet());
                    Member launcher = tar.get(name + "/run-editora-native");
                    String text = new String(launcher.data(), StandardCharsets.UTF_8);
                    assertContains(text, "EDITORA_CONFIG_DIR");
                    assertEquals(0755, launcher.mode() & 0777, target + " launcher mode");
                    assertContains(members, name + "/editora-native", target);
                    if (target.equals("macos-arm64")) {
                        assertContains(text, "-Dprism.order=sw");
                    } else {
                        assertNotContains(text, "-Dprism.order=sw");
                    }
                }
                assertContains(members, name + "/" + library, target);
                // Every archive carries Editora's licence and the third-party notice.
                for (String document : List.of("LICENSE", "NOTICE")) {
                    assertContains(members, name + "/" + document, target);
                }
            }
        }
    }

    @Test
    void linuxRejectsMissingSharedLibraries() throws Exception {
        try (var scratch = new TempDir()) {
            Path binary = scratch.resolve("editora-native");
            Files.writeString(binary, "binary");
            var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> PackageRelease.pack(binary, "1.0.0", "linux-x64", scratch.resolve("out")));
            assertContains(error.getMessage(), "no adjacent");
        }
    }

    /**
     * The members of a gzip'd tar archive by name (without a directory's trailing slash). Reads ustar headers,
     * GNU long names and pax path records, which is what GNU tar and bsdtar write.
     */
    private static Map<String, Member> readTarGz(Path archive) throws IOException {
        var members = new LinkedHashMap<String, Member>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) {
            String longName = null;
            while (true) {
                byte[] header = in.readNBytes(512);
                if (header.length < 512 || header[0] == 0) {
                    return members;
                }
                String name = field(header, 0, 100);
                String prefix = field(header, 345, 155);
                if (!prefix.isEmpty() && header[257] == 'u') {
                    name = prefix + "/" + name;
                }
                int mode = (int) octal(header, 100, 8);
                long size = octal(header, 124, 12);
                byte type = header[156];
                byte[] data = in.readNBytes((int) size);
                in.readNBytes((int) ((512 - size % 512) % 512));
                if (type == 'L') {
                    longName = field(data, 0, data.length);
                    continue;
                }
                if (type == 'x') {
                    longName = paxPath(data);
                    continue;
                }
                if (type == 'g') {
                    continue;
                }
                if (longName != null) {
                    name = longName;
                    longName = null;
                }
                members.put(name.endsWith("/") ? name.substring(0, name.length() - 1) : name, new Member(mode, data));
            }
        }
    }

    private static String field(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] bytes, int offset, int length) {
        String text = field(bytes, offset, length).strip();
        return text.isEmpty() ? 0 : Long.parseLong(text, 8);
    }

    /** The {@code path} record of a pax extended header ({@code "<len> path=<value>\n"}), or null. */
    private static String paxPath(byte[] data) {
        for (String record : new String(data, StandardCharsets.UTF_8).split("\n")) {
            int space = record.indexOf(' ');
            if (space > 0 && record.startsWith("path=", space + 1)) {
                return record.substring(space + 1 + "path=".length());
            }
        }
        return null;
    }
}
