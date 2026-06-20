/// Generates the large/perf samples under samples/perf/ (git-ignored, so they never bloat history).
///
/// A JDK 25 compact source file (JEP 512) — run it directly, from the repo root:
///   java scripts/GenSamples.java             # default 60 MB huge file
///   java scripts/GenSamples.java 120         # scale the huge file to 120 MB
///
/// (It also opens in Editora with a Run ▶ in the gutter — it's a compact source file.)
///
/// Files produced:
///   samples/perf/large-6mb.java  — just over the 5 MB highlight/minimap cutoff
///   samples/perf/huge-<N>mb.txt  — just over the 50 MB read-only/capped-load cutoff (N defaults to 60)

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

void main(String[] args) throws IOException {
    int hugeMb = args.length > 0 ? Integer.parseInt(args[0]) : 60;

    Path samples = Path.of("samples");
    if (!Files.isDirectory(samples)) {
        IO.println("Run from the repo root — no samples/ directory at " + samples.toAbsolutePath());
        System.exit(1);
    }
    Path out = samples.resolve("perf");
    Files.createDirectories(out);

    // ~6 MB of fold-heavy Java (a class per line) — just over the 5 MB cutoff, so highlighting + the
    // minimap auto-disable. Size-driven so the file actually crosses the threshold.
    Path large = out.resolve("large-6mb.java");
    long largeTarget = 6L * 1024 * 1024;
    try (BufferedWriter w = Files.newBufferedWriter(large)) {
        String header = "package perf;\n";
        w.write(header);
        long written = header.length();
        for (int i = 0; written < largeTarget; i++) {
            String cls = "class C" + i + " { int f() { return " + i + "; } }\n";
            w.write(cls);
            written += cls.length();
        }
    }

    // A plain-text file just over the read-only/capped-load threshold.
    Path huge = out.resolve("huge-" + hugeMb + "mb.txt");
    String line = "The quick brown fox jumps over the lazy dog. 0123456789 ABCDEFGHIJKLMNOPQRST\n";
    long target = (long) hugeMb * 1024 * 1024;
    try (BufferedWriter w = Files.newBufferedWriter(huge)) {
        for (long written = 0; written < target; written += line.length()) {
            w.write(line);
        }
    }

    IO.println("Generated:");
    IO.println("  " + (Files.size(large) / 1024) + " KB\t" + large);
    IO.println("  " + (Files.size(huge) / (1024 * 1024)) + " MB\t" + huge);
    IO.println("(samples/perf/ is git-ignored)");
}
