package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.paint.Color;

import com.editora.editor.EditorBuffer;
import com.editora.text.ColorSwatch;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML links, the markup check and colour swatches in a real window, with no language server (HTML review B6, D5,
 * C16, D9): Go to Definition and Ctrl-click follow an {@code href}, the page's own marks show its unclosed
 * {@code <div>}, Related File reaches the linked stylesheet, and a swatch is drawn in its colour.
 */
@Tag("fx")
class HtmlLinksFxTest {

    private static final String INDEX = """
            <!doctype html>
            <html><head>
              <link rel="stylesheet" href="css/site.css">
            </head><body>
              <a href="pages/about.html#team">Team</a>
              <a href="missing.html">Missing</a>
              <a href="https://example.com/">External</a>
              <a href="#bottom">Down</a>
              <main><div>unclosed
              </main>
              <p id="bottom">end</p>
            </body></html>
            """;

    private static final String ABOUT = """
            <!DOCTYPE html>
            <html>
            <head><title>About</title></head>
            <body>
            <h2 id="team">Team</h2>
            </body>
            </html>
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Path site(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("pages"));
        Files.createDirectories(dir.resolve("css"));
        Files.writeString(dir.resolve("pages/about.html"), ABOUT);
        Files.writeString(dir.resolve("css/site.css"), "body { color: red }\n");
        return Files.writeString(dir.resolve("index.html"), INDEX);
    }

    private static void caretIn(WindowRig w, String needle) throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea a = w.active().getFocusedArea();
            a.moveTo(INDEX.indexOf(needle) + 2);
        });
    }

    private static HtmlLinkFollow links(WindowRig w) {
        LspCoordinator lsp = w.field("lspCoordinator");
        return lsp.html.links;
    }

    @Test
    void goToDefinitionOpensTheLinkedPageAtItsFragmentAndSaysWhenTheFileIsMissing(@TempDir Path dir) throws Exception {
        Path index = site(dir);
        Path about = dir.resolve("pages/about.html");
        try (WindowRig w = new WindowRig()) {
            w.open(index);
            caretIn(w, "missing.html");
            w.run("lsp.gotoDefinition");
            w.await("the missing file is named", () -> statusIs(w, tr("status.html.linkMissing", "missing.html")));
            FxTestSupport.runOnFx(() -> assertEquals(index, w.active().getPath(), "nothing opened"));

            caretIn(w, "about.html#team");
            w.run("lsp.gotoDefinition");
            w.await("about.html at the team heading", () -> w.isActive(about) && caretLineIs(w, 4));
        }
    }

    @Test
    void aFragmentMovesInThePageAndAWebAddressAsksFirst(@TempDir Path dir) throws Exception {
        Path index = site(dir);
        try (WindowRig w = new WindowRig()) {
            w.open(index);
            List<String> asked = new ArrayList<>();
            links(w).webConfirmer = url -> asked.add(url) && false;
            caretIn(w, "example.com");
            w.run("lsp.gotoDefinition");
            assertEquals(List.of("https://example.com/"), asked);

            caretIn(w, "#bottom");
            w.run("lsp.gotoDefinition");
            int bottom = (int)
                    INDEX.lines().takeWhile(l -> !l.contains("id=\"bottom\"")).count();
            w.await("the element with that id", () -> w.isActive(index) && caretLineIs(w, bottom));
        }
    }

    @Test
    void ctrlClickOnAnHrefFollowsItWithoutAServer(@TempDir Path dir) throws Exception {
        Path index = site(dir);
        Path about = dir.resolve("pages/about.html");
        try (WindowRig w = new WindowRig()) {
            w.open(index);
            w.async.awaitFx();
            FxTestSupport.runOnFx(() -> {
                CodeArea a = w.active().getFocusedArea();
                int off = INDEX.indexOf("pages/about") + 3;
                Bounds screen = a.getCharacterBoundsOnScreen(off, off + 1).orElseThrow();
                Bounds scene = a.localToScene(a.screenToLocal(screen)); // the event's x/y are the scene's
                boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
                a.fireEvent(new MouseEvent(
                        MouseEvent.MOUSE_PRESSED,
                        scene.getCenterX(),
                        scene.getCenterY(),
                        screen.getCenterX(),
                        screen.getCenterY(),
                        MouseButton.PRIMARY,
                        1,
                        false,
                        !mac,
                        false,
                        mac,
                        true,
                        false,
                        false,
                        false,
                        false,
                        true,
                        null));
            });
            w.await("about.html opened by the click", () -> w.isActive(about) && caretLineIs(w, 4));
        }
    }

    @Test
    void aPageNoServerServesShowsItsOwnMarkupMarksUntilTheCheckIsSwitchedOff(@TempDir Path dir) throws Exception {
        Path index = site(dir);
        try (WindowRig w = new WindowRig()) {
            EditorBuffer b = w.open(index);
            w.await("the page's marks", () -> marks(b).size() == 2);
            FxTestSupport.runOnFx(() -> {
                List<String> messages = b.httpVariableDiagnostics().stream()
                        .map(d -> d.message())
                        .toList();
                assertEquals(
                        List.of(tr("html.check.missingFile", "missing.html"), tr("html.check.unclosed", "div")),
                        messages);
            });
            FxTestSupport.runOnFx(() -> {
                w.controller.config.getSettings().setHtmlMarkupChecks(false);
                LspCoordinator lsp = w.field("lspCoordinator");
                lsp.html.settingsChanged();
            });
            w.await("the marks are taken back", () -> marks(b).isEmpty());
        }
    }

    @Test
    void relatedFileFromAPageReachesItsLinkedStylesheet(@TempDir Path dir) throws Exception {
        Path index = site(dir);
        Path css = dir.resolve("css/site.css");
        try (WindowRig w = new WindowRig()) {
            w.open(index);
            w.run("nav.relatedFile");
            w.await("the stylesheet the page links", () -> w.isActive(css));
        }
        assertEquals(List.of(index), HtmlRelated.linking(css, List.of(index, dir.resolve("pages/about.html")), null));
    }

    @Test
    void aColourSwatchIsDrawnInItsColourBeforeTheLiteral(@TempDir Path dir) throws Exception {
        Path css = Files.writeString(dir.resolve("a.css"), "a { color: red; }\nb { color: #00ff0080; }\n");
        try (WindowRig w = new WindowRig()) {
            EditorBuffer b = w.open(css);
            FxTestSupport.runOnFx(() -> b.setColorSwatches(
                    List.of(new ColorSwatch(0, 11, 1, 0, 0, 1), new ColorSwatch(1, 11, 0, 1, 0, 0.5))));
            w.await("both swatches laid out", () -> swatches(b).size() == 2);
            FxTestSupport.runOnFx(() -> {
                Label red = swatches(b).stream()
                        .filter(l -> l.getStyleClass().contains("swatch-ff0000ff"))
                        .findFirst()
                        .orElseThrow();
                Background bg = red.getBackground();
                assertTrue(bg != null && bg.getFills().size() == 2, "a frame and the colour");
                assertEquals(Color.RED, bg.getFills().get(1).getFill());
                assertTrue(red.getWidth() > 0, "the swatch takes room in the line");
                assertEquals("a { color: red; }\nb { color: #00ff0080; }\n", b.text(), "never text");
            });
            FxTestSupport.runOnFx(() -> b.setColorSwatches(null));
            w.await("cleared", () -> swatches(b).isEmpty());
        }
    }

    private static List<com.editora.markdown.MarkdownLint.Diagnostic> marks(EditorBuffer b) {
        return b.httpVariableDiagnostics();
    }

    private static List<Label> swatches(EditorBuffer b) {
        List<Label> out = new ArrayList<>();
        for (Node n : b.getArea().lookupAll(".inlay")) {
            if (n instanceof Label l && l.getStyleClass().stream().anyMatch(c -> c.startsWith("swatch-"))) {
                out.add(l);
            }
        }
        return out;
    }

    private static boolean statusIs(WindowRig w, String expected) {
        try {
            return expected.equals(SaveDecisionsFxTest.lastMessage(w.fx));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean caretLineIs(WindowRig w, int line) {
        EditorBuffer b = w.active();
        return b != null && b.getFocusedArea().getCurrentParagraph() == line;
    }
}
