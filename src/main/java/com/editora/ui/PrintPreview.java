package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Dimension2D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.PageRange;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.print.PrintService;

import static com.editora.i18n.Messages.tr;

/**
 * A window-modal "Print Preview": shows the paginated output on a sheet of the chosen paper (margins
 * included, with page navigation and a zoom: Fit Page, Fit Width or a percentage — see {@link PrintZoom})
 * before anything is sent to the printer. <b>Page
 * Setup…</b> opens the native page dialog and re-paginates for the new paper, orientation and margins.
 * <b>Print…</b> opens the native print dialog and, on confirm, prints <em>the pages that were
 * previewed</em> — the ones inside the dialog's page range. If the dialog changed the layout (another
 * paper, orientation or printer), nothing is printed: the preview is rebuilt for the new layout and the
 * user presses Print… again once they have seen it. <b>Close</b> cancels.
 *
 * <p>The pages come from a {@link PrintService.Paginator} and are paginated once per layout; the print
 * reuses the previewed {@link PrintService.Pages}.
 */
final class PrintPreview {

    /**
     * The printer-job operations the preview needs — a {@link PrinterJob} in the app ({@link #of}), a fake
     * in tests, which must never drive a real job.
     */
    interface Job extends PrintService.PageSink {
        /** The layout currently set on the job (the printer's default until a dialog changes it). */
        PageLayout layout();

        /** The job's printer, or an empty string when it has none. */
        String printerName();

        /** Shows the native page-setup dialog; {@code true} when confirmed (the layout may have changed). */
        boolean showPageSetup(Window owner);

        /** Shows the native print dialog; {@code true} when the user chose to print. */
        boolean showPrintDialog(Window owner);

        /** The page ranges chosen in the print dialog (1-based, inclusive); null or empty for all pages. */
        PageRange[] pageRanges();

        /** Abandons the job. Never called on a job that was ended ({@link #endJob}) or already cancelled. */
        void cancel();

        static Job of(PrinterJob job) {
            return new Job() {
                @Override
                public PageLayout layout() {
                    return job.getJobSettings().getPageLayout();
                }

                @Override
                public String printerName() {
                    return job.getPrinter() == null ? "" : job.getPrinter().getName();
                }

                @Override
                public boolean showPageSetup(Window owner) {
                    return job.showPageSetupDialog(owner);
                }

                @Override
                public boolean showPrintDialog(Window owner) {
                    return job.showPrintDialog(owner);
                }

                @Override
                public PageRange[] pageRanges() {
                    return job.getJobSettings().getPageRanges();
                }

                @Override
                public boolean printPage(PageLayout layout, Node page) {
                    return job.printPage(layout, page);
                }

                @Override
                public boolean endJob() {
                    return job.endJob();
                }

                @Override
                public void cancel() {
                    job.cancelJob();
                }
            };
        }
    }

    private static final double WIDTH = 760;
    private static final double HEIGHT = 860;
    private static final double MIN_WIDTH = 520;
    private static final double MIN_HEIGHT = 400;
    /** Grey margin around the sheet, in px. */
    private static final double SHEET_GAP = 16;
    /** Room the sheet's drop shadow takes on each side, in px. */
    private static final double SHEET_SHADOW = 16;
    /** Distance one Up/Down press scrolls the sheet, in px. */
    private static final double KEY_SCROLL = 60;
    /** Space between the groups of the bar and around it, in px. */
    private static final double BAR_GAP = 8;

    /**
     * What a main window remembers of its Print Preview between openings — the window's size and the zoom —
     * for the session only (nothing is written to the settings). The size is still clamped to the screen
     * each time the window opens.
     */
    static final class Memory {
        double width = WIDTH;
        double height = HEIGHT;
        PrintZoom.Setting zoom = PrintZoom.Setting.FIT_PAGE;
    }

    private final Stage stage = new Stage();
    private final Job job;
    private final PrintService.Paginator paginator;
    private final Consumer<PrintService.Result> onResult;
    private final Runnable onCancel;
    private final Runnable onPrinting;
    private final Memory memory;
    /** The job was ended or cancelled: it must not be cancelled (again). */
    private boolean jobFinished;

    /** The layout the pages on screen were paginated for. */
    private PageLayout layout;

    private PrintService.Pages pages;
    private int index;
    /** A print is running: navigation and the dialogs are off and Close asks to cancel it. */
    private boolean printing;

