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
        return FxTestSupport.callOnFx(() -> preview.pageText());
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
        FxTestSupport.runOnFx(() -> {
            preview.setZoom(PrintZoom.Setting.percent(1)); // Fit Page, the default, never scrolls
            stage().setHeight(420); // the sheet no longer fits: it scrolls
        });
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
        FxTestSupport.runOnFx(() -> {
            preview.setZoom(PrintZoom.Setting.percent(1));
            stage().setHeight(420);
        });
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

    // --- zoom (A6) ---

    private double factor() throws Exception {
        return FxTestSupport.callOnFx(() -> preview.zoomFactor());
    }

    private PrintZoom.Setting setting() throws Exception {
        return FxTestSupport.callOnFx(() -> preview.zoomSetting());
    }

    private void zoomTo(PrintZoom.Setting setting) throws Exception {
        FxTestSupport.runOnFx(() -> preview.setZoom(setting));
        settle();
    }

    private void press(KeyCode code, boolean control) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Scene scene = stage().getScene();
            javafx.event.EventTarget target = scene.getFocusOwner() != null ? scene.getFocusOwner() : scene;
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false));
        });
        settle();
    }

    private void wheel(double deltaY, boolean control) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ScrollPane scroll = part("scroll");
            Event.fireEvent(
                    scroll,
                    new javafx.scene.input.ScrollEvent(
                            javafx.scene.input.ScrollEvent.SCROLL,
                            10,
                            10,
                            10,
                            10,
                            false,
                            control,
                            false,
                            false,
                            false,
                            false,
                            0,
                            deltaY,
                            0,
                            deltaY,
                            javafx.scene.input.ScrollEvent.HorizontalTextScrollUnits.NONE,
                            0,
                            javafx.scene.input.ScrollEvent.VerticalTextScrollUnits.NONE,
                            0,
                            0,
                            null));
        });
        settle();
    }

    /** The whole sheet, shadow included, is inside the viewport — and the viewport shows no scroll bar. */
    @Test
    void thePreviewOpensWithTheWholePageVisible() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(3));
        assertEquals(PrintZoom.Setting.FIT_PAGE, setting());
        FxTestSupport.runOnFx(() -> {
            ScrollPane scroll = part("scroll");
            StackPane sheet = part("sheet");
            Bounds shown = sheet.getParent().getBoundsInParent(); // the scaled sheet, in the holder
            assertTrue(shown.getHeight() <= scroll.getViewportBounds().getHeight(), "the page fits in height");
            assertTrue(shown.getWidth() <= scroll.getViewportBounds().getWidth(), "and in width");
            assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getVbarPolicy());
            assertEquals(
                    PrintZoom.percentOf(preview.zoomFactor()) + "%",
                    this.<javafx.scene.control.MenuButton>part("zoomMenu").getText(),
                    "the zoom button shows the scale in effect");
        });
    }

    /** The cap at 100% is gone: in a large window the page grows to use it. */
    @Test
    void aFitGrowsPastActualSizeInALargeWindow() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(2));
        FxTestSupport.runOnFx(() -> {
            stage().setWidth(1150);
            stage().setHeight(700);
        });
        zoomTo(PrintZoom.Setting.FIT_WIDTH);
        assertTrue(factor() > 1.3, "612 pt of paper in an 1150 px window is well over 100%: " + factor());
        FxTestSupport.runOnFx(() -> {
            ScrollPane scroll = part("scroll");
            StackPane sheet = part("sheet");
            double shown = sheet.getPrefWidth() * preview.zoomFactor();
            assertTrue(shown <= scroll.getViewportBounds().getWidth(), "the sheet is not wider than the viewport");
            assertTrue(shown > scroll.getViewportBounds().getWidth() - 80, "and nearly fills it");
        });
        double fitWidth = factor();
        zoomTo(PrintZoom.Setting.FIT_PAGE);
        assertTrue(factor() < fitWidth, "a portrait page that must fit in height is smaller than one fit to width");
    }

    @Test
    void theZoomButtonsMenuKeysAndWheelChangeTheScale() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(2));
        zoomTo(PrintZoom.Setting.percent(1));
        fire("zoomIn");
        assertEquals(PrintZoom.Setting.percent(1.25), setting());
        assertEquals(1.25, factor(), 1e-9);
        assertEquals(
                "125%",
                FxTestSupport.callOnFx(() ->
                        this.<javafx.scene.control.MenuButton>part("zoomMenu").getText()));
        fire("zoomOut");
        fire("zoomOut");
        assertEquals(0.75, factor(), 1e-9);

        press(KeyCode.EQUALS, true); // Ctrl + (the unshifted key)
        assertEquals(1.0, factor(), 1e-9);
        press(KeyCode.ADD, true);
        assertEquals(1.25, factor(), 1e-9);
        press(KeyCode.MINUS, true);
        assertEquals(1.0, factor(), 1e-9);
        press(KeyCode.DIGIT0, true);
        assertEquals(PrintZoom.Setting.FIT_PAGE, setting(), "Ctrl+0 fits the page");
        press(KeyCode.EQUALS, false);
        assertEquals(PrintZoom.Setting.FIT_PAGE, setting(), "a plain = is not a zoom key");

        zoomTo(PrintZoom.Setting.percent(1));
        wheel(40, true);
        assertEquals(1.25, factor(), 1e-9, "Ctrl+wheel up zooms in");
        wheel(-40, true);
        wheel(-40, true);
        assertEquals(0.75, factor(), 1e-9, "Ctrl+wheel down zooms out");
        wheel(-40, false);
        assertEquals(0.75, factor(), 1e-9, "a plain wheel only scrolls");

        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.MenuButton menu = part("zoomMenu");
            javafx.scene.control.MenuItem twoHundred = menu.getItems().stream()
                    .filter(i -> "200%".equals(i.getText()))
                    .findFirst()
                    .orElseThrow();
            twoHundred.fire();
        });
        settle();
        assertEquals(2.0, factor(), 1e-9);
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.MenuButton menu = part("zoomMenu");
            assertEquals(
                    List.of(tr("print.preview.fitPage"), tr("print.preview.fitWidth")),
                    menu.getItems().stream()
                            .limit(2)
                            .map(javafx.scene.control.MenuItem::getText)
                            .toList());
            assertTrue(
                    menu.getItems().stream()
                            .filter(i -> i instanceof javafx.scene.control.RadioMenuItem r && r.isSelected())
                            .allMatch(i -> "200%".equals(i.getText())),
                    "the menu marks the zoom in effect");
        });
        for (int i = 0; i < 6; i++) {
            fire("zoomIn");
        }
        assertEquals(PrintZoom.MAX, factor(), 1e-9, "the zoom stops at its largest step");
        assertTrue(FxTestSupport.callOnFx(() -> this.<Button>part("zoomIn").isDisabled()));
    }

    // --- page field (A6) ---

    private void typePage(String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.TextField field = part("pageField");
            field.requestFocus();
            field.setText(text);
            field.fireEvent(new javafx.event.ActionEvent());
        });
        settle();
    }

    private String pageFieldText() throws Exception {
        return FxTestSupport.callOnFx(
                () -> this.<javafx.scene.control.TextField>part("pageField").getText());
    }

    @Test
    void thePageFieldJumpsToThePageTyped() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(15));
        assertEquals("1", pageFieldText());
        assertEquals(
                tr("print.preview.pageAfter", 15),
                FxTestSupport.callOnFx(() -> this.<Label>part("pageAfter").getText()));
        typePage(" 12 ");
        assertEquals(page(12, 15), pageText());
        assertEquals("12", pageFieldText());
        assertSame(
                part("scroll"),
                FxTestSupport.callOnFx(() -> stage().getScene().getFocusOwner()),
                "after a jump the focus is back on the page, so the page keys work");
        press(KeyCode.PAGE_DOWN);
        assertEquals("13", pageFieldText(), "the field follows the page");
    }

    @Test
    void thePageFieldRejectsWhatIsNotAPage() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(15));
        typePage("7");
        for (String bad : List.of("0", "16", "999999999999", "-3", "abc", "", "2.5")) {
            typePage(bad);
            assertEquals(page(7, 15), pageText(), "'" + bad + "' is not a page: nothing moves");
            assertEquals("7", pageFieldText(), "the field shows the current page again after '" + bad + "'");
            assertEquals(tr("print.preview.pageInvalid", 15), notice());
        }
        assertTrue(FxTestSupport.callOnFx(() -> stage().isShowing()));
        typePage("8");
        assertEquals("", notice(), "a good page number takes the message away");
    }

    /** The key filter must leave a text field its keys: Left/Right/Home/End move the caret there. */
    @Test
    void thePageKeysDoNotTurnThePageWhileTypingInThePageField() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(5));
        FxTestSupport.runOnFx(
                () -> this.<javafx.scene.control.TextField>part("pageField").requestFocus());
        settle();
        for (KeyCode code : List.of(KeyCode.RIGHT, KeyCode.END, KeyCode.DOWN, KeyCode.PAGE_DOWN)) {
            press(code);
        }
        assertEquals(page(1, 5), pageText());
        // Escape in the field puts the number back and returns to the page; it does not close the window.
        FxTestSupport.runOnFx(
                () -> this.<javafx.scene.control.TextField>part("pageField").setText("4"));
        press(KeyCode.ESCAPE);
        settle();
        assertTrue(FxTestSupport.callOnFx(() -> stage().isShowing()), "Escape left the field, not the preview");
        assertEquals("1", pageFieldText());
        assertEquals(0, outcome.cancels);
    }

    /** At the minimum width the action buttons take a second row; nothing is cut down to "…". */
    @Test
    void theBarFitsAtTheMinimumWidth() throws Exception {
        open(new FakeJob(letter()), new FakePaginator(1189));
        FxTestSupport.runOnFx(() -> {
            javafx.scene.layout.HBox top = part("barTop");
            javafx.scene.layout.HBox actions = part("actions");
            assertSame(top, actions.getParent(), "at the default width the bar is one row");
            stage().setWidth(stage().getMinWidth());
            stage().setHeight(stage().getMinHeight());
        });
        settle();
        FxTestSupport.runOnFx(() -> {
            double width = stage().getScene().getWidth();
            javafx.scene.layout.HBox bottom = part("barBottom");
            javafx.scene.layout.HBox actions = part("actions");
            assertSame(bottom, actions.getParent(), "at the minimum width the actions wrap");
            for (String name : List.of("prev", "next", "zoomOut", "zoomIn", "setup", "print", "close", "pageField")) {
                javafx.scene.layout.Region control = part(name);
                assertTrue(
                        control.getWidth() >= Math.floor(control.prefWidth(-1)),
                        name + " has its full width: " + control.getWidth() + " of " + control.prefWidth(-1));
                Bounds inScene = control.localToScene(control.getBoundsInLocal());
                assertTrue(
                        inScene.getMinX() >= 0 && inScene.getMaxX() <= width + 0.5,
                        name + " is inside the window: " + inScene);
            }
        });
    }

    // --- the printer job is cancelled when abandoned, and only then (A18) ---

    @Test
    void closingThePreviewCancelsTheJob() throws Exception {
        FakeJob job = new FakeJob(letter());
        open(job, new FakePaginator(2));
        fire("close");
        assertEquals(1, job.cancelled, "Close abandons the job: it must be cancelled, not dropped");
        assertEquals(0, job.ended);
        FxTestSupport.runOnFx(() -> stage().close());
        assertEquals(1, job.cancelled, "never twice");
    }

    @Test
    void aFinishedPrintDoesNotCancelTheJob() throws Exception {
        FakeJob job = new FakeJob(letter());
        open(job, new FakePaginator(2));
        fire("print");
        assertEquals(1, job.ended);
        assertEquals(0, job.cancelled, "an ended job must not be cancelled");
        assertTrue(outcome.results.get(0).ok());
    }

    @Test
    void aFailureBeforeAnyPrintCancelsTheJob() throws Exception {
        FakeJob job = new FakeJob(letter());
        FakePaginator paginator = new FakePaginator(2);
        open(job, paginator);
        paginator.failure = new IllegalStateException("layout failed");
        job.layoutAfterPageSetup = layout(Paper.A4, PageOrientation.PORTRAIT, 54, 54, 54, 54);
        fire("setup");
        assertFalse(outcome.results.get(0).ok());
        assertEquals(1, job.cancelled, "the failed preview's job is cancelled");
    }

    // --- size and zoom are remembered for the session (A13) ---

    @Test
    void theWindowSizeAndZoomAreRememberedInTheMemoryItWasGiven() throws Exception {
        PrintPreview.Memory memory = new PrintPreview.Memory();
        PrintPreview first = FxTestSupport.callOnFx(() -> {
            PrintPreview p = new PrintPreview(
                    null, new FakeJob(letter()), new FakePaginator(2), r -> {}, () -> {}, () -> {}, memory);
            p.show();
            return p;
        });
        preview = first;
        settle();
        FxTestSupport.runOnFx(() -> {
            stage().setWidth(640);
            stage().setHeight(520);
            first.setZoom(PrintZoom.Setting.FIT_WIDTH);
        });
        settle();
        fire("close");
        assertEquals(640, memory.width, 0.5);
        assertEquals(520, memory.height, 0.5);
        assertEquals(PrintZoom.Setting.FIT_WIDTH, memory.zoom);

        preview = FxTestSupport.callOnFx(() -> {
            PrintPreview p = new PrintPreview(
                    null, new FakeJob(letter()), new FakePaginator(2), r -> {}, () -> {}, () -> {}, memory);
            p.show();
            return p;
        });
        settle();
        FxTestSupport.runOnFx(() -> {
            assertEquals(640, stage().getWidth(), 0.5, "the next preview opens at the remembered width");
            assertEquals(520, stage().getHeight(), 0.5);
            assertEquals(PrintZoom.Setting.FIT_WIDTH, preview.zoomSetting());
        });

        // A remembered size larger than the screen is still clamped to it.
        fire("close");
        memory.width = 100_000;
        memory.height = 100_000;
        preview = FxTestSupport.callOnFx(() -> {
            PrintPreview p = new PrintPreview(
                    null, new FakeJob(letter()), new FakePaginator(2), r -> {}, () -> {}, () -> {}, memory);
            p.show();
            return p;
        });
        settle();
        FxTestSupport.runOnFx(() -> {
            javafx.geometry.Rectangle2D screen =
                    javafx.stage.Screen.getPrimary().getVisualBounds();
            assertTrue(stage().getWidth() <= screen.getWidth(), "clamped to the screen: " + stage().getWidth());
            assertTrue(stage().getHeight() <= screen.getHeight());
        });
    }
}
