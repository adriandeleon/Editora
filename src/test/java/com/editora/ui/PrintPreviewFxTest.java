package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.PageRange;
import javafx.print.Paper;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.pdf.PdfText;
import com.editora.print.CodePrintLayout;
import com.editora.print.PrintService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Print Preview window, driven with a fake {@link PrintPreview.Job} and a hand-built
 * {@link PageLayout}. No {@code PrinterJob} is created anywhere in this class, so the Print… button here
 * can only ever reach the recorder below — never a printer.
 */
@Tag("fx")
class PrintPreviewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A page layout without a printer: {@code PageLayout}'s constructor is package-private. */
    static PageLayout layout(
            Paper paper, PageOrientation orientation, double left, double right, double top, double bottom)
            throws Exception {
        var ctor = PageLayout.class.getDeclaredConstructor(
                Paper.class, PageOrientation.class, double.class, double.class, double.class, double.class);
        ctor.setAccessible(true);
        return ctor.newInstance(paper, orientation, left, right, top, bottom);
    }

    static PageLayout letter() throws Exception {
        return layout(Paper.NA_LETTER, PageOrientation.PORTRAIT, 54, 54, 54, 54);
    }

    /** Records what the preview asks of the printer job; the "dialogs" answer from fields. */
    static final class FakeJob implements PrintPreview.Job {
        PageLayout layout;
        boolean dialogConfirms = true;
        PageLayout layoutAfterPrintDialog;
        PageLayout layoutAfterPageSetup;
        PageRange[] ranges;
        Runnable duringPrintPage = () -> {};
        final List<Node> printed = new ArrayList<>();
        int printDialogs;
        int ended;
        int cancelled;

        FakeJob(PageLayout layout) {
            this.layout = layout;
        }

        @Override
        public PageLayout layout() {
            return layout;
        }

        @Override
        public String printerName() {
            return "Test Printer";
        }

        @Override
        public boolean showPageSetup(Window owner) {
            if (layoutAfterPageSetup != null) {
                layout = layoutAfterPageSetup;
            }
            return dialogConfirms;
        }

        @Override
        public boolean showPrintDialog(Window owner) {
            printDialogs++;
            if (layoutAfterPrintDialog != null) {
                layout = layoutAfterPrintDialog;
                layoutAfterPrintDialog = null;
            }
            return dialogConfirms;
        }

        @Override
        public PageRange[] pageRanges() {
            return ranges;
        }

        @Override
        public boolean printPage(PageLayout pageLayout, Node page) {
            printed.add(page);
            duringPrintPage.run();
            return true;
        }

        @Override
        public boolean endJob() {
            ended++;
            return true;
        }

        @Override
        public void cancel() {
            cancelled++;
        }
    }

    /** Eager pages, one {@code Region} each, counting how often it is asked to paginate. */
    static final class FakePaginator implements PrintService.Paginator {
        final int pageCount;
        int calls;
        final List<List<Node>> built = new ArrayList<>();
        Throwable failure;

        FakePaginator(int pageCount) {
            this.pageCount = pageCount;
        }

        @Override
        public List<Node> paginate(PageLayout layout) {
            calls++;
            if (failure instanceof Error e) {
                throw e;
            }
            if (failure instanceof RuntimeException e) {
                throw e;
            }
            List<Node> pages = new ArrayList<>();
            for (int i = 0; i < pageCount; i++) {
                Region page = new Region();
                page.setPrefSize(layout.getPrintableWidth(), layout.getPrintableHeight());
                pages.add(page);
            }
            built.add(pages);
            return pages;
        }
    }

    private static final class Outcome {
        final List<PrintService.Result> results = new ArrayList<>();
        int printing;
        int cancels;
    }

    private PrintPreview preview;
    private final Outcome outcome = new Outcome();

    private PrintPreview open(FakeJob job, PrintService.Paginator paginator) throws Exception {
        preview = FxTestSupport.callOnFx(() -> {
            PrintPreview p = new PrintPreview(
                    null, job, paginator, outcome.results::add, () -> outcome.printing++, () -> outcome.cancels++);
            p.show();
            return p;
        });
        settle();
        return preview;
    }

    @AfterEach
    void close() throws Exception {
        if (preview != null) {
            FxTestSupport.runOnFx(() -> stage().close());
        }
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            Thread.sleep(60);
            FxTestSupport.drainFx();
        }
    }

    private Stage stage() {
        return FxTestSupport.field(preview, "stage");
    }

    private <T> T part(String name) {
        return FxTestSupport.field(preview, name);
    }

    private String pageText() throws Exception {
        return FxTestSupport.callOnFx(() -> this.<Label>part("pageLabel").getText());
    }

    private String notice() throws Exception {
        return FxTestSupport.callOnFx(() -> this.<Label>part("notice").getText());
    }

    private void press(KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Scene scene = stage().getScene();
            javafx.event.EventTarget target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene;
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        });
    }

    private void fire(String button) throws Exception {
        FxTestSupport.runOnFx(() -> this.<Button>part(button).fire());
        settle();
    }

    private static String page(int n, int of) {
        return tr("print.preview.page", n, of);
    }

    @Test
    void theSheetIsThePaperWithThePageInsetByTheMargins() throws Exception {
        open(new FakeJob(layout(Paper.NA_LETTER, PageOrientation.PORTRAIT, 54, 36, 72, 18)), new FakePaginator(2));
        FxTestSupport.runOnFx(() -> {
            StackPane sheet = part("sheet");
            assertEquals(612, sheet.getWidth(), 0.5, "the sheet is Letter wide, not the printable width");
            assertEquals(792, sheet.getHeight(), 0.5);
            Bounds page = sheet.getChildren().get(0).getBoundsInParent();
            assertEquals(54, page.getMinX(), 0.5, "left margin");
            assertEquals(72, page.getMinY(), 0.5, "top margin");
            assertEquals(612 - 54 - 36, page.getWidth(), 0.5, "printable width");
            assertEquals(792 - 72 - 18, page.getHeight(), 0.5, "printable height");
            assertTrue(this.<Label>part("infoLabel").getText().contains("Test Printer"));
            assertTrue(this.<Label>part("infoLabel").getText().contains(Paper.NA_LETTER.getName()));
            assertTrue(this.<Label>part("infoLabel").getText().contains(tr("print.preview.portrait")));
        });
    }

    @Test
    void aLandscapeSheetSwapsThePaperSides() throws Exception {
        PageLayout landscape = layout(Paper.A4, PageOrientation.LANDSCAPE, 30, 30, 40, 40);
        assertEquals(842, PrintPreview.sheetSize(landscape).getWidth(), 0.5);
        assertEquals(595, PrintPreview.sheetSize(landscape).getHeight(), 0.5);
        open(new FakeJob(landscape), new FakePaginator(1));
        FxTestSupport.runOnFx(() -> {
            StackPane sheet = part("sheet");
            assertEquals(842, sheet.getWidth(), 0.5);
            assertEquals(595, sheet.getHeight(), 0.5);
            assertTrue(this.<Label>part("infoLabel").getText().contains(tr("print.preview.landscape")));
        });
    }

    @Test
    void pageKeysTurnThePageWhereverTheFocusIs() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(4));
        assertEquals(page(1, 4), pageText());
        press(KeyCode.PAGE_DOWN);
        assertEquals(page(2, 4), pageText());
        press(KeyCode.RIGHT);
        assertEquals(page(3, 4), pageText());
        press(KeyCode.LEFT);
        assertEquals(page(2, 4), pageText());
        press(KeyCode.END);
        assertEquals(page(4, 4), pageText());
        press(KeyCode.PAGE_DOWN); // already on the last page
        assertEquals(page(4, 4), pageText());
        press(KeyCode.PAGE_UP);
        assertEquals(page(3, 4), pageText());
        press(KeyCode.HOME);
        assertEquals(page(1, 4), pageText());
        // The same keys from a focused button: a scene filter, so the button's focus traversal does not eat them.
        FxTestSupport.runOnFx(() -> this.<Button>part("close").requestFocus());
        press(KeyCode.RIGHT);
        assertEquals(page(2, 4), pageText());
    }

    @Test
    void downScrollsTheSheetAndTurnsThePageAtItsEnd() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(3));
        FxTestSupport.runOnFx(() -> stage().setHeight(420)); // the sheet no longer fits: it scrolls
        settle();
        ScrollPane scroll = part("scroll");
        press(KeyCode.DOWN);
        assertEquals(page(1, 3), pageText(), "Down scrolls first");
        assertTrue(FxTestSupport.callOnFx(scroll::getVvalue) > 0);
        FxTestSupport.runOnFx(() -> scroll.setVvalue(scroll.getVmax()));
        press(KeyCode.DOWN);
        assertEquals(page(2, 3), pageText(), "Down at the bottom goes to the next page");
        assertEquals(0, FxTestSupport.callOnFx(scroll::getVvalue), 1e-9);
        press(KeyCode.UP);
        assertEquals(page(1, 3), pageText(), "Up at the top goes to the previous page");
    }

    @Test
    void aNewPageStartsAtItsTop() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(3));
        FxTestSupport.runOnFx(() -> stage().setHeight(420));
        settle();
        ScrollPane scroll = part("scroll");
        FxTestSupport.runOnFx(() -> scroll.setVvalue(1.0));
        assertEquals(1.0, FxTestSupport.callOnFx(scroll::getVvalue), 1e-9, "precondition: the sheet scrolls");
        fire("next");
        assertEquals(page(2, 3), pageText());
        assertEquals(0, FxTestSupport.callOnFx(scroll::getVvalue), 1e-9, "page 2 must not open at its last lines");
    }

    @Test
    void focusStartsOnThePageAndTheArrowsAreDescribed() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(2));
        FxTestSupport.runOnFx(() -> {
            assertSame(part("scroll"), stage().getScene().getFocusOwner(), "focus starts on the page, not a button");
            Button prev = part("prev");
            Button next = part("next");
            assertEquals(tr("print.preview.previous"), prev.getAccessibleText());
            assertEquals(tr("print.preview.next"), next.getAccessibleText());
            assertEquals(tr("print.preview.previous"), prev.getTooltip().getText());
            assertEquals(tr("print.preview.next"), next.getTooltip().getText());
            assertEquals(javafx.stage.Modality.WINDOW_MODAL, stage().getModality());
            assertTrue(stage().getMinWidth() >= 400 && stage().getMinHeight() >= 300, "a minimum size is set");
        });
    }

    @Test
    void printReusesThePreviewedPagesAndHonoursThePageRange() throws Exception {
        FakeJob job = new FakeJob(letter());
        job.ranges = new PageRange[] {new PageRange(2, 3)};
        FakePaginator paginator = new FakePaginator(5);
        open(job, paginator);
        fire("print");
        assertEquals(1, paginator.calls, "the same layout must not be paginated a second time for the print");
        List<Node> previewed = paginator.built.get(0);
        assertEquals(List.of(previewed.get(1), previewed.get(2)), job.printed, "pages 2-3, as previewed");
        assertEquals(1, job.ended);
        assertEquals(1, outcome.printing);
        assertEquals(1, outcome.results.size());
        assertTrue(outcome.results.get(0).ok());
        assertFalse(FxTestSupport.callOnFx(() -> stage().isShowing()));
    }

    /** The page on screen is printed too: it must leave the scaled sheet and sit at the origin. */
    @Test
    void thePageOnScreenIsDetachedBeforeItIsPrinted() throws Exception {
        FakeJob job = new FakeJob(letter());
        List<double[]> seen = new ArrayList<>();
        job.duringPrintPage = () -> {
            Node page = job.printed.get(job.printed.size() - 1);
            seen.add(new double[] {page.getParent() == null ? 0 : 1, page.getLayoutX(), page.getLayoutY()});
        };
        open(job, new FakePaginator(2));
        fire("print");
        assertEquals(2, seen.size());
        for (double[] s : seen) {
            assertArrayEquals(new double[] {0, 0, 0}, s, "no parent, at the origin");
        }
    }

    @Test
    void aLayoutChangedInThePrintDialogIsPreviewedInsteadOfPrinted() throws Exception {
        FakeJob job = new FakeJob(letter());
        job.layoutAfterPrintDialog = layout(Paper.A4, PageOrientation.LANDSCAPE, 54, 54, 54, 54);
        FakePaginator paginator = new FakePaginator(3);
        open(job, paginator);
        fire("print");
        assertTrue(job.printed.isEmpty(), "a pagination the user never saw must not be printed");
        assertEquals(0, job.ended);
        assertEquals(0, outcome.printing);
        assertTrue(outcome.results.isEmpty());
        assertTrue(FxTestSupport.callOnFx(() -> stage().isShowing()), "the preview stays open");
        assertEquals(2, paginator.calls, "re-paginated for the new layout");
        assertEquals(tr("print.preview.layoutChanged"), notice());
        FxTestSupport.runOnFx(() -> {
            assertEquals(842, this.<StackPane>part("sheet").getPrefWidth(), 0.5, "the sheet is now A4 landscape");
            assertSame(
                    paginator.built.get(1).get(0),
                    this.<StackPane>part("sheet").getChildren().get(0),
                    "showing the new pages");
        });

        fire("print"); // the user has seen it and presses Print… again
        assertEquals(2, job.printDialogs);
        assertEquals(2, paginator.calls, "unchanged this time: printed from the previewed pages");
        assertEquals(paginator.built.get(1), job.printed);
        assertTrue(outcome.results.get(0).ok());
    }

    @Test
    void pageSetupRepaginatesForTheNewLayout() throws Exception {
        FakeJob job = new FakeJob(letter());
        FakePaginator paginator = new FakePaginator(2);
        open(job, paginator);
        fire("setup"); // confirmed without a change
        assertEquals(1, paginator.calls, "an unchanged layout is not paginated again");
        job.layoutAfterPageSetup = layout(Paper.NA_LETTER, PageOrientation.LANDSCAPE, 54, 54, 54, 54);
        fire("setup");
        assertEquals(2, paginator.calls);
        FxTestSupport.runOnFx(() -> {
            assertEquals(792, this.<StackPane>part("sheet").getPrefWidth(), 0.5);
            assertTrue(this.<Label>part("infoLabel").getText().contains(tr("print.preview.landscape")));
        });
        assertTrue(job.printed.isEmpty());
    }

    @Test
    void layoutsAreComparedByPaperOrientationAndMargins() throws Exception {
        PageLayout base = letter();
        assertTrue(PrintPreview.sameLayout(base, letter()), "equal values, different objects");
        assertTrue(
                PrintPreview.sameLayout(base, layout(Paper.NA_LETTER, PageOrientation.PORTRAIT, 54.2, 54, 53.9, 54)));
        assertFalse(PrintPreview.sameLayout(base, layout(Paper.A4, PageOrientation.PORTRAIT, 54, 54, 54, 54)));
        assertFalse(PrintPreview.sameLayout(base, layout(Paper.NA_LETTER, PageOrientation.LANDSCAPE, 54, 54, 54, 54)));
        assertFalse(PrintPreview.sameLayout(base, layout(Paper.NA_LETTER, PageOrientation.PORTRAIT, 54, 54, 72, 54)));
        assertFalse(PrintPreview.sameLayout(base, null));
    }

    @Test
    void aPageRangeOutsideTheDocumentPrintsNothingAndSaysSo() throws Exception {
        FakeJob job = new FakeJob(letter());
        job.ranges = new PageRange[] {new PageRange(8, 9)};
        open(job, new FakePaginator(3));
        fire("print");
        assertTrue(job.printed.isEmpty());
        assertEquals(0, job.ended, "the job is not started, so Print… can be chosen again");
        assertEquals(tr("print.preview.rangeEmpty", 3), notice());
        assertTrue(FxTestSupport.callOnFx(() -> stage().isShowing()));
    }

    @Test
    void cancelDuringAPrintStopsBetweenPagesAndReportsACancel() throws Exception {
        FakeJob job = new FakeJob(letter());
        open(job, new FakePaginator(5));
        job.duringPrintPage = () -> {
            if (job.printed.size() == 2) {
                this.<Button>part("close").fire(); // "Cancel" while page 2 is being rendered
            }
        };
        fire("print");
        assertEquals(2, job.printed.size(), "no page is submitted after the cancel");
        assertEquals(1, job.cancelled);
        assertEquals(0, job.ended);
        assertEquals(1, outcome.cancels);
        assertTrue(outcome.results.isEmpty());
        assertFalse(FxTestSupport.callOnFx(() -> stage().isShowing()));
    }

    @Test
    void aCancelledPrintDialogLeavesThePreviewOpen() throws Exception {
        FakeJob job = new FakeJob(letter());
        job.dialogConfirms = false;
        open(job, new FakePaginator(2));
        fire("print");
        assertTrue(job.printed.isEmpty());
        assertTrue(FxTestSupport.callOnFx(() -> stage().isShowing()));
        fire("close");
        assertEquals(1, outcome.cancels);
    }

    /** An {@code Error} (the measured case was an OutOfMemoryError) must end in a reported failure. */
    @Test
    void aPaginationFailureAfterPageSetupClosesThePreviewWithAnError() throws Exception {
        FakeJob job = new FakeJob(letter());
        FakePaginator paginator = new FakePaginator(2);
        open(job, paginator);
        paginator.failure = new OutOfMemoryError("Java heap space");
        job.layoutAfterPageSetup = layout(Paper.A4, PageOrientation.PORTRAIT, 54, 54, 54, 54);
        fire("setup");
        assertEquals(1, outcome.results.size());
        assertFalse(outcome.results.get(0).ok());
        assertEquals("Java heap space", outcome.results.get(0).message());
        assertFalse(FxTestSupport.callOnFx(() -> stage().isShowing()));
    }

    @Test
    void aFailureWhilePrintingCancelsTheJobAndIsReported() throws Exception {
        FakeJob job = new FakeJob(letter());
        open(job, new FakePaginator(3));
        job.duringPrintPage = () -> {
            throw new IllegalStateException("printer went away");
        };
        fire("print");
        assertEquals(1, job.cancelled);
        assertEquals(1, outcome.results.size());
        assertEquals("printer went away", outcome.results.get(0).message());
        assertFalse(FxTestSupport.callOnFx(() -> stage().isShowing()));
    }

    /** Lazy pages: opening a long document builds the page shown and nothing else. */
    @Test
    void onlyThePageOnScreenIsBuilt() throws Exception {
        List<Integer> asked = new ArrayList<>();
        PrintService.Pages lazy = new PrintService.Pages() {
            @Override
            public int count() {
                return 1200;
            }

            @Override
            public Node get(int index) {
                asked.add(index);
                return new Region();
            }
        };
        PrintService.Paginator paginator = new PrintService.Paginator() {
            @Override
            public List<Node> paginate(PageLayout l) {
                throw new AssertionError("the preview must ask for pages(), not for every node");
            }

            @Override
            public PrintService.Pages pages(PageLayout l) {
                return lazy;
            }
        };
        open(new FakeJob(letter()), paginator);
        assertEquals(page(1, 1200), pageText());
        assertEquals(List.of(0), asked);
        press(KeyCode.END);
        assertEquals(List.of(0, 1199), asked);
    }

    private static List<List<PdfText.Run>> code(int lines) {
        List<List<PdfText.Run>> out = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            String text = i % 7 == 0 ? "x".repeat(300) : "line " + i; // every 7th line wraps
            out.add(List.of(new PdfText.Run(text, java.awt.Color.BLACK, false, false)));
        }
        return out;
    }

    private static List<String> rows(Node page) {
        List<String> rows = new ArrayList<>();
        for (Node row : ((javafx.scene.layout.Pane) page).getChildren()) {
            StringBuilder sb = new StringBuilder();
            collectText(row, sb);
            rows.add(sb.toString());
        }
        return rows;
    }

    private static void collectText(Node node, StringBuilder sb) {
        if (node instanceof javafx.scene.text.Text t) {
            sb.append(t.getText()).append('|');
        } else if (node instanceof javafx.scene.Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collectText(c, sb));
        }
    }

    /** The on-demand code pages are the eager ones, page for page, and are not held once handed out. */
    @Test
    void lazyCodePagesMatchTheEagerPagination() throws Exception {
        PageLayout layout = letter();
        FxTestSupport.runOnFx(() -> {
            Font mono = Font.font("JetBrains Mono", CodePrintLayout.FONT_SIZE);
            List<List<PdfText.Run>> lines = code(401);
            // The eager reference: every visual line in order, cut into pages of linesPerPage rows.
            double lineH = Math.ceil(mono.getSize() * 1.3);
            int perPage = CodePrintLayout.linesPerPage(layout.getPrintableHeight(), lineH);
            PrintService.Pages pages = CodePrintLayout.pages(lines, layout, true, mono);
            List<Node> eager = CodePrintLayout.paginate(lines, layout, true, mono);
            assertEquals(eager.size(), pages.count());
            assertTrue(pages.count() > 5, "enough pages to mean something: " + pages.count());
            List<String> all = new ArrayList<>();
            for (int i = 0; i < pages.count(); i++) {
                List<String> rows = rows(pages.get(i));
                assertEquals(rows(eager.get(i)), rows, "page " + (i + 1));
                assertTrue(rows.size() <= perPage);
                if (i < pages.count() - 1) {
                    assertEquals(perPage, rows.size(), "a full page " + (i + 1));
                }
                all.addAll(rows);
            }
            assertTrue(all.get(0).startsWith("1|xxx"), all.get(0));
            assertTrue(all.get(1).startsWith("|xxx"), "a wrapped continuation has a blank gutter: " + all.get(1));
            assertTrue(all.get(all.size() - 1).startsWith("401|line 400"), "the last line ends the last page");
            assertNotSame(pages.get(0), pages.get(0), "a page is built per request, not kept");
        });
    }

    /** A 60,000-line file: the page count is known and one page is built, not half a million nodes. */
    @Test
    void aVeryLongFileIsPaginatedWithoutBuildingItsPages() throws Exception {
        PageLayout layout = letter();
        FxTestSupport.runOnFx(() -> {
            Font mono = Font.font("JetBrains Mono", CodePrintLayout.FONT_SIZE);
            List<List<PdfText.Run>> lines = new ArrayList<>();
            List<PdfText.Run> one = List.of(new PdfText.Run("int x = 0;", java.awt.Color.BLACK, false, false));
            for (int i = 0; i < 60_000; i++) {
                lines.add(one);
            }
            PrintService.Pages pages = CodePrintLayout.pages(lines, layout, true, mono);
            int perPage = CodePrintLayout.linesPerPage(layout.getPrintableHeight(), Math.ceil(mono.getSize() * 1.3));
            assertEquals((60_000 + perPage - 1) / perPage, pages.count());
            List<String> last = rows(pages.get(pages.count() - 1));
            assertTrue(last.get(last.size() - 1).startsWith("60000|"), last.get(last.size() - 1));
        });
    }
}