    private boolean cancelRequested;
    /** The window has reported its outcome (result or cancel); nothing may be reported twice. */
    private boolean done;

    private final StackPane sheet = new StackPane();
    private final Scale zoom = new Scale(1, 1, 0, 0);
    private PrintZoom.Setting zoomSetting;
    private final StackPane paperHolder = new StackPane(new Group(sheet));
    private final ScrollPane scroll = new ScrollPane(paperHolder);
    /** "Page [ 3 ] of 15": the number is a field that jumps to the page typed into it. */
    private final Label pageBefore = new Label(tr("print.preview.pageBefore"));

    private final TextField pageField = new TextField();
    private final Label pageAfter = new Label();
    private final Button zoomOut = new Button("−");
    private final MenuButton zoomMenu = new MenuButton();
    private final Button zoomIn = new Button("+");
    private final ToggleGroup zoomChoices = new ToggleGroup();
    /** The bar's three groups — page, zoom, actions — and its two rows (the second only when narrow). */
    private final HBox pager = new HBox(4);

    private final HBox zooms = new HBox(2);
    private final HBox actions = new HBox(BAR_GAP);
    private final HBox barTop = new HBox(BAR_GAP);
    private final HBox barBottom = new HBox(BAR_GAP);
    private final Label infoLabel = new Label();
    private final Label notice = new Label();
    /** The "no such page" text while it is what {@link #notice} shows; the next page turn takes it away. */
    private String pageRangeNotice;

    private final Button prev = new Button("◀");
    private final Button next = new Button("▶");
    private final Button setup = new Button(tr("print.preview.pageSetup"));
    private final Button print = new Button(tr("print.preview.print"));
    private final Button close = new Button(tr("print.preview.close"));

    /**
     * Paginates for the job's current layout and builds the window. A pagination failure is thrown to the
     * caller (nothing is on screen yet); a later one closes the window and is reported through
     * {@code onResult}.
     */
    PrintPreview(
            Window owner,
            Job job,
            PrintService.Paginator paginator,
            Consumer<PrintService.Result> onResult,
            Runnable onPrinting,
            Runnable onCancel) {
        this(owner, job, paginator, onResult, onPrinting, onCancel, new Memory());
    }

    /** As above, opening at the size and zoom in {@code memory} and leaving the last ones there on close. */
    PrintPreview(
            Window owner,
            Job job,
            PrintService.Paginator paginator,
            Consumer<PrintService.Result> onResult,
            Runnable onPrinting,
            Runnable onCancel,
            Memory memory) {
        this.job = job;
        this.memory = memory;
        this.zoomSetting = memory.zoom == null ? PrintZoom.Setting.FIT_PAGE : memory.zoom;
        this.paginator = paginator;
        this.onResult = onResult;
        this.onPrinting = onPrinting;
        this.onCancel = onCancel;
        this.layout = job.layout();
        this.pages = paginator.pages(layout);
        build(owner);
    }

    void show() {
        stage.show();
    }

    /** Brings the open preview forward (a second print request while it is open lands here). */
    void toFront() {
        stage.toFront();
        stage.requestFocus();
    }

