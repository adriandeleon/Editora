package com.editora;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editor.GrammarRegistry;
import com.editora.editor.LanguageRegistry;
import com.editora.search.GitignoreFilter;
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
 *   <li>the core (LSP-served) languages each have a syntax sample;</li>
 *   <li>every bundled grammar has a sample somewhere in the corpus, so shipping a new language without
 *       one fails here rather than going unnoticed;</li>
 *   <li>every sample still resolves to a language, so a rename or a move out of a name-matched folder
 *       (e.g. {@code etc/hosts}) cannot silently turn it into plain text.</li>
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

    /**
     * Bundled grammars that deliberately have no sample. {@code .gitattributes} is the only one: a real file
     * of that name would change Git's behavior for the folder it sits in (see the README's Conventions).
     */
    private static final Set<String> NO_SAMPLE_ON_PURPOSE = Set.of("gitattributes");

    /**
     * Extensions that are meant to open as plain text or in a viewer: prose fixtures, binaries, and the two
     * tool files Editora has no grammar for ({@code go.mod}, BibTeX).
     */
    private static final Set<String> PLAIN_EXTENSIONS =
            Set.of("txt", "bin", "png", "jpg", "gif", "bmp", "pdf", "mod", "bib");

    /** Samples whose language comes from their <em>content</em> (a shebang, a log sniff), not their name. */
    private static final Set<String> DETECTED_BY_CONTENT =
            Set.of("samples/run/shebang-script", "samples/log/server.out");

    @Test
    void everyCommittedSampleIsListedInTheReadme() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SAMPLES), "samples/ not present (skipping)");
        // Whole paths, not substrings: "samples/syntax/sample.ts" must not be satisfied by a mention of
        // "samples/syntax/sample.tsx", nor "sample.js" by "sample.json".
        Set<String> listed = readmeReferences();
        Set<String> missing = new TreeSet<>();
        for (String rel : committedSamples()) {
            if (!listed.contains(rel)) {
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
        for (String ref : readmeReferences()) {
            if (ref.endsWith("/") || ref.equals("samples/perf") || ref.startsWith("samples/perf/")) {
                continue; // a directory mention, or a file under the generated, git-ignored perf/ tree
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

    @Test
    void everyBundledGrammarHasASample() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SAMPLES), "samples/ not present (skipping)");
        Set<String> covered = new TreeSet<>();
        for (String rel : committedSamples()) {
            covered.add(languageOf(rel));
        }
        covered.add(LanguageRegistry.forFileName("README.md")); // the manifest itself is a Markdown sample
        Set<String> uncovered = new TreeSet<>(GrammarRegistry.shared().availableLanguageNames());
        uncovered.removeAll(covered);
        uncovered.removeAll(NO_SAMPLE_ON_PURPOSE);
        if (!uncovered.isEmpty()) {
            fail("These bundled grammars have no sample under samples/ (add one, and list it in the README): "
                    + uncovered);
        }
    }

    @Test
    void everySampleResolvesToALanguage() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(SAMPLES), "samples/ not present (skipping)");
        Set<String> plain = new TreeSet<>();
        for (String rel : committedSamples()) {
            String name = rel.substring(rel.lastIndexOf('/') + 1);
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (PLAIN_EXTENSIONS.contains(ext) || DETECTED_BY_CONTENT.contains(rel)) {
                continue;
            }
            if (LanguageRegistry.PLAINTEXT.equals(languageOf(rel))) {
                plain.add(rel);
            }
        }
        if (!plain.isEmpty()) {
            fail("These samples open as plain text. A name- or folder-matched sample may have been renamed or"
                    + " moved; otherwise add its extension to PLAIN_EXTENSIONS: " + plain);
        }
    }

    /** The language a sample opens as. Resolved from the absolute path, as the editor does, because several
     *  config grammars are matched by an enclosing folder ({@code etc/}, {@code debian/}). */
    private static String languageOf(String rel) {
        return LanguageRegistry.forFileName(
                Path.of(rel).toAbsolutePath().toString().replace('\\', '/'));
    }

    /** Every {@code samples/...} path the README mentions, with sentence punctuation trimmed off the end. */
    private static Set<String> readmeReferences() throws IOException {
        Set<String> out = new TreeSet<>();
        Matcher m = REF.matcher(Files.readString(README));
        while (m.find()) {
            String ref = m.group();
            while (ref.endsWith(".") || ref.endsWith("-")) {
                ref = ref.substring(0, ref.length() - 1);
            }
            out.add(ref);
        }
        return out;
    }

    /** All committed sample files (forward-slash {@code samples/...} paths), excluding the README and the
     *  git-ignored, generated {@code samples/perf/} tree. */
    private static Set<String> committedSamples() throws IOException {
        Set<String> out = new TreeSet<>();
        // Honor the repo .gitignore, mirroring the production walker (prune ignored directories, skip ignored
        // files). Without this, merely RUNNING a build-tool sample — which is what samples/build-tools/ is FOR
        // — litters cargo/target, maven/target, gradle/build, … and every generated file then looks like an
        // undocumented committed sample, failing this test. The `!/samples/log/*.log` negation keeps the log
        // corpus counted.
        GitignoreFilter ignore = GitignoreFilter.load(Path.of(""));
        Files.walkFileTree(SAMPLES, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(SAMPLES)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = rel(dir);
                // samples/perf/ is generated (git-ignored) too, but keep the explicit skip as documentation.
                return rel.equals("samples/perf") || ignore.ignored(rel, true)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = rel(file);
                if (!file.equals(README)
                        && !file.getFileName().toString().equals(".DS_Store")
                        && !ignore.ignored(rel, false)) {
                    out.add(rel);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /** The repo-root-relative, forward-slash path of a sample entry (e.g. {@code samples/syntax/sample.py}). */
    private static String rel(Path p) {
        return "samples/" + SAMPLES.relativize(p).toString().replace('\\', '/');
    }
}
