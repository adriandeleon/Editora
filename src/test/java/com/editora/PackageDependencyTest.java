package com.editora;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins which {@code com.editora.*} packages the {@code editor} and {@code completion} packages may reference.
 *
 * <p>The invariant that holds — and must keep holding — is that neither references {@code ui}: the editor is
 * wired by the window, never the other way round. Beyond that the lists below are a <b>ratchet, not a
 * design</b>: they record the dependencies that exist today (the pure parsers and detectors the editor calls
 * directly for gutters, previews and typing assists) so that a <em>new</em> one cannot slip in unnoticed. A
 * feature with behaviour, state or I/O still reaches the editor through an injected hook (see
 * {@code docs/architecture.md}); if a new compile-time dependency is genuinely the right call, add it here in
 * the same change and say why in the review. When one is removed, delete its entry so it stays removed
 * (an unused entry is not an error — parallel changes must be able to drop a dependency without touching
 * this file — but it is no longer protecting anything).
 *
 * <p>Sources are scanned as text, like {@link SourceFileSizeTest}: imports and fully qualified names both
 * count, comments do not.
 */
class PackageDependencyTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/editora");

    /** {@code com.editora.<package>.} — the first segment is the top-level package. */
    private static final Pattern REFERENCE = Pattern.compile("\\bcom\\.editora\\.([a-z][a-z0-9]*)\\.");

    /** Block and Javadoc comments, then line comments (a {@code //} inside a string literal is rare enough). */
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static final Set<String> EDITOR_MAY_REFERENCE = Set.of(
            // Shared foundations.
            "completion",
            "config",
            "editops",
            "editorconfig",
            "i18n",
            "markdown",
            "snippet",
            "structured",
            // Pure per-format parsers and detectors behind the gutter Run/Test markers and the previews.
            "cron",
            "csv",
            "diagram",
            "dockerfile",
            "fstab",
            "ghactions",
            "http",
            "logviewer",
            "macro",
            "markwhen",
            "maven",
            "mermaid",
            "run",
            "sshconfig",
            "systemd",
            "test",
            "typst");

    private static final Set<String> COMPLETION_MAY_REFERENCE = Set.of("snippet");

    /** The top-level {@code com.editora} packages referenced from code under {@code pkg}, itself excluded. */
    static Set<String> referencedPackages(String pkg) throws IOException {
        Path dir = SOURCE_ROOT.resolve(pkg);
        assertTrue(Files.isDirectory(dir), "Run from the Maven project root");
        Set<String> found = new TreeSet<>();
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = REFERENCE.matcher(stripComments(Files.readString(file)));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        found.remove(pkg);
        return found;
    }

    static String stripComments(String source) {
        return COMMENTS.matcher(source).replaceAll(" ");
    }

    private static Set<String> unexpected(String pkg, Set<String> allowed) throws IOException {
        Set<String> extra = referencedPackages(pkg);
        extra.removeAll(allowed);
        return extra;
    }

    @Test
    void theEditorPackageNeverReferencesUi() throws Exception {
        assertFalse(
                referencedPackages("editor").contains("ui"),
                "editor must not depend on ui — inject a Supplier/Consumer/small interface instead");
    }

    @Test
    void theCompletionPackageNeverReferencesUi() throws Exception {
        assertFalse(
                referencedPackages("completion").contains("ui"),
                "completion must not depend on ui — inject a Supplier/Consumer/small interface instead");
    }

    @Test
    void theEditorPackageGainsNoNewPackageDependency() throws Exception {
        Set<String> extra = unexpected("editor", EDITOR_MAY_REFERENCE);
        assertTrue(
                extra.isEmpty(),
                () -> "editor now references " + extra + ". Features reach the editor through injected hooks"
                        + " (docs/architecture.md); if this dependency is intended, add it to"
                        + " EDITOR_MAY_REFERENCE in the same change.");
    }

    @Test
    void theCompletionPackageGainsNoNewPackageDependency() throws Exception {
        Set<String> extra = unexpected("completion", COMPLETION_MAY_REFERENCE);
        assertTrue(
                extra.isEmpty(),
                () -> "completion now references " + extra + "; if intended, add it to COMPLETION_MAY_REFERENCE.");
    }

    @Test
    void commentsDoNotCountAsReferences() {
        String source = "/** See {@code com.editora.ui.Icons}. */\n"
                + "class X { // com.editora.git.GitService\n"
                + "  com.editora.csv.CsvParser p; /* com.editora.lsp.X */\n"
                + "}\n";
        Matcher m = REFERENCE.matcher(stripComments(source));
        Set<String> found = new TreeSet<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        assertTrue(found.equals(Set.of("csv")), found.toString());
    }
}