    private void build(Window owner) {
        prev.setOnAction(e -> navigate(index - 1));
        next.setOnAction(e -> navigate(index + 1));
        describe(prev, tr("print.preview.previous"));
        describe(next, tr("print.preview.next"));
        setup.setOnAction(e -> pageSetup());
        // Print… stays the default button: Enter opens the system print dialog, which is itself the
        // confirmation — nothing is printed by Enter alone. Initial focus goes to the page (see below),
        // not to a button, so the keys that turn pages work at once.
        print.setDefaultButton(true);
        print.setOnAction(e -> doPrint());
        close.setCancelButton(true);
        close.setOnAction(e -> cancel());
        for (Button b : List.of(prev, next, zoomOut, zoomIn, setup, print, close)) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        buildPageField();
        buildZoomControls();

        infoLabel.setMinWidth(0);
        infoLabel.setMaxWidth(Double.MAX_VALUE);
        infoLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        infoLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        HBox.setHgrow(infoLabel, Priority.ALWAYS);
        HBox.setMargin(infoLabel, new Insets(0, 8, 0, 8));
        // Related controls sit closer together than the groups do. When the window narrows, the
        // printer/paper text gives way first (it elides); when even that is not enough, the three action
        // buttons move to a row of their own (see arrangeBar) — no control is ever squeezed to "…".
        pager.getChildren().setAll(prev, pageBefore, pageField, pageAfter, next);
        zooms.getChildren().setAll(zoomOut, zoomMenu, zoomIn);
        actions.getChildren().setAll(setup, print, close);
        for (HBox group : List.of(pager, zooms, actions)) {
            group.setAlignment(Pos.CENTER_LEFT);
            group.setMinWidth(Region.USE_PREF_SIZE);
        }
        barTop.getChildren().setAll(pager, zooms, infoLabel, actions);
        barTop.setAlignment(Pos.CENTER_LEFT);
        barBottom.setAlignment(Pos.CENTER_RIGHT);
        barBottom.setVisible(false);
        barBottom.setManaged(false);
        VBox bar = new VBox(BAR_GAP, barTop, barBottom);
        bar.setPadding(new Insets(BAR_GAP));
        bar.getStyleClass().add("print-preview-bar");

        notice.setWrapText(true);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.setPadding(new Insets(8, 12, 8, 12));
        notice.setStyle("-fx-background-color: -color-accent-subtle;");
        notice.managedProperty().bind(notice.visibleProperty());
        notice.visibleProperty().bind(notice.textProperty().isNotEmpty());

        sheet.setAlignment(Pos.TOP_LEFT);
        sheet.setStyle("-fx-background-color: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 3);");
        // The sheet is scaled by the zoom setting, anchored top-left.
        sheet.getTransforms().add(zoom);
        scroll.viewportBoundsProperty().addListener((obs, old, bounds) -> fitSheet());
        scroll.addEventFilter(ScrollEvent.SCROLL, this::onWheel);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setFocusTraversable(true);
        paperHolder.setPadding(new Insets(SHEET_GAP));
        paperHolder.setStyle("-fx-background-color: derive(-color-bg-default, -6%);");

        BorderPane root = new BorderPane();
        root.setCenter(scroll);
        root.setBottom(new VBox(notice, bar));
        // The window's width, not the bar's: the bar never gets narrower than its one-row minimum.
        root.widthProperty().addListener((obs, old, width) -> arrangeBar(width.doubleValue()));

        Scene scene = new Scene(root);
        addStylesheet(scene, "/com/editora/styles/app.css");
        addStylesheet(scene, "/com/editora/styles/syntax.css");
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.setScene(scene);
        stage.setTitle(tr("print.preview.title"));
        stage.initOwner(owner);
        // Blocks only the window that is printing; other Editora windows stay usable.
        stage.initModality(Modality.WINDOW_MODAL);
        // The size this main window's preview had last time, still limited to the screen it opens on now.
        Dimension2D size = WindowPlacement.clampSize(memory.width, memory.height, WindowPlacement.screenOf(owner), 0.9);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setWidth(Math.max(MIN_WIDTH, size.getWidth()));
        stage.setHeight(Math.max(MIN_HEIGHT, size.getHeight()));
        WindowPlacement.centerOnOwner(stage, owner, stage.getWidth(), stage.getHeight());
        stage.setOnCloseRequest(e -> {
            e.consume();
            cancel();
        });
        // Focus starts on the page, not on a button: Page Up/Down work at once and Space does nothing.
        stage.setOnShown(e -> scroll.requestFocus());

        applyLayout();
        setZoom(zoomSetting);
        goTo(0);
        scroll.requestFocus();
    }

    /**
     * Puts the action buttons beside the page and zoom controls when {@code width} has room for all three
     * groups, and on a second row when it has not (the window's minimum width, a long translation, a
     * four-digit page count).
     */
    private void arrangeBar(double width) {
        double oneRow = pager.prefWidth(-1) + zooms.prefWidth(-1) + actions.prefWidth(-1) + 7 * BAR_GAP;
        boolean wrap = width > 0 && width < oneRow;
        if (wrap == (actions.getParent() == barBottom)) {
            return;
        }
        if (wrap) {
            barTop.getChildren().remove(actions);
            barBottom.getChildren().setAll(actions);
        } else {
            barBottom.getChildren().clear();
            barTop.getChildren().add(actions);
        }
        barBottom.setVisible(wrap);
        barBottom.setManaged(wrap);
    }

