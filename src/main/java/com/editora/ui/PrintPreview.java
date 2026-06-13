package com.editora.ui;

import static com.editora.i18n.Messages.tr;

import com.editora.print.PrintService;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Scale;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * A modal "Print Preview" window: shows the paginated output (scaled to fit, with page navigation)
 * before anything is sent to the printer. <b>Print…</b> then opens the native OS print dialog and,
 * on confirm, prints exactly these pages (re-paginated for the chosen page layout); <b>Close</b>
 * cancels. The pages come from a {@link PrintService.Paginator}, so the preview and the final print
 * use the same layout recipe.
 */
final class PrintPreview {

    private final Stage stage = new Stage();
    private final PrinterJob job;
    private final PrintService.Paginator paginator;
    private final PageLayout previewLayout;
    private final List<Node> pages;
    private final Consumer<PrintService.Result> onResult;
    private final Runnable onCancel;
    private final Runnable onPrinting;

    private final StackPane paperHolder = new StackPane();
    private final ScrollPane scroll = new ScrollPane(paperHolder);
    private final Label pageLabel = new Label();
    private final Button prev = new Button("◀");
    private final Button next = new Button("▶");
    private int index;

    PrintPreview(
            Window owner,
            PrinterJob job,
            PrintService.Paginator paginator,
            Consumer<PrintService.Result> onResult,
            Runnable onPrinting,
            Runnable onCancel) {
        this.job = job;
        this.paginator = paginator;
        this.onResult = onResult;
        this.onPrinting = onPrinting;
        this.onCancel = onCancel;
        this.previewLayout = job.getJobSettings().getPageLayout();
        this.pages = paginator.paginate(previewLayout);
        build(owner);
    }

    void show() {
        stage.show();
    }

    private void build(Window owner) {
        prev.setOnAction(e -> goTo(index - 1));
        next.setOnAction(e -> goTo(index + 1));
        Button print = new Button(tr("print.preview.print"));
        print.setDefaultButton(true);
        print.setOnAction(e -> doPrint());
        Button close = new Button(tr("print.preview.close"));
        close.setCancelButton(true);
        close.setOnAction(e -> cancel());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, prev, pageLabel, next, spacer, print, close);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8));
        bar.getStyleClass().add("print-preview-bar");

        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        paperHolder.setPadding(new Insets(16));
        paperHolder.setStyle("-fx-background-color: derive(-color-bg-default, -6%);");

        BorderPane root = new BorderPane();
        root.setCenter(scroll);
        root.setBottom(bar);

        Scene scene = new Scene(root, 760, 860);
        addStylesheet(scene, "/com/editora/styles/app.css");
        addStylesheet(scene, "/com/editora/styles/syntax.css");
        stage.setScene(scene);
        stage.setTitle(tr("print.preview.title"));
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setOnCloseRequest(e -> {
            e.consume();
            cancel();
        });

        goTo(0);
    }

    /** Shows the page at {@code i} on a white "paper" sheet scaled to fit the viewport width. */
    private void goTo(int i) {
        if (pages.isEmpty()) {
            pageLabel.setText(tr("print.preview.page", 0, 0));
            prev.setDisable(true);
            next.setDisable(true);
            return;
        }
        index = Math.max(0, Math.min(i, pages.size() - 1));
        double pw = previewLayout.getPrintableWidth();
        double ph = previewLayout.getPrintableHeight();

        StackPane paper = new StackPane(pages.get(index));
        StackPane.setAlignment(pages.get(index), Pos.TOP_LEFT);
        paper.setMinSize(pw, ph);
        paper.setPrefSize(pw, ph);
        paper.setMaxSize(pw, ph);
        paper.setStyle("-fx-background-color: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 3);");

        // Scale the page to fit the viewport width (capped at 100%), anchored top-left.
        Scale scale = new Scale(1, 1, 0, 0);
        scale.xProperty()
                .bind(Bindings.createDoubleBinding(
                        () -> {
                            double avail = scroll.getViewportBounds().getWidth() - 32;
                            return avail <= 0 || pw <= 0 ? 1 : Math.min(1.0, avail / pw);
                        },
                        scroll.viewportBoundsProperty()));
        scale.yProperty().bind(scale.xProperty());
        paper.getTransforms().add(scale);

        paperHolder.getChildren().setAll(new Group(paper));
        pageLabel.setText(tr("print.preview.page", index + 1, pages.size()));
        prev.setDisable(index == 0);
        next.setDisable(index == pages.size() - 1);
    }

    /** Opens the native print dialog; on confirm, prints freshly-paginated pages for the chosen layout. */
    private void doPrint() {
        if (!job.showPrintDialog(stage)) {
            return; // dialog cancelled — stay in the preview
        }
        onPrinting.run();
        PageLayout layout = job.getJobSettings().getPageLayout();
        PrintService.Result result;
        try {
            result = PrintService.printPages(paginator.paginate(layout), layout, job);
        } catch (RuntimeException ex) {
            result = new PrintService.Result(false, ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
        stage.close();
        onResult.accept(result);
    }

    private void cancel() {
        stage.close();
        onCancel.run();
    }

    private static void addStylesheet(Scene scene, String resource) {
        java.net.URL url = PrintPreview.class.getResource(resource);
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }
}
