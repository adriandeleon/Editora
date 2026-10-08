package com.editora.editor;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.diagram.DiagramKind;
import com.editora.editor.EditorBuffer.MarkdownViewMode;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The previews that shell out to a renderer (Mermaid's mmdc and maid, Graphviz, PlantUML, Typst), run against
 * stand-in shell scripts in a temp directory: what a finished render looks like, what the user reads when the
 * tool fails or writes something that is not an image, and which render wins when edits arrive faster than
 * the tool. No real renderer is started.
 */
@Tag("fx")
class ExternalRenderFxTest {

    @TempDir
    static Path tools;

    private static Path mmdc;
    private static Path maid;
    private static Path dot;
    private static Path plantuml;
    private static Path typst;
    private static Path mmdcLog;
    private static final AtomicInteger NONCE = new AtomicInteger();

    private static Object[] savedMermaid;
    private static Object[] savedDiagram;
    private static Object[] savedTypst;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"), "the stand-in tools are sh scripts");
        EditorFx.boot();
        Path png = tools.resolve("image.png");
        javax.imageio.ImageIO.write(new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
        mmdcLog = tools.resolve("mmdc.log");
        mmdc = script("mmdc", """
                echo "$@" >> "%s"
                in=""; out=""
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -i) in="$2"; shift ;;
                    -o) out="$2"; shift ;;
                  esac
                  shift
                done
                if grep -q CHROME "$in"; then echo "Error: Could not find Chrome (ver. 131)" >&2; exit 1; fi
                if grep -q BROKEN "$in"; then echo "Parse error on line 2" >&2; exit 1; fi
                if grep -q GARBAGE "$in"; then echo "not a png" > "$out"; exit 0; fi
                cp "%s" "$out"
                """.formatted(mmdcLog, png));
        maid = script("maid", """
                for last; do :; done
                if grep -q NODIAG "$last"; then echo '{"valid":true,"errors":[]}'; exit 0; fi
                echo '[{"line":2,"column":7,"severity":"error","code":"E1","message":"Unexpected token"},{"line":3,"column":1,"severity":"error","message":"Missing end"}]'
                exit 1
                """);
        dot = script("dot", """
                in=""; out=""
                while [ $# -gt 0 ]; do
                  case "$1" in
                    -o) out="$2"; shift ;;
                    -T*) ;;
                    *) in="$1" ;;
                  esac
                  shift
                done
                if grep -q BROKEN "$in"; then echo "syntax error in line 1 near 'digraf'" >&2; exit 1; fi
                if grep -q SILENT "$in"; then exit 1; fi
                if grep -q EMPTY "$in"; then exit 0; fi
                if grep -q GARBAGE "$in"; then echo "not a png" > "$out"; exit 0; fi
                cp "%s" "$out"
                """.formatted(png));
        plantuml = script("plantuml", """
                for last; do :; done
                dir=$(dirname "$last")
                cp "%1$s" "$dir/first.png"
                if grep -q SECOND "$last"; then cp "%1$s" "$dir/second.png"; cp "%1$s" "$dir/third.png"; fi
                """.formatted(png));
        typst = script("typst", """
                prev=""; in=""
                for a; do in="$prev"; prev="$a"; done
                dir=$(dirname "$prev")
                if grep -q BROKEN "$in"; then echo "error: unknown variable: nope" >&2; echo "  at $in:1:2" >&2; exit 1; fi
                if grep -q GARBAGE "$in"; then echo "not a png" > "$dir/page-1.png"; exit 0; fi
                if grep -q NOPAGES "$in"; then exit 0; fi
                pages=2
                if grep -q MANY "$in"; then pages=42; fi
                i=1
                while [ $i -le $pages ]; do cp "%s" "$dir/page-$i.png"; i=$((i+1)); done
                """.formatted(png));

        savedMermaid = new Object[] {
            EditorFx.staticField(MermaidImages.class, "enabled"),
            EditorFx.staticField(MermaidImages.class, "mmdc"),
            EditorFx.staticField(MermaidImages.class, "maid"),
            EditorFx.staticField(MermaidImages.class, "dark"),
            EditorFx.staticField(MermaidImages.class, "maidAvailable")
        };
        savedDiagram = new Object[] {
            EditorFx.staticField(DiagramImages.class, "enabled"),
            EditorFx.staticField(DiagramImages.class, "commands"),
            EditorFx.staticField(DiagramImages.class, "dark")
        };
        savedTypst = new Object[] {
            EditorFx.staticField(TypstImages.class, "enabled"), EditorFx.staticField(TypstImages.class, "command")
        };
    }

    @AfterAll
    @SuppressWarnings("unchecked")
    static void restoreTheSharedRendererConfiguration() {
        if (savedMermaid == null) {
            return;
        }
        MermaidImages.configure(
                (Boolean) savedMermaid[0], (List<String>) savedMermaid[1], (List<String>) savedMermaid[2], (Boolean)
                        savedMermaid[3]);
        MermaidImages.setMaidAvailable((Boolean) savedMermaid[4]);
        Map<DiagramKind, List<String>> commands = (Map<DiagramKind, List<String>>) savedDiagram[1];
        DiagramImages.configure((Boolean) savedDiagram[0], commands, (Boolean) savedDiagram[2]);
        EditorFx.setStaticField(DiagramImages.class, "commands", commands); // configure() ignores an empty map
        TypstImages.configure((Boolean) savedTypst[0], (List<String>) savedTypst[1]);
    }

    private static Path script(String name, String body) throws Exception {
        Path file = tools.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    private static String unique(String body) {
        return body + "\n%% " + NONCE.incrementAndGet() + " " + System.nanoTime();
    }

    private static void useMermaid(boolean maidInstalled, boolean dark) {
        MermaidImages.configure(true, List.of(mmdc.toString()), List.of(maid.toString()), dark);
        MermaidImages.setMaidAvailable(maidInstalled);
    }

    private static void useDiagrams() {
        Map<DiagramKind, List<String>> commands = new EnumMap<>(DiagramKind.class);
        commands.put(DiagramKind.DOT, List.of(dot.toString()));
        commands.put(DiagramKind.PLANTUML, List.of(plantuml.toString()));
        DiagramImages.configure(true, commands, false);
    }

    private static boolean rendering(StackPane host) {
        return host.getChildren().size() == 1
                && host.getChildren().get(0) instanceof Label label
                && (label.getStyleClass().contains("mermaid-placeholder")
                        || label.getStyleClass().contains("diagram-placeholder"));
    }

    /** Waits until {@code host} shows a finished render (image or error) instead of its "rendering" note. */
    private static void awaitRendered(StackPane host) throws Exception {
        EditorFx.awaitUntil(host::getChildren, () -> !rendering(host));
    }

    private static StackPane render(java.util.function.Supplier<Node> node) throws Exception {
        StackPane host = (StackPane) EditorFx.callFx(node::get);
        awaitRendered(host);
        return host;
    }

    // ---- Mermaid -------------------------------------------------------------------------------------

    @Test
    void aMermaidDiagramRendersAtItsLogicalSizeAndIsServedFromTheCacheAfterwards() throws Exception {
        useMermaid(false, false);
        String source = unique("graph TD; A-->B");
        StackPane host = render(() -> MermaidImages.node(source, width -> width * 3));
        EditorFx.onFx(() -> {
            assertTrue(host.getStyleClass().contains("md-mermaid"));
            ImageView view =
                    assertInstanceOf(ImageView.class, host.getChildren().get(0));
            assertEquals(80, view.getImage().getWidth(), 0.01);
            assertEquals(120, view.getFitWidth(), 0.01, "an 80 px render at 2x is 40 logical px, times the sizer");

            // The same text again is a cache hit: the picture is there before node() returns.
            StackPane again = (StackPane) MermaidImages.node(source, width -> width);
            ImageView cached =
                    assertInstanceOf(ImageView.class, again.getChildren().get(0));
            assertEquals(40, cached.getFitWidth(), 0.01);
            assertEquals(view.getImage(), cached.getImage(), "the decoded image is shared, not rendered twice");
        });
    }

    @Test
    void theAppThemeIsPassedToTheRenderer() throws Exception {
        useMermaid(false, true);
        String source = unique("graph TD; Dark-->Theme");
        render(() -> MermaidImages.node(source, width -> width));
        assertTrue(Files.readString(mmdcLog).contains("-t dark"), "the dark app theme renders a dark diagram");
        useMermaid(false, false);
        render(() -> MermaidImages.node(source, width -> width));
        assertTrue(Files.readString(mmdcLog).contains("-t default"), "and the light theme a separate light one");
    }

    @Test
    void aFailedMermaidRenderShowsTheToolsMessage() throws Exception {
        useMermaid(false, false);
        StackPane host = render(() -> MermaidImages.node(unique("graph TD; BROKEN"), width -> width));
        EditorFx.onFx(() -> {
            Label error = assertInstanceOf(Label.class, host.getChildren().get(0));
            assertTrue(error.getStyleClass().contains("mermaid-error"));
            assertEquals(Messages.tr("mermaid.renderFailed") + "\nParse error on line 2", error.getText());
        });
    }

    @Test
    void aMissingHeadlessChromeLeadsWithTheFix() throws Exception {
        useMermaid(false, false);
        StackPane host = render(() -> MermaidImages.node(unique("graph TD; CHROME"), width -> width));
        EditorFx.onFx(() -> {
            String text = ((Label) host.getChildren().get(0)).getText();
            assertTrue(text.startsWith(Messages.tr("mermaid.chromeMissing")), text);
            assertTrue(text.contains("Could not find Chrome"), "the raw message is still there underneath");
        });
    }

    @Test
    void withMaidInstalledAFailedRenderShowsItsLineAndColumnDiagnostics() throws Exception {
        useMermaid(true, false);
        StackPane host = render(() -> MermaidImages.node(unique("graph TD; BROKEN"), width -> width));
        EditorFx.onFx(() -> {
            String text = ((Label) host.getChildren().get(0)).getText();
            assertTrue(text.contains(Messages.tr("mermaid.diagnosticLine", 2, 7, " [E1] Unexpected token")), text);
            assertTrue(text.contains(Messages.tr("mermaid.diagnosticLine", 3, 1, " Missing end")), text);
            assertFalse(text.contains("Parse error on line 2"), "maid's diagnostics replace mmdc's raw error");
        });

        // maid has nothing to say about this one: fall back to what mmdc reported.
        StackPane fallback = render(() -> MermaidImages.node(unique("graph TD; BROKEN NODIAG"), width -> width));
        EditorFx.onFx(() ->
                assertTrue(((Label) fallback.getChildren().get(0)).getText().contains("Parse error on line 2")));
    }

    @Test
    void outputThatIsNotAnImageIsReportedAsAFailedRender() throws Exception {
        useMermaid(false, false);
        StackPane host = render(() -> MermaidImages.node(unique("graph TD; GARBAGE"), width -> width));
        EditorFx.onFx(() -> assertEquals(
                Messages.tr("mermaid.renderFailed") + "\n" + Messages.tr("mermaid.renderFailed"),
                ((Label) host.getChildren().get(0)).getText()));
    }

    @Test
    void onlyTheNewestMermaidRenderOfALiveSurfaceRuns() throws Exception {
        useMermaid(false, false);
        ExecutorService pool = EditorFx.staticField(MermaidImages.class, "EXEC");
        Runnable release = EditorFx.holdPool(pool, 2);
        String surface = "mmd-live-" + System.nanoTime();
        String stale = unique("graph TD; Old-->Edit");
        String newest = unique("graph TD; New-->Edit");
        StackPane[] hosts = new StackPane[2];
        try {
            EditorFx.onFx(() -> {
                hosts[0] = (StackPane) MermaidImages.node(stale, width -> width, surface);
                hosts[1] = (StackPane) MermaidImages.node(newest, width -> width, surface);
            });
        } finally {
            release.run();
        }
        awaitRendered(hosts[1]);
        EditorFx.onFx(() -> {
            assertInstanceOf(ImageView.class, hosts[1].getChildren().get(0));
            assertTrue(rendering(hosts[0]), "the superseded edit never reached the renderer");
        });
        MermaidImages.release(surface);
        MermaidImages.release(null);
        // With the surface released its render is no longer cached: the same text renders afresh.
        StackPane again = (StackPane) EditorFx.callFx(() -> MermaidImages.node(newest, width -> width, surface));
        assertTrue(EditorFx.callFx(() -> rendering(again)), "nothing cached for a released surface");
        awaitRendered(again);
    }

    @Test
    void repointingTheToolForgetsTheFailuresOfTheOldOne() throws Exception {
        Path missing = tools.resolve("no-such-mmdc");
        MermaidImages.configure(true, List.of(missing.toString()), null, false);
        MermaidImages.setMaidAvailable(false);
        String source = unique("graph TD; Install-->Me");
        StackPane failed = render(() -> MermaidImages.node(source, width -> width));
        EditorFx.onFx(() -> assertInstanceOf(Label.class, failed.getChildren().get(0), "no tool, no picture"));

        // The install flow: the tool appears, every preview re-renders the same text.
        useMermaid(false, false);
        StackPane retried = render(() -> MermaidImages.node(source, width -> width));
        EditorFx.onFx(() -> assertInstanceOf(
                ImageView.class, retried.getChildren().get(0), "the cached failure is not served again"));

        MermaidImages.configure(true, List.of(), List.of(), false); // empty commands keep the configured ones
        StackPane kept = (StackPane) EditorFx.callFx(() -> MermaidImages.node(source, width -> width));
        EditorFx.onFx(() -> assertInstanceOf(ImageView.class, kept.getChildren().get(0), "still cached"));
    }

    @Test
    void anOldFailureIsRetriedButAFreshOneIsReused() throws Exception {
        useMermaid(false, false);
        String source = unique("graph TD; Retry-->Later");
        String key =
                EditorFx.call(MermaidImages.class, "key", new Class<?>[] {String.class, boolean.class}, source, false);
        MermaidImages.store(key, new MermaidImages.Cached(null, "mmdc was not installed"), null);
        EditorFx.onFx(() -> {
            StackPane fresh = (StackPane) MermaidImages.node(source, width -> width);
            assertTrue(((Label) fresh.getChildren().get(0)).getText().contains("mmdc was not installed"));
        });

        MermaidImages.store(key, new MermaidImages.Cached(null, "mmdc was not installed", 0L), null);
        StackPane retried = render(() -> MermaidImages.node(source, width -> width));
        EditorFx.onFx(
                () -> assertInstanceOf(ImageView.class, retried.getChildren().get(0)));
    }

    @Test
    void printRendersEveryDiagramOnceWithTheLightThemeAndWaitsForThem() throws Exception {
        useMermaid(false, true); // the app is dark; paper is not
        String good = unique("graph TD; Print-->Me");
        String bad = unique("graph TD; BROKEN print");
        long before = Files.exists(mmdcLog) ? Files.readAllLines(mmdcLog).size() : 0;
        Map<String, MermaidImages.Cached> rendered = MermaidImages.renderAllLight(List.of(good, bad, good));
        assertEquals(2, rendered.size());
        assertNotNull(rendered.get(good).loaded());
        assertEquals("Parse error on line 2", rendered.get(bad).error());
        List<String> lines = Files.readAllLines(mmdcLog);
        assertEquals(before + 2, lines.size(), "the repeated diagram was rendered once");
        assertTrue(lines.subList((int) before, lines.size()).stream().allMatch(l -> l.contains("-t default")));

        assertEquals(
                rendered.get(good), MermaidImages.renderAllLight(List.of(good)).get(good), "a cache hit");
        assertEquals(before + 2, Files.readAllLines(mmdcLog).size());

        EditorFx.onFx(() -> {
            StackPane printed = (StackPane) MermaidImages.printNode(rendered.get(good), width -> width * 2);
            assertEquals(80, ((ImageView) printed.getChildren().get(0)).getFitWidth(), 0.01);
            StackPane failed = (StackPane) MermaidImages.printNode(rendered.get(bad), width -> width);
            assertTrue(((Label) failed.getChildren().get(0)).getText().contains("Parse error on line 2"));
            StackPane missing = (StackPane) MermaidImages.printNode(null, width -> width);
            assertEquals(
                    Messages.tr("mermaid.renderFailed"),
                    ((Label) missing.getChildren().get(0)).getText());
        });
        useMermaid(false, false);
    }

    // ---- Graphviz / PlantUML ---------------------------------------------------------------------------

    @Test
    void aGraphvizDiagramRendersAndIsCached() throws Exception {
        useDiagrams();
        assertTrue(DiagramImages.isEnabled());
        String source = unique("digraph { a -> b }").replace("%%", "//");
        StackPane host = render(() -> DiagramImages.node(DiagramKind.DOT, source, width -> width / 2));
        EditorFx.onFx(() -> {
            assertTrue(host.getStyleClass().contains("md-diagram"));
            ImageView view =
                    assertInstanceOf(ImageView.class, host.getChildren().get(0));
            assertEquals(40, view.getFitWidth(), 0.01);
            StackPane again = (StackPane) DiagramImages.node(DiagramKind.DOT, source, width -> width);
            assertEquals(80, ((ImageView) again.getChildren().get(0)).getFitWidth(), 0.01, "a cache hit");
        });
    }

    @Test
    void aPlantUmlFileWithSeveralDiagramsShowsTheFirstAndCountsTheRest() throws Exception {
        useDiagrams();
        String one = unique("@startuml named\nA -> B\n@enduml").replace("%%", "'");
        StackPane single = render(() -> DiagramImages.node(DiagramKind.PLANTUML, one, width -> width));
        EditorFx.onFx(() -> assertInstanceOf(
                ImageView.class,
                single.getChildren().get(0),
                "the output is found although it is named after the diagram, not the input"));

        String several = unique("@startuml\nA -> B\n@enduml\n' SECOND").replace("%%", "'");
        StackPane host = render(() -> DiagramImages.node(DiagramKind.PLANTUML, several, width -> width));
        EditorFx.onFx(() -> {
            VBox box = assertInstanceOf(VBox.class, host.getChildren().get(0));
            assertInstanceOf(ImageView.class, box.getChildren().get(0));
            Label note = assertInstanceOf(Label.class, box.getChildren().get(1));
            assertEquals(Messages.tr("diagram.moreDiagrams", "2"), note.getText());
        });
    }

    @Test
    void aFailedDiagramRenderSaysWhy() throws Exception {
        useDiagrams();
        String failed = Messages.tr("diagram.renderFailed");
        StackPane broken = render(() -> DiagramImages.node(DiagramKind.DOT, unique("digraf BROKEN"), w -> w));
        StackPane silent = render(() -> DiagramImages.node(DiagramKind.DOT, unique("digraph SILENT"), w -> w));
        StackPane empty = render(() -> DiagramImages.node(DiagramKind.DOT, unique("digraph EMPTY"), w -> w));
        StackPane garbage = render(() -> DiagramImages.node(DiagramKind.DOT, unique("digraph GARBAGE"), w -> w));
        EditorFx.onFx(() -> {
            Label error = assertInstanceOf(Label.class, broken.getChildren().get(0));
            assertTrue(error.getStyleClass().contains("diagram-error"));
            assertEquals(failed + "\nsyntax error in line 1 near 'digraf'", error.getText());
            assertEquals(
                    failed + "\nrender failed", ((Label) silent.getChildren().get(0)).getText(), "a silent tool");
            assertEquals(
                    failed + "\nno output produced",
                    ((Label) empty.getChildren().get(0)).getText());
            assertEquals(failed + "\n" + failed, ((Label) garbage.getChildren().get(0)).getText());
        });
    }

    @Test
    void onlyTheNewestDiagramRenderOfALiveSurfaceRuns() throws Exception {
        useDiagrams();
        ExecutorService pool = EditorFx.staticField(DiagramImages.class, "EXEC");
        Runnable release = EditorFx.holdPool(pool, 2);
        String surface = "dot-live-" + System.nanoTime();
        StackPane[] hosts = new StackPane[2];
        try {
            EditorFx.onFx(() -> {
                hosts[0] = (StackPane) DiagramImages.node(DiagramKind.DOT, unique("digraph old {}"), w -> w, surface);
                hosts[1] = (StackPane) DiagramImages.node(DiagramKind.DOT, unique("digraph new {}"), w -> w, surface);
            });
        } finally {
            release.run();
        }
        awaitRendered(hosts[1]);
        EditorFx.onFx(() -> {
            assertInstanceOf(ImageView.class, hosts[1].getChildren().get(0));
            assertTrue(rendering(hosts[0]), "the superseded edit was dropped before the tool was started");
        });
    }

    @Test
    void repointingADiagramToolForgetsItsCachedFailures() throws Exception {
        Map<DiagramKind, List<String>> none = new EnumMap<>(DiagramKind.class);
        none.put(DiagramKind.DOT, List.of(tools.resolve("no-such-dot").toString()));
        DiagramImages.configure(true, none, false);
        String source = unique("digraph install {}");
        StackPane failed = render(() -> DiagramImages.node(DiagramKind.DOT, source, w -> w));
        EditorFx.onFx(() -> assertInstanceOf(Label.class, failed.getChildren().get(0)));

        useDiagrams();
        StackPane retried = render(() -> DiagramImages.node(DiagramKind.DOT, source, w -> w));
        EditorFx.onFx(
                () -> assertInstanceOf(ImageView.class, retried.getChildren().get(0)));

        DiagramImages.configure(true, null, false); // no commands pushed: the configured ones stay
        DiagramImages.configure(true, Map.of(), false);
        StackPane kept = (StackPane) EditorFx.callFx(() -> DiagramImages.node(DiagramKind.DOT, source, w -> w));
        EditorFx.onFx(() -> assertInstanceOf(ImageView.class, kept.getChildren().get(0), "still cached"));
    }

    // ---- Typst -----------------------------------------------------------------------------------------

    private static void useTypst() {
        TypstImages.configure(true, List.of(typst.toString()));
    }

    private static StackPane typstNode(String source, String retainKey, String surface) {
        return (StackPane) TypstImages.node(source, width -> width, retainKey, surface, null, null, "report.typ");
    }

    @Test
    void aTypstDocumentRendersOnePictureForEachPage() throws Exception {
        useTypst();
        assertTrue(TypstImages.isEnabled());
        String retain = "typst-pages-" + System.nanoTime();
        String source = unique("= Report");
        StackPane host = render(() -> TypstImages.node(source, width -> width * 2, retain, null, null, "report.typ"));
        EditorFx.onFx(() -> {
            VBox pages = assertInstanceOf(VBox.class, host.getChildren().get(0));
            assertTrue(pages.getStyleClass().contains("typst-pages"));
            assertEquals(2, pages.getChildren().size());
            ImageView first =
                    assertInstanceOf(ImageView.class, pages.getChildren().get(0));
            assertEquals(80, first.getFitWidth(), 0.01, "40 logical px times the sizer");
            assertTrue(TypstImages.retained(retain));

            StackPane again = typstNode(source, retain, null);
            assertEquals(2, ((VBox) again.getChildren().get(0)).getChildren().size(), "a cache hit");
        });
        TypstImages.release(retain, null);
    }

    @Test
    void aLongTypstDocumentShowsItsFirstPagesAndSaysHowManyAreLeftOut() throws Exception {
        useTypst();
        String retain = "typst-many-" + System.nanoTime();
        StackPane host = render(() -> typstNode(unique("= MANY pages"), retain, null));
        EditorFx.onFx(() -> {
            VBox pages = (VBox) host.getChildren().get(0);
            assertEquals(41, pages.getChildren().size(), "forty pages and the note");
            Label note = assertInstanceOf(Label.class, pages.getChildren().get(40));
            assertEquals(Messages.tr("typst.morePages", 2), note.getText());
        });
        TypstImages.release(retain, null);
    }

    @Test
    void aTypstErrorKeepsTheLastGoodPagesUnderABanner() throws Exception {
        useTypst();
        String retain = "typst-retain-" + System.nanoTime();
        String failed = Messages.tr("typst.renderFailed");

        // With nothing rendered yet, the error stands alone — and names the user's file, not the temp input.
        StackPane first = render(() -> typstNode(unique("#nope BROKEN"), retain, null));
        EditorFx.onFx(() -> {
            Label error = assertInstanceOf(Label.class, first.getChildren().get(0));
            assertTrue(error.getStyleClass().contains("diagram-error"));
            assertTrue(error.getText().startsWith(failed + "\nerror: unknown variable: nope"), error.getText());
            assertTrue(error.getText().contains("report.typ:1:2"), error.getText());
            assertFalse(error.getText().contains(".editora-typst-"), error.getText());
        });

        render(() -> typstNode(unique("= Good"), retain, null));

        // The next edit re-renders: the previous pages stay up meanwhile instead of a "rendering" note.
        ExecutorService pool = EditorFx.staticField(TypstImages.class, "EXEC");
        Runnable release = EditorFx.holdPool(pool, 2);
        StackPane[] host = new StackPane[1];
        try {
            EditorFx.onFx(() -> {
                host[0] = typstNode(unique("#nope BROKEN again"), retain, null);
                VBox kept = assertInstanceOf(VBox.class, host[0].getChildren().get(0));
                assertEquals(2, kept.getChildren().size(), "the last good pages, no banner yet");
            });
        } finally {
            release.run();
        }
        EditorFx.awaitUntil(
                host[0]::getChildren,
                () -> host[0].getChildren().get(0) instanceof VBox box
                        && box.getChildren().get(0) instanceof Label);
        EditorFx.onFx(() -> {
            VBox pages = (VBox) host[0].getChildren().get(0);
            Label banner = (Label) pages.getChildren().get(0);
            assertTrue(banner.getStyleClass().contains("typst-error"));
            assertTrue(banner.getText().contains("unknown variable"), banner.getText());
            assertEquals(3, pages.getChildren().size(), "the banner above the two retained pages");
        });
        TypstImages.release(retain, null);
        assertFalse(TypstImages.retained(retain));
    }

    @Test
    void typstOutputThatIsNotAnImageOrNoOutputAtAllIsAFailedRender() throws Exception {
        useTypst();
        String failed = Messages.tr("typst.renderFailed");
        StackPane garbage = render(() -> typstNode(unique("= GARBAGE"), "typst-g-" + System.nanoTime(), null));
        StackPane nothing = render(() -> typstNode(unique("= NOPAGES"), "typst-n-" + System.nanoTime(), null));
        EditorFx.onFx(() -> {
            assertEquals(failed + "\n" + failed, ((Label) garbage.getChildren().get(0)).getText());
            assertEquals(
                    failed + "\nno pages rendered",
                    ((Label) nothing.getChildren().get(0)).getText());
        });
    }

    @Test
    void onlyTheNewestTypstRenderOfALiveSurfaceRunsAndAnOldFailureIsRetried() throws Exception {
        useTypst();
        ExecutorService pool = EditorFx.staticField(TypstImages.class, "EXEC");
        Runnable release = EditorFx.holdPool(pool, 2);
        String surface = "typst-live-" + System.nanoTime();
        String retain = surface + "-retain";
        StackPane[] hosts = new StackPane[2];
        try {
            EditorFx.onFx(() -> {
                hosts[0] = typstNode(unique("= Old"), retain, surface);
                hosts[1] = typstNode(unique("= New"), retain, surface);
            });
        } finally {
            release.run();
        }
        awaitRendered(hosts[1]);
        EditorFx.onFx(() -> {
            assertInstanceOf(VBox.class, hosts[1].getChildren().get(0));
            assertTrue(rendering(hosts[0]), "the superseded edit was dropped before the tool was started");
        });
        TypstImages.release(retain, surface);

        // A failure cached long ago (the tool was missing then) is not served for ever.
        String source = unique("= Retry");
        String key = EditorFx.call(
                TypstImages.class, "key", new Class<?>[] {String.class, Path.class, Path.class}, source, null, null);
        String retry = "typst-retry-" + System.nanoTime();
        TypstImages.store(key, new TypstImages.Cached(null, "typst not found", 0), retry, null);
        EditorFx.onFx(() -> {
            StackPane fresh = typstNode(source, retry, null);
            assertTrue(((Label) fresh.getChildren().get(0)).getText().contains("typst not found"));
        });
        TypstImages.store(key, new TypstImages.Cached(null, "typst not found", 0, 0L), retry, null);
        StackPane retried = render(() -> typstNode(source, retry, null));
        EditorFx.onFx(() -> assertInstanceOf(VBox.class, retried.getChildren().get(0)));

        // Re-pointing the tool drops cached results; an empty command keeps the configured one.
        TypstImages.configure(true, List.of());
        TypstImages.configure(true, null);
        assertTrue(TypstImages.cached(key));
        TypstImages.configure(true, List.of(typst.toString(), "--color", "never"));
        assertFalse(TypstImages.cached(key), "a different command invalidates what the old one produced");
        TypstImages.release(retry, null);
    }

    // ---- The buffer's own image previews ----------------------------------------------------------------

    private static StackPane imageHost(EditorBuffer buffer, String styleClass) {
        ScrollPane pane = EditorFx.call(buffer, "previewPane");
        Node host = pane.getContent().lookup("." + styleClass);
        assertNotNull(host, "the preview holds a ." + styleClass + " host");
        return (StackPane) host;
    }

    @Test
    void aMermaidFilePreviewsAsOneDiagramOnlyWhileTheFeatureIsOn() throws Exception {
        MermaidImages.configure(false, List.of(mmdc.toString()), List.of(maid.toString()), false);
        EditorBuffer[] ref = new EditorBuffer[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("mermaid");
            buffer.getArea().replaceText(unique("graph TD; File-->Preview"));
            assertTrue(buffer.isDiagram());
            assertFalse(buffer.hasPreview(), "Mermaid support is off: a .mmd file is plain text");
            ref[0] = buffer;
        });
        useMermaid(false, false);
        EditorBuffer buffer = ref[0];
        StackPane[] host = new StackPane[1];
        EditorFx.onFx(() -> {
            assertTrue(buffer.hasPreview());
            buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
            host[0] = imageHost(buffer, "md-mermaid");
        });
        awaitRendered(host[0]);
        StackPane[] zoomed = new StackPane[1];
        EditorFx.onFx(() -> {
            assertEquals(40, ((ImageView) host[0].getChildren().get(0)).getFitWidth(), 0.01);
            // Zoom re-fits the picture (a cache hit) rather than scaling a font.
            buffer.zoomPreviewIn();
            zoomed[0] = imageHost(buffer, "md-mermaid");
            assertEquals(44, ((ImageView) zoomed[0].getChildren().get(0)).getFitWidth(), 0.01);
            buffer.dispose();
        });
    }

    @Test
    void aDotFilePreviewsThroughGraphviz() throws Exception {
        useDiagrams();
        EditorBuffer[] ref = new EditorBuffer[1];
        StackPane[] host = new StackPane[1];
        EditorFx.onFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("dot");
            buffer.getArea().replaceText(unique("digraph file { a -> b }").replace("%%", "//"));
            assertEquals(DiagramKind.DOT, buffer.diagramKind());
            assertTrue(buffer.isRenderedDiagram());
            assertTrue(buffer.hasPreview());
            buffer.setMarkdownViewMode(MarkdownViewMode.SPLIT);
            host[0] = imageHost(buffer, "md-diagram");
            ref[0] = buffer;
        });
        awaitRendered(host[0]);
        EditorFx.onFx(() -> {
            assertInstanceOf(ImageView.class, host[0].getChildren().get(0));
            DiagramImages.configure(false, null, false);
            assertFalse(ref[0].hasPreview(), "with diagram support off the file has no preview");
            ref[0].dispose();
        });
        useDiagrams();
    }

    @Test
    void aTypstFilePreviewsItsPagesAndOffersPngAndSvgExport() throws Exception {
        useTypst();
        EditorBuffer[] ref = new EditorBuffer[1];
        StackPane[] host = new StackPane[1];
        Stage[] stage = new Stage[1];
        List<String> ran = new java.util.ArrayList<>();
        EditorFx.onFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("typst");
            buffer.getArea().replaceText(unique("= Typst file").replace("%%", "//"));
            assertFalse(buffer.hasTypstPreview(), "the feature gate is off");
            buffer.setTypstPreviewEnabled(true);
            buffer.setTypstPreviewEnabled(true);
            buffer.setTypstRootResolver(path -> path.getParent());
            buffer.setPreviewExportPngHandler(() -> ran.add("png"));
            buffer.setPreviewExportSvgHandler(() -> ran.add("svg"));
            stage[0] = EditorFx.show(buffer, 640, 320);
            buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
            host[0] = imageHost(buffer, "md-diagram");
            ref[0] = buffer;
        });
        awaitRendered(host[0]);
        EditorBuffer buffer = ref[0];
        EditorFx.onFx(() -> {
            assertEquals(2, ((VBox) host[0].getChildren().get(0)).getChildren().size(), "two pages");
            buffer.getNode().applyCss();
            buffer.getNode().layout();
            ScrollPane pane = EditorFx.call(buffer, "previewPane");
            pane.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 220, 220, false, null));
            ContextMenu menu = EditorFx.field(buffer, "previewContextMenu");
            EditorFx.menuItem(menu.getItems(), Messages.tr("command.typst.exportPng"))
                    .fire();
            EditorFx.menuItem(menu.getItems(), Messages.tr("command.typst.exportSvg"))
                    .fire();
            assertEquals(List.of("png", "svg"), ran);
            assertNull(EditorFx.findMenuItem(menu.getItems(), Messages.tr("command.preview.exportDocx")));
            menu.hide();

            buffer.setTypstPreviewEnabled(false);
            assertEquals(MarkdownViewMode.EDITOR, buffer.getMarkdownViewMode(), "no preview left: back to source");
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void anSvgFilePreviewsAsADrawingAndABrokenOneSaysSo() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        StackPane[] host = new StackPane[1];
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"50\"><!-- " + System.nanoTime()
                + " --><rect width=\"200\" height=\"50\"/></svg>";
        EditorFx.onFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setDisplayName("logo.svg");
            buffer.getArea().replaceText(svg);
            assertTrue(buffer.isSvg());
            assertFalse(buffer.isXml(), "an SVG gets the drawing, not the XML tree");
            assertFalse(buffer.hasPreview());
            buffer.setSvgPreviewEnabled(true);
            buffer.setSvgPreviewEnabled(true);
            assertTrue(buffer.hasSvgPreview());
            buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
            host[0] = imageHost(buffer, "md-svg");
            ref[0] = buffer;
        });
        EditorBuffer buffer = ref[0];
        EditorFx.awaitUntil(host[0]::getChildren, () -> !(host[0].getChildren().get(0) instanceof Label));
        StackPane[] broken = new StackPane[1];
        EditorFx.onFx(() -> {
            javafx.scene.layout.Region canvas =
                    (javafx.scene.layout.Region) host[0].getChildren().get(0);
            assertEquals(200, canvas.getPrefWidth(), 0.01, "drawn at its declared size");
            assertEquals(50, canvas.getPrefHeight(), 0.01);

            buffer.getArea().replaceText("<svg " + System.nanoTime() + " this is not xml");
            buffer.refreshPreview();
            broken[0] = imageHost(buffer, "md-svg");
        });
        EditorFx.awaitUntil(
                broken[0]::getChildren,
                () -> broken[0].getChildren().get(0).getStyleClass().contains("svg-error"));
        EditorFx.onFx(() -> {
            assertEquals(
                    Messages.tr("svg.renderFailed"),
                    ((Label) broken[0].getChildren().get(0)).getText());
            buffer.setSvgPreviewEnabled(false);
            assertEquals(MarkdownViewMode.EDITOR, buffer.getMarkdownViewMode());
            buffer.dispose();
        });
    }
}