    /** The page number as a field: Enter jumps to the page typed, Escape and leaving the field put it back. */
    private void buildPageField() {
        pageBefore.setMinWidth(Region.USE_PREF_SIZE);
        pageAfter.setMinWidth(Region.USE_PREF_SIZE);
        pageField.setAlignment(Pos.CENTER_RIGHT);
        pageField.setMinWidth(Region.USE_PREF_SIZE);
        pageField.setOnAction(e -> jumpToTypedPage());
        pageField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) { // Escape leaves the field; it must not also close the window
                showPageNumber();
                scroll.requestFocus();
                e.consume();
            }
        });
        pageField.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                Platform.runLater(pageField::selectAll); // typing replaces the number
            } else {
                showPageNumber();
            }
        });
    }

    /** − / + step through the percentages; the button between them shows the scale and opens the choices. */
    private void buildZoomControls() {
        zoomOut.setOnAction(e -> zoomOut());
        zoomIn.setOnAction(e -> zoomIn());
        describe(zoomOut, tr("print.preview.zoomOut"));
        describe(zoomIn, tr("print.preview.zoomIn"));
        zoomMenu.setAccessibleText(tr("print.preview.zoom"));
        zoomMenu.setTooltip(new Tooltip(tr("print.preview.zoom")));
        zoomMenu.setMinWidth(Region.USE_PREF_SIZE);
        zoomMenu.getItems().add(zoomChoice(tr("print.preview.fitPage"), PrintZoom.Setting.FIT_PAGE));
        zoomMenu.getItems().add(zoomChoice(tr("print.preview.fitWidth"), PrintZoom.Setting.FIT_WIDTH));
        zoomMenu.getItems().add(new SeparatorMenuItem());
        for (double step : PrintZoom.steps()) {
            zoomMenu.getItems().add(zoomChoice(PrintZoom.percentOf(step) + "%", PrintZoom.Setting.percent(step)));
        }
    }

    private RadioMenuItem zoomChoice(String text, PrintZoom.Setting setting) {
        RadioMenuItem item = new RadioMenuItem(text);
        item.setToggleGroup(zoomChoices);
        item.setUserData(setting);
        item.setOnAction(e -> setZoom(setting));
        return item;
    }

    /** The zoom in effect. */
    PrintZoom.Setting zoomSetting() {
        return zoomSetting;
    }

    /** The scale the sheet is drawn at right now (1.0 = 100%). */
    double zoomFactor() {
        return zoom.getX();
    }

    /**
     * Applies {@code setting}. The scroll bars follow the mode, so that a fit cannot chase its own scroll
     * bar (a bar appearing narrows the viewport, which re-fits, which removes the bar…): Fit Page needs
     * none, Fit Width always keeps the vertical one, and a percentage shows whichever the sheet needs.
     */
    void setZoom(PrintZoom.Setting setting) {
        zoomSetting = setting;
        switch (setting.mode()) {
            case FIT_PAGE -> {
                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            }
            case FIT_WIDTH -> {
                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
            }
            case PERCENT -> {
                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            }
        }
        for (var choice : zoomChoices.getToggles()) {
            if (setting.equals(choice.getUserData())) {
                zoomChoices.selectToggle(choice);
            }
        }
        if (zoomChoices.getSelectedToggle() != null
                && !setting.equals(zoomChoices.getSelectedToggle().getUserData())) {
            zoomChoices.selectToggle(null);
        }
        fitSheet();
    }

    private void zoomIn() {
        setZoom(PrintZoom.Setting.percent(PrintZoom.stepUp(zoom.getX())));
    }

    private void zoomOut() {
        setZoom(PrintZoom.Setting.percent(PrintZoom.stepDown(zoom.getX())));
    }

    /** Ctrl+wheel zooms; a plain wheel scrolls the sheet as before. */
    private void onWheel(ScrollEvent e) {
        if (!(e.isShortcutDown() || e.isControlDown()) || e.getDeltaY() == 0) {
            return;
        }
        if (e.getDeltaY() > 0) {
            zoomIn();
        } else {
            zoomOut();
        }
        e.consume();
    }

    private static void describe(Button button, String text) {
        button.setAccessibleText(text);
        button.setTooltip(new Tooltip(text));
    }

    /** The paper size of {@code layout} as it is read — width and height swapped for landscape. */
    static Dimension2D sheetSize(PageLayout layout) {
        double w = layout.getPaper().getWidth();
        double h = layout.getPaper().getHeight();
        return landscape(layout) ? new Dimension2D(h, w) : new Dimension2D(w, h);
    }

    private static boolean landscape(PageLayout layout) {
        PageOrientation o = layout.getPageOrientation();
        return o == PageOrientation.LANDSCAPE || o == PageOrientation.REVERSE_LANDSCAPE;
    }

    /**
     * Whether two layouts paginate the same: same paper size, orientation and margins. Compared by value
     * and with a tolerance — a job hands out a fresh {@code PageLayout} after each dialog, and the
     * platform rounds margins.
     */
    static boolean sameLayout(PageLayout a, PageLayout b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getPageOrientation() == b.getPageOrientation()
                && near(a.getPaper().getWidth(), b.getPaper().getWidth())
                && near(a.getPaper().getHeight(), b.getPaper().getHeight())
                && near(a.getLeftMargin(), b.getLeftMargin())
                && near(a.getRightMargin(), b.getRightMargin())
                && near(a.getTopMargin(), b.getTopMargin())
                && near(a.getBottomMargin(), b.getBottomMargin());
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.5; // half a point
    }

    /**
     * Sizes the sheet to the paper of the current layout, with the layout's margins as padding so the
     * page node sits where it will on paper, and names the printer, paper and orientation in the bar.
     */
    private void applyLayout() {
        Dimension2D size = sheetSize(layout);
        sheet.setPadding(new Insets(
                layout.getTopMargin(), layout.getRightMargin(), layout.getBottomMargin(), layout.getLeftMargin()));
        sheet.setMinSize(size.getWidth(), size.getHeight());
        sheet.setPrefSize(size.getWidth(), size.getHeight());
        sheet.setMaxSize(size.getWidth(), size.getHeight());
        fitSheet();
        String orientation = tr(landscape(layout) ? "print.preview.landscape" : "print.preview.portrait");
        String printer = job.printerName();
        String paper = layout.getPaper().getName();
        infoLabel.setText((printer == null || printer.isBlank() ? "" : printer + " · ") + paper + " · " + orientation);
        infoLabel.setTooltip(new Tooltip(infoLabel.getText()));
    }

    /** Scales the sheet for the zoom setting and the viewport, and shows the resulting percentage. */
    private void fitSheet() {
        // The sheet's drop shadow counts towards its bounds: leave room for it, or a sheet scaled to fit
        // still overflows the viewport by the shadow.
        Bounds view = scroll.getViewportBounds();
        double factor = PrintZoom.factor(
                zoomSetting,
                sheet.getPrefWidth(),
                sheet.getPrefHeight(),
                view.getWidth(),
                view.getHeight(),
                SHEET_GAP + SHEET_SHADOW);
        zoom.setX(factor);
        zoom.setY(factor);
        zoomMenu.setText(PrintZoom.percentOf(factor) + "%");
        zoomOut.setDisable(factor <= PrintZoom.MIN + 0.005);
        zoomIn.setDisable(factor >= PrintZoom.MAX - 0.005);
    }

    /** "Page 3 of 15" — what the page controls say together (their accessible text; read by tests). */
    String pageText() {
        int count = pages.count();
        return tr("print.preview.page", count == 0 ? 0 : index + 1, count);
    }

    /** Puts the current page number (back) into the field and sizes the field for the page count. */
    private void showPageNumber() {
        int count = pages.count();
        pageField.setText(Integer.toString(count == 0 ? 0 : index + 1));
        pageField.setPrefColumnCount(Math.max(2, Integer.toString(count).length()));
        pageAfter.setText(tr("print.preview.pageAfter", count));
        pageField.setAccessibleText(pageText());
        pageField.setTooltip(new Tooltip(tr("print.preview.pageField", Math.min(1, count), count)));
        pageField.setDisable(count == 0 || printing);
        if (stage.getScene() != null) {
            arrangeBar(stage.getScene().getWidth()); // more digits may no longer fit beside the buttons
        }
    }

    /**
     * Enter in the page field: goes to the page typed. Anything that is not a page of this document — text,
     * zero, a number past the end — changes nothing: the field shows the current page again, selected for
     * another try, and the notice says which numbers exist.
     */
    private void jumpToTypedPage() {
        int target = PrintZoom.pageIndex(pageField.getText(), pages.count());
        if (target < 0) {
            showPageNumber();
            pageField.selectAll();
            pageRangeNotice = tr("print.preview.pageInvalid", pages.count());
            notice.setText(pageRangeNotice);
            return;
        }
        navigate(target);
        scroll.requestFocus(); // back to the page, so the page keys work again
    }

    /** {@link #goTo} for a user action: a page that fails to build ends the preview with a reported error. */
    private void navigate(int i) {
        try {
            goTo(i);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Shows the page at {@code i} (clamped) on the sheet, scrolled to its top. */
    private void goTo(int i) {
        int count = pages.count();
        if (count == 0) {
            sheet.getChildren().clear();
            index = 0;
            showPageNumber();
            prev.setDisable(true);
            next.setDisable(true);
            return;
        }
        index = Math.max(0, Math.min(i, count - 1));
        Node page = pages.get(index);
        StackPane.setAlignment(page, Pos.TOP_LEFT);
        sheet.getChildren().setAll(page);
        showPageNumber();
        if (pageRangeNotice != null && pageRangeNotice.equals(notice.getText())) {
            notice.setText("");
        }
        pageRangeNotice = null;
        prev.setDisable(index == 0);
        next.setDisable(index == count - 1);
        // A new page starts at its top, not wherever the last one was scrolled to.
        scroll.setVvalue(scroll.getVmin());
        scroll.setHvalue(scroll.getHmin());
    }

    /**
     * Page keys, wherever the focus is: Page Up/Down and Left/Right turn the page, Home/End go to the
     * first/last, Up/Down scroll the sheet and turn the page at its edge. A text field keeps its keys.
     * Ctrl with + / − zooms in and out and Ctrl+0 fits the page.
     */
    private void onKey(KeyEvent e) {
        if ((e.isShortcutDown() || e.isControlDown()) && !e.isAltDown() && zoomKey(e.getCode())) {
            e.consume();
            return;
        }
        if (printing
                || e.isShortcutDown()
                || e.isControlDown()
                || e.isAltDown()
                || e.isMetaDown()
                || e.getTarget() instanceof TextInputControl) {
            return;
        }
        switch (e.getCode()) {
            case PAGE_DOWN, RIGHT -> navigate(index + 1);
            case PAGE_UP, LEFT -> navigate(index - 1);
            case HOME -> navigate(0);
            case END -> navigate(pages.count() - 1);
            case DOWN -> scrollOrTurn(1);
            case UP -> scrollOrTurn(-1);
            default -> {
                return;
            }
        }
        e.consume();
    }

    /** Handles the key of a Ctrl zoom chord; false when {@code code} is not one. */
    private boolean zoomKey(KeyCode code) {
        switch (code) {
            case PLUS, EQUALS, ADD -> zoomIn();
            case MINUS, SUBTRACT -> zoomOut();
            case DIGIT0, NUMPAD0 -> setZoom(PrintZoom.Setting.FIT_PAGE);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void scrollOrTurn(int direction) {
        double hidden = paperHolder.getHeight() - scroll.getViewportBounds().getHeight();
        double v = scroll.getVvalue();
        boolean atEdge = hidden <= 0.5 || (direction > 0 ? v >= scroll.getVmax() - 1e-6 : v <= scroll.getVmin() + 1e-6);
        if (atEdge) {
            navigate(index + direction);
            return;
        }
        double step = KEY_SCROLL / hidden * (scroll.getVmax() - scroll.getVmin());
        scroll.setVvalue(Math.max(scroll.getVmin(), Math.min(scroll.getVmax(), v + direction * step)));
    }

    /** Opens the native page-setup dialog and re-paginates the preview for the layout it leaves. */
    private void pageSetup() {
        if (printing || done) {
            return;
        }
        try {
            if (job.showPageSetup(stage)) {
                notice.setText("");
                adopt(job.layout());
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Makes {@code chosen} the previewed layout. Pages are rebuilt only when it differs from the one on
     * screen — the same layout never paginates twice. Returns whether it differed.
     */
    private boolean adopt(PageLayout chosen) {
        if (sameLayout(chosen, layout)) {
            applyLayout(); // the printer may have changed even though the layout did not
            return false;
        }
        pages = PrintService.Pages.of(List.of()); // let go of the old pages before building the new ones
        sheet.getChildren().clear();
        layout = chosen;
        pages = paginator.pages(chosen);
        applyLayout();
        goTo(index);
        return true;
    }

    /**
     * Opens the native print dialog. On confirm: when the dialog left the layout as previewed, prints the
     * previewed pages inside the chosen page range; when it changed the layout, shows the new pagination
     * and waits for another Print… — the user must not get pages they never saw.
     */
    private void doPrint() {
        if (printing || done) {
            return;
        }
        try {
            if (!job.showPrintDialog(stage)) {
                return; // dialog cancelled — stay in the preview
            }
            if (adopt(job.layout())) {
                notice.setText(tr("print.preview.layoutChanged"));
                return;
            }
            int[] indices = PrintService.pageIndices(job.pageRanges(), pages.count());
            if (indices.length == 0) {
                notice.setText(tr("print.preview.rangeEmpty", pages.count()));
                return;
            }
            startPrinting(indices);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Prints one page per {@code Platform.runLater} turn, so the window repaints and Cancel is heard
     * between pages. ({@code printPage} must run on the FX thread and not inside a pulse or an animation,
     * which rules out a timer; it runs a nested event loop while the page renders, so the buttons are
     * live during it too — hence everything but Cancel is disabled.)
     */
    private void startPrinting(int[] indices) {
        printing = true;
        cancelRequested = false;
        for (Button b : List.of(prev, next, setup, print)) {
            b.setDisable(true);
        }
        pageField.setDisable(true);
        close.setText(tr("dialog.cancel"));
        onPrinting.run();
        printStep(indices, 0);
    }

    private void printStep(int[] indices, int at) {
        if (done) {
            return;
        }
        try {
            if (cancelRequested) {
                abandonJob();
                closeWith(onCancel);
                return;
            }
            if (at == indices.length) {
                jobFinished = true;
                boolean ended = job.endJob();
                finish(ended ? new PrintService.Result(true, "") : failed());
                return;
            }
            notice.setText(tr("print.preview.printingPage", at + 1, indices.length));
            if (!job.printPage(layout, detached(pages.get(indices[at])))) {
                jobFinished = true;
                job.endJob();
                finish(failed());
                return;
            }
            Platform.runLater(() -> printStep(indices, at + 1));
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static PrintService.Result failed() {
        return new PrintService.Result(false, "print job failed");
    }

    /**
     * {@code page} as a free-standing node at the origin. The page on screen sits inside the scaled sheet;
     * a printed node is drawn with its own position but not its parent's, so it is taken off the sheet.
     */
    private static Node detached(Node page) {
        if (page.getParent() instanceof Pane parent) {
            parent.getChildren().remove(page);
        }
        page.relocate(0, 0);
        return page;
    }

    /** Ends the preview with an error: closes the window and reports it, so no status is left hanging. */
    private void fail(Throwable t) {
        pages = PrintService.Pages.of(List.of()); // an OutOfMemoryError needs the pages gone to report at all
        sheet.getChildren().clear();
        abandonJob();
        finish(new PrintService.Result(false, t.getMessage() == null ? t.toString() : t.getMessage()));
    }

    /**
     * Cancels the printer job unless it was ended or cancelled already. Every way out of the preview that
     * does not finish a print comes through here — Close, a cancelled print, a failure — so the job the
     * window was opened with is never just dropped.
     */
    private void abandonJob() {
        if (jobFinished) {
            return;
        }
        jobFinished = true;
        try {
            job.cancel();
        } catch (Throwable ignored) {
            // nothing more can be done with a job that will not even cancel
        }
    }

    private void finish(PrintService.Result result) {
        closeWith(() -> onResult.accept(result));
    }

    /** Closes the window and reports its one outcome. */
    private void closeWith(Runnable report) {
        if (done) {
            return;
        }
        done = true;
        printing = false;
        remember();
        stage.close();
        report.run();
    }

    /** Leaves the window's size and zoom for the next preview of the same main window. */
    private void remember() {
        memory.zoom = zoomSetting;
        if (stage.isMaximized() || stage.isFullScreen() || stage.isIconified()) {
            return; // not a size to open the next preview at
        }
        if (stage.getWidth() >= MIN_WIDTH && stage.getHeight() >= MIN_HEIGHT) {
            memory.width = stage.getWidth();
            memory.height = stage.getHeight();
        }
    }

    /** Close / Escape / the window's close button: leaves the preview, or asks a running print to stop. */
    private void cancel() {
        if (printing) {
            cancelRequested = true;
            close.setDisable(true);
            return;
        }
        abandonJob();
        closeWith(onCancel);
    }

    private static void addStylesheet(Scene scene, String resource) {
        java.net.URL url = PrintPreview.class.getResource(resource);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }
}
