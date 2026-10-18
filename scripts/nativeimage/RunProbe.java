package nativeimage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lib.Json;
import lib.Repo;

/**
 * Run the opt-in EditorBuffer probe with identical arguments on either runtime.
 *
 * <pre>
 *   java scripts/nativeimage/RunProbe.java                                        HotSpot, headless
 *   java scripts/nativeimage/RunProbe.java --native target/editora-editor-probe   Native Image
 *   java scripts/nativeimage/RunProbe.java --print-command                        argv JSON for a benchmark config
 * </pre>
 */
public final class RunProbe {

    private RunProbe() {}

    /** Python's {@code Path.resolve()}: absolute, with symbolic links resolved as far as the path exists. */
    static Path resolve(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** The probe's argv for these arguments; {@code p.error} exits on a usage problem. */
    static List<String> command(Args p) throws IOException {
        String javaHome = System.getenv("JAVA_HOME");
        String java = p.get("--java", javaHome != null ? Path.of(javaHome, "bin", "java").toString() : "java");
        String sizes = p.get("--sizes", "102400,1048576,5242880,10485760");
        int edits = p.integer("--edits", 300);
        Path nativeProbe = p.path("--native");
        Path trace = p.path("--trace");
        var options = new ArrayList<>(List.of("-Xmx2g", "-Xms64m"));
        if (!p.flag("--desktop")) {
            options.addAll(List.of("-Dglass.platform=Headless", "-Dprism.order=sw"));
        }
        var command = new ArrayList<String>();
        if (nativeProbe != null) {
            if (trace != null) {
                p.error("tracing agent runs on the JVM, not the native executable");
            }
            command.add(resolve(nativeProbe).toString());
            command.add("-XX:MissingRegistrationReportingMode=Exit");
            command.addAll(options);
        } else {
            Path root = Repo.root();
            Path classpath = root.resolve("target/probe-classpath.txt");
            if (!Files.exists(classpath)) {
                p.error("first run mvn -Peditor-probe compile dependency:build-classpath"
                        + " -Dmdep.outputFile=target/probe-classpath.txt");
            }
            command.addAll(List.of(java, "--enable-native-access=ALL-UNNAMED", "-XX:+UseG1GC"));
            command.addAll(options);
            if (trace != null) {
                Benchmark.mkdirsNew(trace);
                command.add("-agentlib:native-image-agent=config-output-dir=" + resolve(trace));
            }
            command.addAll(List.of(
                    "-cp",
                    root.resolve("target/classes") + File.pathSeparator + Files.readString(classpath).strip(),
                    "com.editora.experiment.EditorProbeLauncher"));
        }
        command.add("--sizes=" + sizes);
        command.add("--edits=" + edits);
        return command;
    }

    public static void main(String[] argv) throws IOException, InterruptedException {
        var p = new Args(
                "RunProbe.java [-h] [--native NATIVE] [--java JAVA] [--sizes SIZES] [--edits EDITS] [--desktop]"
                        + " [--trace TRACE] [--print-command]",
                """
                Run the opt-in EditorBuffer probe with identical arguments on either runtime.

                  --native NATIVE   native probe executable; default is HotSpot
                  --desktop         use real display instead of Headless/software
                  --trace TRACE     unreviewed agent output directory (GraalVM JVM only)
                  --print-command   emit argv JSON for benchmark config""",
                argv,
                List.of("--native", "--java", "--sizes", "--edits", "--trace"),
                List.of("--desktop", "--print-command"),
                List.of());
        List<String> command = command(p);
        if (p.flag("--print-command")) {
            System.out.println(Json.write(command));
        } else {
            // Python exec'd the probe in place; a child with this process's streams is the closest equivalent.
            System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
        }
    }
}
