package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.HtmlMarkupLint;
import com.editora.lsp.LspTestHooks;
import com.editora.text.LspDiagnostic;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML links while the HTML server serves the page (HTML review 2, B103, B106): Go to Definition resolves the link
 * itself first — the server keeps {@code ?v=3} in the file name, ignores {@code <base>} and reads
 * {@code C%23notes.html} as {@code C} with a fragment — and asks the server only when that names no file; and the
 * "File not found" marks the markup check adds to the server's diagnostics follow files created and deleted on disk.
 */
@Tag("fx")
class HtmlServerLinksFxTest {

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        ServerCapabilities caps = LspTestHooks.caps();
        caps.setDocumentLinkProvider(new org.eclipse.lsp4j.DocumentLinkOptions(false));
        caps.setDiagnosticProvider(new org.eclipse.lsp4j.DiagnosticRegistrationOptions(false, false));
        fx = new LspCoordinatorFixture(root, caps);
        FxTestSupport.runOnFx(() -> fx.coordinator.setServerAvailableForTest("html", true));
        Files.createDirectories(root.resolve("css"));
        Files.createDirectories(root.resolve("pages"));
        Files.createDirectories(root.resolve("assets"));
        Files.writeString(root.resolve("css/site.css"), "body {}\n");
        Files.writeString(root.resolve("pages/about.html"), "<html>\n<body>\n<h2 id=\"team\">Team</h2>\n</body>\n");
        Files.writeString(root.resolve("C#notes.html"), "<p>c sharp</p>\n");
        Files.writeString(root.resolve("assets/x.css"), "p {}\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    /** The server's link at {@code line}:{@code from}–{@code to}, pointing at {@code target} (as it writes it). */
    private static DocumentLink link(int line, int from, int to, String target) {
        return new DocumentLink(new Range(new Position(line, from), new Position(line, to)), target);
    }

    private static String uri(Path p) {
        return p.toUri().toString();
    }

    private void follow(EditorBuffer b, int line, int column) throws Exception {
        fx.caret(b, line, column);
        fx.run(() -> assertTrue(fx.coordinator.html.links.followAtCaret()));
    }

    private void await(String what, BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end) {
            fx.settle();
            if (FxTestSupport.callOnFx(done::getAsBoolean)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what + "; statuses " + fx.host.statuses);
    }

    private boolean jumpedTo(Path file, int line) {
        return fx.ops.jumps.stream().anyMatch(j -> j.file().equals(file) && j.line() == line);
    }

    @Test
    void theLinksOwnResolutionWinsOverTheServersTarget() throws Exception {
        String page = """
                <link rel="stylesheet" href="css/site.css?v=3">
                <a href="C%23notes.html">c</a>
                """;
        EditorBuffer b = fx.open("links.html", page);
        FakeLanguageServer server = fx.server();
        // What vscode-html-language-server answers: the query encoded into the name, the file's # read as a fragment.
        server.documentLinkResponse = List.of(
                link(0, 29, 45, uri(root) + "css/site.css%3Fv=3"), link(1, 9, 23, uri(root) + "C%23notes.html"));

        follow(b, 0, 35);
        await("css/site.css", () -> jumpedTo(root.resolve("css/site.css"), 0));
        follow(b, 1, 12);
        await("C#notes.html", () -> jumpedTo(root.resolve("C#notes.html"), 0));
        assertTrue(
                fx.host.statuses.stream().noneMatch(s -> s.startsWith(tr("status.html.linkMissing", ""))),
                "nothing reported missing: " + fx.host.statuses);
    }

    @Test
    void aPageWithABaseFollowsItWhereTheServerIgnoresIt() throws Exception {
        String page = "<head><base href=\"pages/\"></head>\n<a href=\"about.html#team\">t</a>\n";
        EditorBuffer b = fx.open("based.html", page);
        fx.server().documentLinkResponse = List.of(link(1, 9, 24, uri(root) + "about.html%23team"));

        follow(b, 1, 12);

        await("pages/about.html at the team heading", () -> jumpedTo(root.resolve("pages/about.html"), 2));
    }

    /**
     * Only when the page's own resolution names no file is the server's target taken — a loose page's root-relative
     * link, which the server resolves from its site root — and then without the query it encoded into the name.
     */
    @Test
    void theServerIsAskedWhenTheLinkNamesNoFileHere() throws Exception {
        Files.createDirectories(root.resolve("sub"));
        String page = "<link rel=\"stylesheet\" href=\"/assets/x.css?v=1\">\n";
        EditorBuffer b = fx.open("sub/page.html", page);
        fx.server().documentLinkResponse = List.of(link(0, 29, 46, uri(root) + "assets/x.css%3Fv=1"));

        follow(b, 0, 35);

        await("the server's file, without its query", () -> jumpedTo(root.resolve("assets/x.css"), 0));
    }

    /** B107: a link to a folder that has no index page says so, rather than that the folder is missing. */
    @Test
    void aFolderWithoutAnIndexPageSaysSo() throws Exception {
        EditorBuffer b = fx.open("dir.html", "<a href=\"pages/\">p</a>\n");
        fx.server().documentLinkResponse = List.of(link(0, 9, 15, uri(root) + "pages/"));

        follow(b, 0, 11);

        await(
                "the no-index message",
                () -> tr("status.html.linkNoIndex", "pages/").equals(fx.host.lastStatus()));
    }

    private List<String> missingMarks(Path file) {
        List<LspDiagnostic> shown = fx.published.getOrDefault(file, List.of());
        return shown.stream()
                .filter(d -> HtmlMarkupLint.SOURCE.equals(d.source()))
                .map(LspDiagnostic::message)
                .toList();
    }

    /**
     * B106: the "File not found" marks are checked again when files are created or deleted — the page was not
     * edited — and a file event that only changes content checks nothing.
     */
    @Test
    void theMissingFileMarksFollowFilesCreatedAndDeleted() throws Exception {
        EditorBuffer b = fx.open("index.html", "<a href=\"new.html\">n</a>\n<img src=\"css/site.css\">\n");
        Path page = b.getPath();
        FakeLanguageServer server = fx.server();
        server.diagnosticResponse = new org.eclipse.lsp4j.DocumentDiagnosticReport(
                new org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport(List.of()));
        fx.run(() -> fx.manager.pullDiagnostics(page));
        String missingNew = tr("html.check.missingFile", "new.html");
        await("new.html flagged", () -> missingMarks(page).equals(List.of(missingNew)));

        int pulls = server.diagnosticPulls.size();
        fx.run(() -> fx.coordinator.watchedFilesChanged(
                List.of(new ProjectPanel.FsChange(root.resolve("css/site.css"), ProjectPanel.FsKind.CHANGED))));
        Thread.sleep(3 * (long) HtmlLspAids.LINK_RECHECK_DELAY.toMillis());
        fx.settle();
        assertEquals(pulls, server.diagnosticPulls.size(), "a content change does not re-check the links");

        Path created = Files.writeString(root.resolve("new.html"), "<p>new</p>\n");
        Files.delete(root.resolve("css/site.css"));
        fx.run(() -> fx.coordinator.watchedFilesChanged(List.of(
                new ProjectPanel.FsChange(created, ProjectPanel.FsKind.CREATED),
                new ProjectPanel.FsChange(root.resolve("css/site.css"), ProjectPanel.FsKind.DELETED))));

        String missingCss = tr("html.check.missingFile", "css/site.css");
        await("the marks follow the disk", () -> missingMarks(page).equals(List.of(missingCss)));
    }
}
