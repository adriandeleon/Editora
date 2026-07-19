package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.diff.PatchParser.FilePatch;
import com.editora.editor.TabContent;
import com.editora.github.PrReviewSummary;
import com.editora.github.PrReviewSummary.FileRow;
import com.editora.github.PrViewParser.PrDetail;

import static com.editora.i18n.Messages.tr;

/**
 * A GitHub "Files changed"-style review tab for a pull request — a {@link TabContent} built like
 * {@link WelcomePane} (a centered, scrolling column). It shows the PR meta (number/title/author, {@code head
 * → base}, state, {@code +adds −dels} roll-up) + Open-on-GitHub / Open-all / Refresh links above a list of
 * clickable file rows (status letter + {@link FileIcons} glyph colored via the shared {@code .git-status-*}
 * classes + path + per-file {@code +a −d}). Clicking a file opens its read-only diff.
 *
 * <p>Kept decoupled from the coordinator: all actions are injected callbacks and {@link #update} feeds it the
 * already-fetched {@link PrDetail} (nullable — a degraded header renders from just the number) + parsed
 * {@link FilePatch}es. A binary-only file yields no {@code FilePatch}, so it simply doesn't appear (a
 * binary-only PR shows the empty state).
 */
public final class PrReviewPane extends Region implements TabContent {

    /** Above this many rows the list is capped (Open-on-GitHub is the escape hatch) so a pathological PR
     *  can't build tens of thousands of nodes on the FX thread. */
    private static final int MAX_REVIEW_ROWS = 500;

    private static final double MARGIN = 48;
    private static final double CONTENT_WIDTH = 640;

    private final int prNumber;
    private final Consumer<FilePatch> onOpenFile;
    private final Runnable onOpenAll;
    private final Consumer<String> onOpenUrl;
    private final Runnable onRefresh;

    private PrDetail detail;
    private List<FilePatch> files = List.of();

    private final VBox content = new VBox();
    private final StackPane centerHost = new StackPane(content);
    private final ScrollPane scroll = new ScrollPane(centerHost);

