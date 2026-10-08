package com.editora.typst;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TypstRenderer} run against a stand-in {@code typst} — a shell script that writes page files where
 * the real tool would, or fails the way it does. Never the real tool: what is tested is what the renderer
 * passes it, reads back, cleans up and reports.
 */
@DisabledOnOs(OS.WINDOWS)
class TypstStandInToolTest {

    @TempDir
    Path dir;

    /** A stand-in whose body sees {@code $input} (the .typ) and {@code $output} (the last argument). */
    private List<String> tool(String body) throws IOException {
        Path script = dir.resolve("fake-typst-" + System.nanoTime() + ".sh");
        Files.writeString(
                script,
                "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + dir.resolve("args.txt") + "'\n"
                        + "for a; do input=\"$output\"; output=\"$a\"; done\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return List.of(script.toString());
    }

    /** Writes pages 1, 2 and 10 (out of order on a name sort) for a templated output, one file otherwise. */
    private static final String WRITES_PAGES = "case \"$output\" in\n"
            + "  *'{p}'*) for n in 10 2 1; do printf 'page %s' \"$n\" > \"$(printf '%s' \"$output\" | sed \"s/{p}/$n/\")\"; done ;;\n"
            + "  *) cp \"$input\" \"$output\" ;;\nesac";

    private List<String> args() throws IOException {
        return Files.readAllLines(dir.resolve("args.txt"));
    }

    private static List<String> text(List<byte[]> pages) {
        return pages.stream().map(p -> new String(p, StandardCharsets.UTF_8)).toList();
    }

    private List<String> leftovers(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.startsWith(".editora-"))
                    .toList();
        }
    }

    @Test
    void pagesComeBackInPageOrderAndTheThrowawayInputIsRemoved() throws Exception {
        Path project = Files.createDirectories(dir.resolve("proj").resolve("chapters"));
        TypstRenderer.Pages pages =
                TypstRenderer.renderPages(tool(WRITES_PAGES), "= Title", project, dir.resolve("proj"));

        assertTrue(pages.ok(), pages.error());
        assertEquals(List.of("page 1", "page 2", "page 10"), text(pages.pages()), "numeric, not alphabetical");
        List<String> args = args();
        assertEquals("compile", args.get(0));
        assertEquals(
                List.of("--root", dir.resolve("proj").toString()),
                args.subList(1, 3),
                "the project root is the sandbox");
        assertEquals(List.of("-f", "png", "--ppi", Integer.toString(TypstRenderer.renderPpi())), args.subList(3, 7));
        Path input = Path.of(args.get(7));
        assertEquals(project, input.getParent(), "the input sits beside the document so relative imports resolve");
        assertFalse(Files.exists(input), "and is gone afterwards");
        assertEquals(List.of(), leftovers(project));
    }

    @Test
    void aRootThatDoesNotContainTheDocumentIsNotUsedAsTheSandbox() throws Exception {
        Path doc = Files.createDirectories(dir.resolve("docs"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        assertTrue(TypstRenderer.renderPages(tool(WRITES_PAGES), "x", doc, elsewhere)
                .ok());
        assertEquals(doc.toString(), args().get(2), "typst needs the input inside its root");

        assertTrue(TypstRenderer.renderPages(tool(WRITES_PAGES), "x", doc, dir.resolve("missing-root"))
                .ok());
        assertEquals(doc.toString(), args().get(2));
    }

    @Test
    void anUntitledDocumentRendersInAThrowawayFolderOfItsOwn() throws Exception {
        TypstRenderer.Pages pages = TypstRenderer.renderPages(tool(WRITES_PAGES), "x", null, null);
        assertTrue(pages.ok(), pages.error());
        Path root = Path.of(args().get(2));
        assertFalse(Files.exists(root), "the temporary root is removed");
        assertEquals(root, Path.of(args().get(7)).getParent());

        // A folder that is not there (the file was moved) is treated the same way.
        assertTrue(TypstRenderer.renderPages(tool(WRITES_PAGES), "x", dir.resolve("gone"), null)
                .ok());
    }

    @Test
    void aCompileErrorNamesTheUsersFileNotTheThrowawayOne() throws Exception {
        List<String> failing = tool("echo \"error: unknown variable: x\" >&2\necho \"  --> $input:3:1\" >&2\nexit 1");
        Path doc = Files.createDirectories(dir.resolve("docs"));

        TypstRenderer.Pages named = TypstRenderer.renderPages(failing, "#x", doc, null, "thesis.typ");
        assertFalse(named.ok());
        assertTrue(named.error().contains("unknown variable: x"), named.error());
        assertTrue(named.error().contains("thesis.typ:3:1"), named.error());
        assertFalse(named.error().contains(".editora-typst-"), named.error());
        assertEquals(List.of(), named.pages());

        TypstRenderer.Pages unnamed = TypstRenderer.renderPages(failing, "#x", doc, null);
        assertTrue(unnamed.error().contains("document.typ:3:1"), unnamed.error());
        assertEquals(List.of(), leftovers(doc), "a failed render leaves nothing behind either");
    }

    @Test
    void aToolThatSucceedsButWritesNothingOrCannotBeStartedIsAFailureWithAReason() throws Exception {
        TypstRenderer.Pages silent = TypstRenderer.renderPages(tool("exit 0"), "x", null, null);
        assertFalse(silent.ok());
        assertEquals("no pages rendered", silent.error());

        TypstRenderer.Pages missing =
                TypstRenderer.renderPages(List.of(dir.resolve("no-such-typst").toString()), "x", null, null);
        assertFalse(missing.ok());
        assertFalse(missing.error().isBlank());

        TypstRenderer.Pages quiet = TypstRenderer.renderPages(tool("exit 3"), "x", null, null);
        assertFalse(quiet.ok());
        assertFalse(quiet.error().isBlank(), "a silent failure still says something");
    }

    @Test
    void aPdfExportIsStagedBesideItsDestinationAndMovedInOnCommit() throws Exception {
        Path out = Files.createDirectories(dir.resolve("out"));
        Path dest = out.resolve("report.pdf");
        Files.writeString(dest, "the previous export");

        try (TypstRenderer.PendingExport pending =
                TypstRenderer.stageExport(tool(WRITES_PAGES), "= Report", dest, null, null)) {
            assertTrue(pending.ok());
            assertEquals(List.of(dest), pending.targets());
            assertEquals(List.of(), pending.existingTargets(), "the Save dialog already asked about the name itself");
            assertEquals("the previous export", Files.readString(dest), "nothing is replaced before commit");
            assertEquals(List.of("-f", "pdf"), args().subList(3, 5));
            assertFalse(args().contains("--ppi"), "a PDF has no pixel density");

            assertEquals(List.of(dest), pending.commit());
            assertEquals("= Report", Files.readString(dest));
        }
        assertEquals(List.of(), leftovers(out), "the staging folder is gone");
    }

    @Test
    void aMultiPagePngExportIsOneNumberedFilePerPage() throws Exception {
        Path out = Files.createDirectories(dir.resolve("out"));
        Path dest = out.resolve("report.png");
        Path taken = Files.writeString(out.resolve("report-2.png"), "someone's file");

        try (TypstRenderer.PendingExport pending =
                TypstRenderer.stageExport(tool(WRITES_PAGES), "x", dest, null, null)) {
            assertEquals(
                    List.of("report-1.png", "report-2.png", "report-10.png"),
                    pending.targets().stream()
                            .map(p -> p.getFileName().toString())
                            .toList());
            assertEquals(List.of(taken), pending.existingTargets(), "a page file nobody was asked about");
            assertTrue(args().contains("--ppi"));
        }
        assertEquals("someone's file", Files.readString(taken), "a discarded export replaces nothing");
        assertEquals(List.of(), leftovers(out));
    }

    @Test
    void aFailedExportHasNoTargetsAndKeepsItsDiagnostics() throws Exception {
        Path dest = Files.createDirectories(dir.resolve("out")).resolve("report.pdf");
        try (TypstRenderer.PendingExport pending =
                TypstRenderer.stageExport(tool("echo 'error: boom' >&2\nexit 1"), "x", dest, null, null)) {
            assertFalse(pending.ok());
            assertFalse(pending.result().ok());
            assertTrue(
                    pending.result().message().contains("boom"),
                    pending.result().message());
            assertEquals(List.of(), pending.targets());
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, pending::commit);
        }
        assertFalse(Files.exists(dest));

        try (TypstRenderer.PendingExport silent = TypstRenderer.stageExport(tool("exit 0"), "x", dest, null, null)) {
            assertTrue(silent.result().ok());
            assertFalse(silent.ok(), "exit 0 with no file is not an export");
        }
    }

    @Test
    void aPagesResultIsOnlyOkWithPagesAndNoError() {
        assertTrue(TypstRenderer.Pages.ok(List.of(new byte[] {1})).ok());
        assertFalse(TypstRenderer.Pages.ok(List.of()).ok());
        assertFalse(new TypstRenderer.Pages(null, null).ok());
        assertEquals("render failed", TypstRenderer.Pages.fail(" ").error());
        assertEquals("render failed", TypstRenderer.Pages.fail(null).error());
        assertEquals("bad", TypstRenderer.Pages.fail(" bad \n").error());
    }
}
