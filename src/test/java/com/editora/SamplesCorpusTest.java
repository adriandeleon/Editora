package com.editora;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the manual sample corpus under {@code samples/} from rotting. It is not a behavioral test — it
 * only checks the manifest stays in sync with the files on disk, so a contributor can't add a sample
 * without documenting it (or delete one and leave a dangling link):
 *
 * <ul>
 *   <li>every committed sample is listed in {@code samples/README.md};</li>
 *   <li>every {@code samples/...} path referenced in the README actually exists;</li>
 *   <li>the core (LSP-served) languages each have a syntax sample.</li>
 * </ul>
 *
 * Runs against the source tree (cwd = module root under Maven); skipped gracefully if {@code samples/}
 * isn't present. The generated {@code samples/perf/} tree is git-ignored and excluded.
 */
class SamplesCorpusTest {

    private static final Path SAMPLES = Path.of("samples");
    private static final Path README = SAMPLES.resolve("README.md");
    private static final Pattern REF = Pattern.compile("samples/[\\w./-]+");

    /** Core languages that must always have a syntax sample (the LSP-served, common ones). */
    private static final List<String> MUST_HAVE = List.of(
            "samples/syntax/Sample.java",
            "samples/syntax/sample.py",
            "samples/syntax/sample.ts",
            "samples/syntax/sample.go",
            "samples/syntax/sample.rs",
            "samples/syntax/sample.c",
            "samples/syntax/sample.cpp",
            "samples/syntax/sample.json",
            "samples/syntax/sample.yaml",
            "samples/syntax/sample.xml",
            "samples/syntax/sample.html",
            "samples/syntax/sample.css",
            "samples/syntax/sample.sql",
            "samples/syntax/sample.toml",
            "samples/syntax/sample.sh");

    @Test
    void everyCommittedSampleIsListedInTheReadme() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SAMPLES), "samples/ not present (skipping)");
        String readme = Files.readString(README);
        Set<String> missing = new TreeSet<>();
        for (String rel : committedSamples()) {
            if (!readme.contains(rel)) {
                missing.add(rel);
            }
        }
        if (!missing.isEmpty()) {
            fail("These committed samples are not listed in samples/README.md (add them): " + missing);
        }
    }

    @Test
    void everyReadmeReferenceExists() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SAMPLES), "samples/ not present (skipping)");
        Set<String> dangling = new TreeSet<>();
        Matcher m = REF.matcher(Files.readString(README));
        while (m.find()) {
            String ref = m.group();
            if (ref.endsWith("/") || ref.equals("samples/perf")) {
                continue; // a directory mention (e.g. "samples/perf/"), not a file
            }
            if (!Files.isRegularFile(Path.of(ref))) {
                dangling.add(ref);
            }
        }
        if (!dangling.isEmpty()) {
            fail("samples/README.md references paths that don't exist: " + dangling);
        }
    }

    @Test
    void coreLanguagesHaveASyntaxSample() {
        Assumptions.assumeTrue(Files.isDirectory(SAMPLES), "samples/ not present (skipping)");
        for (String f : MUST_HAVE) {
            assertTrue(Files.isRegularFile(Path.of(f)), "missing required syntax sample: " + f);
        }
    }

    /** All committed sample files (forward-slash {@code samples/...} paths), excluding the README and the
     *  git-ignored, generated {@code samples/perf/} tree. */
    private static Set<String> committedSamples() throws IOException {
        Set<String> out = new TreeSet<>();
        Path perf = SAMPLES.resolve("perf");
        try (Stream<Path> walk = Files.walk(SAMPLES)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(README))
                    .filter(p -> !p.startsWith(perf))
                    .filter(p -> !p.getFileName().toString().equals(".DS_Store"))
                    .forEach(p -> out.add(
                            "samples/" + SAMPLES.relativize(p).toString().replace('\\', '/')));
        }
        return out;
    }
}