    PrReviewPane(
            int prNumber,
            Consumer<FilePatch> onOpenFile,
            Runnable onOpenAll,
            Consumer<String> onOpenUrl,
            Runnable onRefresh) {
        this.prNumber = prNumber;
        this.onOpenFile = onOpenFile;
        this.onOpenAll = onOpenAll;
        this.onOpenUrl = onOpenUrl;
        this.onRefresh = onRefresh;

        getStyleClass().add("pr-review-pane");
        content.getStyleClass().add("pr-review-content");
        content.setSpacing(10);
        content.setAlignment(Pos.TOP_LEFT);
        content.setFillWidth(true);
        content.setPrefWidth(CONTENT_WIDTH);
        content.setMaxWidth(CONTENT_WIDTH);

        centerHost.setAlignment(Pos.TOP_CENTER);
        centerHost.setPadding(new Insets(MARGIN));
        centerHost.setMinWidth(CONTENT_WIDTH + 2 * MARGIN);

        scroll.getStyleClass().add("welcome-scroll");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);
        getChildren().add(scroll);
    }

    /** The current file list (so the injected "Open all" action reads fresh data after a Refresh). */
    List<FilePatch> files() {
        return files;
    }

    /** Feeds fresh data (first build + Refresh-in-place); {@code detail} may be null (degraded header). */
    void update(PrDetail detail, List<FilePatch> files) {
        this.detail = detail;
        this.files = files == null ? List.of() : files;
        rebuild();
    }

    private void rebuild() {
        List<FileRow> rows = PrReviewSummary.rows(files);
        content.getChildren().setAll(header(rows));
        if (files.isEmpty()) {
            content.getChildren().add(caption(tr("github.review.noFiles")));
            return;
        }
        content.getChildren().add(fileList(rows));
    }

    private Node header(List<FileRow> rows) {
        VBox box = new VBox(4);
        box.getStyleClass().add("pr-review-header");

        Label num = new Label("#" + prNumber);
        num.getStyleClass().add("pr-review-number");
        String titleText =
                detail != null && !detail.title().isBlank() ? detail.title() : tr("github.review.title", prNumber);
        Label title = new Label(titleText);
        title.getStyleClass().add("pr-review-title");
        title.setWrapText(true);
        HBox titleLine = new HBox(8, num, title);
        titleLine.setAlignment(Pos.BASELINE_LEFT);
        box.getChildren().add(titleLine);

        if (detail != null) {
            HBox meta = new HBox(10);
            meta.setAlignment(Pos.BASELINE_LEFT);
            if (!detail.authorLogin().isBlank()) {
                meta.getChildren().add(metaLabel(detail.authorLogin(), "pr-review-meta"));
            }
            if (!detail.headRefName().isBlank() || !detail.baseRefName().isBlank()) {
                meta.getChildren()
                        .add(metaLabel(detail.headRefName() + " → " + detail.baseRefName(), "pr-review-branch"));
            }
            if (!detail.state().isBlank()) {
                meta.getChildren().add(metaLabel(detail.state(), "pr-review-state"));
            }
            if (!meta.getChildren().isEmpty()) {
                box.getChildren().add(meta);
            }
        }

        box.getChildren().add(statsLine(rows));
        box.getChildren().add(actions());
        return box;
    }

    private Node statsLine(List<FileRow> rows) {
        int adds = detail != null ? detail.additions() : PrReviewSummary.totalAdditions(rows);
        int dels = detail != null ? detail.deletions() : PrReviewSummary.totalDeletions(rows);
        Label filesLabel = new Label(tr("github.review.filesChanged", files.size()));
        filesLabel.getStyleClass().add("pr-review-stats");
        HBox line = new HBox(10, filesLabel, adds(adds), dels(dels));
        line.setAlignment(Pos.BASELINE_LEFT);
        return line;
    }

    private Node actions() {
        HBox row = new HBox(14);
        row.getStyleClass().add("pr-review-actions");
        row.setAlignment(Pos.CENTER_LEFT);
        if (detail != null && !detail.url().isBlank()) {
            row.getChildren().add(link(tr("github.review.openOnGitHub"), () -> onOpenUrl.accept(detail.url())));
        }
        if (files.size() > 1) {
            row.getChildren().add(link(tr("github.review.openAll", files.size()), onOpenAll));
        }
        row.getChildren().add(link(tr("github.review.refresh"), onRefresh));
        return row;
    }

    private Node fileList(List<FileRow> rows) {
        VBox box = new VBox();
        box.getStyleClass().add("pr-review-files");
        int shown = Math.min(rows.size(), MAX_REVIEW_ROWS);
        for (int i = 0; i < shown; i++) {
            box.getChildren().add(fileRow(rows.get(i)));
        }
        if (rows.size() > MAX_REVIEW_ROWS) {
            box.getChildren().add(caption(tr("github.review.truncated", rows.size() - MAX_REVIEW_ROWS)));
        }
        return box;
    }

    private Node fileRow(FileRow r) {
        Label status = new Label(r.status().letter());
        status.getStyleClass().add("pr-review-file-status");

        StackPane iconHolder = new StackPane(FileIcons.forFileName(r.path()));
        iconHolder.setMinWidth(24);
        iconHolder.setPrefWidth(24);
        iconHolder.setMaxWidth(24);
        iconHolder.setAlignment(Pos.CENTER_LEFT);

        Hyperlink link = new Hyperlink(r.displayPath());
        link.getStyleClass().add("pr-review-file-link");
        link.setOnAction(e -> onOpenFile.accept(r.patch()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(6, status, iconHolder, link, spacer, adds(r.additions()), dels(r.deletions()));
        // The status class colors the letter + icon (the .pr-review-file-row.git-status-* CSS).
        row.getStyleClass().addAll("pr-review-file-row", r.status().cssClass());
        row.setAlignment(Pos.CENTER_LEFT);
        Tooltip.install(row, new Tooltip(r.displayPath()));
        row.setOnMouseClicked(e -> link.fire());
        return row;
    }

    private static Label adds(int n) {
        Label l = new Label("+" + n);
        l.getStyleClass().add("pr-review-adds");
        return l;
    }

    private static Label dels(int n) {
        Label l = new Label("−" + n);
        l.getStyleClass().add("pr-review-dels");
        return l;
    }

    private static Label metaLabel(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add(styleClass);
        return l;
    }

    private static Hyperlink link(String text, Runnable action) {
        Hyperlink h = new Hyperlink(text);
        h.getStyleClass().add("pr-review-action");
        h.setOnAction(e -> action.run());
        return h;
    }

    private static Label caption(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("welcome-caption");
        return l;
    }

    // --- TabContent ---

    @Override
    public Node node() {
        return this;
    }

    @Override
    public String title() {
        return tr("github.review.tab", prNumber);
    }

    @Override
    public Node icon() {
        return Icons.github();
    }

    /** Scales the tab's text to the editor text-zoom factor (em-relative CSS), like {@link WelcomePane}. */
    public void setFontScale(double zoom) {
        setStyle("-fx-font-size: " + Math.max(0.5, zoom) + "em;");
    }

    @Override
    protected void layoutChildren() {
        scroll.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
