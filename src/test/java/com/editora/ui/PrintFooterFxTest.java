package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import com.editora.pdf.PdfText;
import com.editora.print.CodePrintLayout;
import com.editora.print.PageFooter;
import com.editora.print.PrintService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The footer line on printed pages — document name and "Page n of N" — on code pages and through
 * {@link PrintService}, on and off. (Markdown pages: {@code MarkdownPrintLayoutFxTest}.) No printer: the
 * page layouts are built directly, and nothing is ever sent to a job.
 */
@Tag("fx")
class PrintFooterFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final double PW = 504;
    private static final double PH = 684;

    private static List<List<PdfText.Run>> lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            text.append("line ").append(i).append(i < count ? "\n" : "");
        }
        return PdfText.splitIntoLineRuns(text.toString(), null, 4);
    }

    private static String text(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder out) {
        if (node instanceof Text t) {
            out.append(t.getText()).append('\u0001');
        } else if (node instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collect(c, out));
        }
    }

    /** A page as the printer lays it out: in a scene of its own, CSS applied. */
    private static void layOut(Node page) {
        if (page.getScene() == null && page.getParent() == null) {
            new javafx.scene.Scene(new javafx.scene.Group(page));
        }
        Parent root = page.getParent() == null ? (Parent) page : page.getParent();
        root.applyCss();
        root.layout();
    }

    @Test
    void codePagesCarryTheNameAndPageNOfNAndHoldFewerLines() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Font mono = Font.font("JetBrains Mono", CodePrintLayout.FONT_SIZE);
            List<List<PdfText.Run>> lines = lines(300);
            PrintService.Pages plain = CodePrintLayout.pages(lines, PW, PH, true, mono, null);
            PrintService.Pages footed = CodePrintLayout.pages(lines, PW, PH, true, mono, PageFooter.of("Main.java"));
            assertTrue(footed.count() >= plain.count(), "the footer can only cost lines");

            List<String> printed = new ArrayList<>();
            for (int i = 0; i < footed.count(); i++) {
                Region page = (Region) footed.get(i);
                layOut(page);
                assertEquals(PW, page.getWidth(), 0.5, "a page with a footer is the printable area");
                assertEquals(PH, page.getHeight(), 0.5);
                Node footer = page.lookup(".print-page-footer");
                assertNotNull(footer, "page " + (i + 1) + " has a footer");
                String label = text(footer);
                assertTrue(label.startsWith("Main.java"), label);
                assertTrue(label.contains(tr("print.footer.page", i + 1, footed.count())), label);
                javafx.geometry.Bounds f = footer.localToScene(footer.getBoundsInLocal());
                assertTrue(f.getMaxY() <= PH + 0.5, "the footer is inside the page");
                Node rows = page.getChildrenUnmodifiable().get(0);
                javafx.geometry.Bounds r = rows.localToScene(rows.getBoundsInLocal());
                assertTrue(r.getMaxY() <= f.getMinY() + 0.5, "the code stops above the footer");
                for (String s : text(rows).split("\u0001")) {
                    if (s.startsWith("line ")) {
                        printed.add(s);
                    }
                }
            }
            assertEquals(300, printed.size(), "every line prints once");
            assertEquals("line 300", printed.get(299));

            // off: the bare column of rows it always was, no footer anywhere
            for (int i = 0; i < plain.count(); i++) {
                Node page = plain.get(i);
                layOut(page);
                assertNull(page.lookup(".print-page-footer"));
                assertTrue(((Region) page).getHeight() <= PH + 0.5);
            }
        });
    }

    @Test
    void aLongDocumentNameIsCutShortRatherThanRunIntoThePageNumber() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node line =
                    PageFooter.of("a-very-long-file-name-".repeat(20) + ".md").line(1, 1, PW);
            layOut(line);
            List<Node> parts = ((Parent) line).getChildrenUnmodifiable();
            Text name = (Text) parts.get(0);
            Text number = (Text) parts.get(parts.size() - 1);
            assertTrue(name.getText().endsWith("…"), name.getText());
            assertTrue(
                    name.getBoundsInParent().getMaxX()
                            < number.getBoundsInParent().getMinX(),
                    "the name ends before the page number starts");
            assertEquals(
                    tr("print.footer.page", 1, 1),
                    text(PageFooter.of(null).line(1, 1, PW)).replace("\u0001", ""));
        });
    }

    private static PrintService.Prepared await(
            java.util.function.Consumer<java.util.function.Consumer<PrintService.Prepared>> start) throws Exception {
        CompletableFuture<PrintService.Prepared> ready = new CompletableFuture<>();
        start.accept(ready::complete);
        return ready.get(30, TimeUnit.SECONDS);
    }

    /** The service's flag reaches the pages, for code, Markdown and a built document (CSV) alike. */
    @Test
    void theServicePutsTheFooterOnOnlyWhenAsked() throws Exception {
        PrintService service = new PrintService();
        try {
            PageLayout layout = PrintPreviewFxTest.letter();
            PageLayout wide = PrintPreviewFxTest.layout(Paper.A4, PageOrientation.LANDSCAPE, 54, 54, 54, 54);
            String code = "class A {}\n".repeat(200);
            String md = "# T\n\n" + "paragraph of text\n\n".repeat(80);
            org.commonmark.node.Node table =
                    com.editora.markdown.CsvTableDocument.fromCsv("a,b\n" + "1,2\n".repeat(120));

            List<PrintService.Prepared> on = List.of(
                    await(cb -> service.prepareCode(code, "A.java", false, true, 4, "A.java", true, cb)),
                    await(cb -> service.prepareMarkdown(md, null, "A.java", true, cb)),
                    await(cb -> service.prepareDocument(table, null, "A.java", true, cb)));
            List<PrintService.Prepared> off = List.of(
                    await(cb -> service.prepareCode(code, "A.java", false, true, 4, "A.java", false, cb)),
                    await(cb -> service.prepareMarkdown(md, null, "A.java", false, cb)),
                    await(cb -> service.prepareDocument(table, null, "A.java", false, cb)),
                    await(cb -> service.prepareCode(code, "A.java", false, true, 4, cb)),
                    await(cb -> service.prepareMarkdown(md, null, cb)),
                    await(cb -> service.prepareDocument(table, null, cb)));
            FxTestSupport.runOnFx(() -> {
                for (PageLayout l : List.of(layout, wide)) {
                    for (PrintService.Prepared prepared : on) {
                        assertTrue(prepared.ok(), prepared.error());
                        PrintService.Pages pages = prepared.paginator().pages(l);
                        assertTrue(pages.count() > 1);
                        for (int i = 0; i < pages.count(); i++) {
                            Node page = pages.get(i);
                            layOut(page);
                            Node footer = page.lookup(".print-page-footer");
                            assertNotNull(footer, "footer on page " + (i + 1));
                            String label = text(footer).replace("\u0001", " ");
                            assertTrue(label.contains("A.java"), label);
                            assertTrue(label.contains(tr("print.footer.page", i + 1, pages.count())), label);
                            javafx.geometry.Bounds f = footer.localToScene(footer.getBoundsInLocal());
                            assertTrue(f.getMaxX() <= l.getPrintableWidth() + 0.5, "footer inside the page width");
                            assertTrue(f.getMaxY() <= l.getPrintableHeight() + 0.5, "footer inside the page height");
                        }
                    }
                    for (PrintService.Prepared prepared : off) {
                        assertTrue(prepared.ok(), prepared.error());
                        for (Node page : prepared.paginator().paginate(l)) {
                            layOut(page);
                            assertNull(page.lookup(".print-page-footer"), "no footer when it is off");
                        }
                    }
                    // off through the new signature is exactly the old signature
                    for (int k = 0; k < 3; k++) {
                        assertEquals(
                                off.get(k + 3).paginator().pages(l).count(),
                                off.get(k).paginator().pages(l).count());
                    }
                }
            });
        } finally {
            service.shutdown();
        }
    }

    /** A file that ends in a newline does not print a last numbered line with nothing on it. */
    @Test
    void theServiceDropsTheEmptyLineAfterAFinalNewline() throws Exception {
        PrintService service = new PrintService();
        try {
            PageLayout layout = PrintPreviewFxTest.letter();
            PrintService.Prepared prepared =
                    await(cb -> service.prepareCode("one\ntwo\n", "a.txt", false, true, 4, cb));
            FxTestSupport.runOnFx(() -> {
                PrintService.Pages pages = prepared.paginator().pages(layout);
                assertEquals(1, pages.count());
                List<String> shown = new ArrayList<>(List.of(text(pages.get(0)).split("\u0001")));
                shown.removeIf(String::isEmpty);
                assertEquals(List.of("1", "one", "2", "two"), shown, "two numbered lines, not three");
            });
        } finally {
            service.shutdown();
        }
    }
}
