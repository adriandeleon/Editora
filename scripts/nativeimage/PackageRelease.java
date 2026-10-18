package nativeimage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lib.Proc;
import lib.Repo;
import lib.TempDir;

/**
 * Bundle one host-built experimental Native Image and its adjacent shared libraries.
 *
 * <pre>
 *   java scripts/nativeimage/PackageRelease.java target/editora-native 1.2.3 linux-x64 staging
 * </pre>
 *
 * Windows targets become a {@code .zip} (with a CRLF {@code .cmd} launcher); Linux and macOS targets a
 * {@code .tar.gz} written by the system {@code tar}, which those legs run on, so the launcher keeps mode 0755.
 */
public final class PackageRelease {

    private static final Set<String> TARGETS = Set.of("linux-x64", "macos-x64", "macos-arm64", "windows-x64");

    private static final String SHELL_LAUNCHER = """
            #!/usr/bin/env bash
            set -euo pipefail
            bundle="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
            export EDITORA_CONFIG_DIR="${EDITORA_NATIVE_CONFIG_DIR:-%s}"
            exec "$bundle/editora-native" -XX:MissingRegistrationReportingMode=Exit -Xmx2g -Xms64m %s"$@"
            """;

    private static final String WINDOWS_LAUNCHER = """
            @echo off
            setlocal
            if not defined EDITORA_NATIVE_CONFIG_DIR set "EDITORA_NATIVE_CONFIG_DIR=%APPDATA%\\EditoraNativeExperimental"
            set "EDITORA_CONFIG_DIR=%EDITORA_NATIVE_CONFIG_DIR%"
            "%~dp0editora-native.exe" -XX:MissingRegistrationReportingMode=Exit -Xmx2g -Xms64m %*
            exit /b %errorlevel%
            """;

    private static final String README = """
            Editora experimental Native Image (%s)

            From this extracted directory, run:
              %s [path/to/file]

            The launcher uses separate settings. Set EDITORA_NATIVE_CONFIG_DIR to choose
            another directory. Keep all adjacent shared libraries beside the binary.
            No Java installation is needed. This is a portable archive, not an installer.
            The build is unsigned; macOS may require removing quarantine after inspection.

            This build is experimental. Benchmarks found slower tokenization and input
            latency than the JVM build. External Java plugins cannot load into this
            closed-world image. LSP, debugging, SSH and other peripheral features have
            not been qualified. Keep the ordinary Editora release available for daily use.

            Experiment and measured results:
            https://github.com/adriandeleon/Editora/blob/master/docs/native-image-staticfx.md
            """;

    private PackageRelease() {}

    /** Python's {@code Path.suffix}: the last extension with its dot, or "" for none or a leading dot only. */
    private static String suffix(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot);
    }

    static Path pack(Path binary, String version, String target, Path output) throws IOException {
        if (!version.matches("[0-9A-Za-z][0-9A-Za-z.+-]*")) {
            throw new IllegalArgumentException("invalid version");
        }
        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("unsupported native target");
        }
        binary = binary.toRealPath();
        if (!Files.isRegularFile(binary)) {
            throw new IllegalArgumentException("native executable is not a file");
        }
        boolean windows = target.startsWith("windows-");
        if (windows != suffix(binary).toLowerCase(Locale.ROOT).equals(".exe")) {
            throw new IllegalArgumentException("native executable extension does not match target");
        }
        String extension = windows ? ".dll" : target.startsWith("macos-") ? ".dylib" : ".so";
        List<Path> libraries;
        try (Stream<Path> siblings = Files.list(binary.getParent())) {
            libraries = siblings.filter(p -> Files.isRegularFile(p) && suffix(p).equals(extension))
                    .sorted()
                    .toList();
        }
        // Native Image can produce a stand-alone Windows/macOS executable. Linux's AWT
        // path is known to emit adjacent .so files and needs them in the archive.
        if (target.equals("linux-x64") && libraries.isEmpty()) {
            throw new IllegalArgumentException("Native Image emitted no adjacent .so libraries");
        }
        String name = "Editora-" + version + "-" + target + "-native-experimental";
        Files.createDirectories(output);
        try (var scratch = new TempDir()) {
            Path root = scratch.resolve(name);
            Files.createDirectory(root);
            copy(binary, root.resolve(windows ? "editora-native.exe" : "editora-native"));
            for (Path library : libraries) {
                copy(library, root.resolve(library.getFileName().toString()));
            }
            if (windows) {
                Files.writeString(root.resolve("run-editora-native.cmd"), WINDOWS_LAUNCHER.replace("\n", "\r\n"));
            } else {
                String config = target.equals("linux-x64")
                        ? "${XDG_CONFIG_HOME:-$HOME/.config}/editora-native-experimental"
                        : "${HOME}/Library/Application Support/EditoraNativeExperimental";
                Path launcher = root.resolve("run-editora-native");
                // GitHub's virtual Apple GPU crashes in the Metal pipeline on arm64.
                // Ship the same software pipeline that the archive smoke test uses.
                String prism = target.equals("macos-arm64") ? "-Dprism.order=sw " : "";
                TextFiles.write(launcher, SHELL_LAUNCHER.formatted(config, prism));
                Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
            String readme = README.formatted(target, windows ? "run-editora-native.cmd" : "./run-editora-native");
            if (target.equals("macos-arm64")) {
                readme += """

                        This Apple Silicon launcher uses JavaFX software rendering because the
                        hosted runner's virtual Metal GPU crashes at startup. Rendering on
                        a physical Apple Silicon Mac has not been qualified.
                        """;
            }
            TextFiles.write(root.resolve("README.txt"), readme);
            // The licence texts travel with every archive: Editora's own (MIT) and the notice
            // naming the bundled third-party software, including the static JavaFX libraries
            // that only this build links in.
            for (String document : List.of("LICENSE", "NOTICE")) {
                copy(Repo.root().resolve(document), root.resolve(document));
            }
            Path archive = output.resolve(name + (windows ? ".zip" : ".tar.gz"));
            if (windows) {
                zip(root, name, archive);
            } else {
                // COPYFILE_DISABLE keeps macOS tar from adding AppleDouble ._ entries for extended attributes.
                Proc.Result tar = Proc.run(
                        List.of("tar", "-czf", archive.toAbsolutePath().toString(),
                                "-C", scratch.path().toString(), name),
                        Map.of("COPYFILE_DISABLE", "1"),
                        null,
                        false);
                if (tar.code() != 0) {
                    throw new IOException("tar exited " + tar.code() + ": " + tar.err().strip());
                }
            }
            return archive;
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void zip(Path root, String name, Path archive) throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(root)) {
            files = listing.sorted().toList();
        }
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (Path file : files) {
                var entry = new ZipEntry(name + "/" + file.getFileName());
                entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    public static void main(String[] argv) throws IOException {
        var p = new Args(
                "PackageRelease.java [-h] binary version target output",
                "Bundle one host-built experimental Native Image and its adjacent shared libraries.",
                argv,
                List.of(),
                List.of(),
                List.of("binary", "version", "target", "output"));
        System.out.println(pack(Path.of(p.positional(0)), p.positional(1), p.positional(2), Path.of(p.positional(3))));
    }
}
