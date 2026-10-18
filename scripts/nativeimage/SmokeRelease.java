package nativeimage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;
import lib.Json;
import lib.Repo;

/**
 * Extract and exercise the exact experimental archive before publishing it.
 *
 * <pre>
 *   java scripts/nativeimage/SmokeRelease.java staging/Editora-1.2.3-linux-x64-native-experimental.tar.gz \
 *       linux-x64 /new/smoke/directory
 * </pre>
 */
public final class SmokeRelease {

    private SmokeRelease() {}

    /** A command that exited nonzero or ran out of time. */
    static final class Failed extends Exception {
        Failed(String message) {
            super(message);
        }
    }

    /** Runs {@code command} with this process's streams; returns its exit code, or throws when it times out. */
    private static int run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException, Failed {
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            ProcessTree.kill(process);
            throw new Failed("Command " + command + " timed out after " + timeoutSeconds + " seconds");
        }
        return process.exitValue();
    }

    private static void check(List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException, Failed {
        int code = run(command, timeoutSeconds);
        if (code != 0) {
            throw new Failed("Command " + command + " returned non-zero exit status " + code + ".");
        }
    }

    private static List<String> probe(Path config, Path output) {
        return List.of(
                ProcessTree.javaLauncher(),
                Repo.scripts().resolve("nativeimage/AppProbe.java").toString(),
                config.toString(),
                "--output",
                output.toString(),
                "--runs",
                "1",
                "--warmups",
                "0",
                "--cycles",
                "3");
    }

    /** Each run directory's {@code app.log} under {@code dir}, sorted; none when {@code dir} does not exist. */
    private static List<Path> appLogs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> runs = Files.list(dir)) {
            return runs.map(run -> run.resolve("app.log")).filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static void extractZip(Path archive, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("zip entry outside the extraction directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target);
                }
            }
        }
    }

    static void smoke(Path archive, String target, Path output) throws IOException, InterruptedException, Failed {
        String fileName = archive.getFileName().toString();
        String name = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
        name = name.endsWith(".tar.gz") ? name.substring(0, name.length() - 7) : name;
        if (!name.endsWith("-" + target + "-native-experimental")) {
            throw new IllegalArgumentException("archive name does not match target");
        }
        Path extracted = output.resolve("bundle");
        Benchmark.mkdirsNew(extracted);
        if (fileName.endsWith(".zip")) {
            extractZip(archive, extracted);
        } else {
            check(List.of("tar", "-xzf", archive.toString(), "-C", extracted.toString()), 600);
        }
        Path root = extracted.resolve(name);
        boolean windows = target.startsWith("windows-");
        Path launcher = root.resolve(windows ? "run-editora-native.cmd" : "run-editora-native");
        Path binary = root.resolve(windows ? "editora-native.exe" : "editora-native");
        List<String> application;
        if (windows) {
            check(List.of("cmd", "/c", launcher.toString(), "--version"), 30);
            // The .cmd launcher is separately checked above. The GUI probe starts
            // the exact bundled executable so process teardown owns the app PID.
            // GitHub's Windows runner has no interactive desktop. StaticFX's
            // headless toolkit still runs the real FXML/editor workflow in-process.
            application = List.of(binary.toString(), "-XX:MissingRegistrationReportingMode=Exit", "-Xmx2g", "-Xms64m",
                    "-Dglass.platform=Headless", "-Dprism.order=sw", "-Deditora.debug.stderr=true");
        } else {
            check(List.of(launcher.toString(), "--version"), 30);
            application = List.of(launcher.toString());
        }
        Path config = output.resolve("config.json");
        TextFiles.write(config, Json.write(Map.of("modes", Map.of("native", Map.of("application", application)))));
        try {
            check(probe(config, output.resolve("probe")), 240);
        } catch (Failed failure) {
            for (Path log : appLogs(output.resolve("probe"))) {
                System.err.println("===== " + log + " (last 100 lines) =====");
                List<String> lines = TextFiles.lines(TextFiles.read(log));
                System.err.println(String.join("\n", lines.subList(Math.max(0, lines.size() - 100), lines.size())));
            }
            if (!windows) {
                // A second, diagnostic-only run can expose later missing metadata
                // without another 10-25 minute hosted Native Image compilation.
                Path warnConfig = output.resolve("warn-config.json");
                List<String> warnApplication = List.of(
                        binary.toString(), "-XX:MissingRegistrationReportingMode=Warn", "-Xmx2g", "-Xms64m");
                TextFiles.write(warnConfig,
                        Json.write(Map.of("modes", Map.of("native", Map.of("application", warnApplication)))));
                try {
                    run(probe(warnConfig, output.resolve("warn-probe")), 240);
                } catch (Failed timedOut) {
                    System.err.println("Warning-mode diagnostic timed out");
                }
                for (Path log : appLogs(output.resolve("warn-probe"))) {
                    var warnings = new LinkedHashSet<String>();
                    for (String line : TextFiles.lines(TextFiles.read(log))) {
                        if (line.contains("Missing") && line.contains("RegistrationError:")) {
                            warnings.add(line);
                        }
                    }
                    System.err.println("===== " + log + " missing registrations =====");
                    System.err.println(String.join("\n", new ArrayList<>(warnings)));
                }
            }
            throw failure;
        }
    }

    public static void main(String[] argv) throws IOException, InterruptedException {
        var p = new Args(
                "SmokeRelease.java [-h] archive target output",
                "Extract and exercise the exact experimental archive before publishing it.",
                argv,
                List.of(),
                List.of(),
                List.of("archive", "target", "output"));
        Path output = Path.of(p.positional(2)).toAbsolutePath().normalize();
        Benchmark.mkdirsNew(output);
        try {
            smoke(Path.of(p.positional(0)).toRealPath(), p.positional(1), output);
        } catch (Failed failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        }
    }
}
