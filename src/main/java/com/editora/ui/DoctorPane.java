package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorStatus;
import com.editora.doctor.DoctorSummary;
import com.editora.editor.TabContent;

import static com.editora.i18n.Messages.tr;

/**
 * The Doctor screen — a full-tab health report of every external CLI/tool Editora's features rely on,
 * shown like the Welcome page (a real {@link TabContent} tab; same centered, scrollable layout). Rows are
 * grouped by feature section and fill in live as {@code DoctorCoordinator}'s probes land; each non-OK row
 * carries a tip and, where possible, an Install… / Settings… action. Row updates are coalesced to one
 * rebuild per FX pulse (the {@code scheduleBraceMatch} idiom) so a burst of probe results doesn't rebuild
 * the scene repeatedly.
 */
final class DoctorPane extends Region implements TabContent {

    /** Row actions, implemented by {@code DoctorCoordinator}. */
    interface Actions {
        void refresh();

        void install(DoctorCheck check);

        void openSettings(String settingsKey);
    }

    /** Natural content width — wide enough for name + command + version + action buttons per row. */
    private static final double CONTENT_WIDTH = 760;

    /** Outer margin around the centered content (mirrors {@code WelcomePane}). */
    private static final double MARGIN = 48;

    private final Actions actions;

    /** Row id → latest check, in catalog order (insertion order drives section grouping). */
    private final LinkedHashMap<String, DoctorCheck> checks = new LinkedHashMap<>();

    private final VBox content = new VBox();
    private final StackPane centerHost = new StackPane(content);
    private final ScrollPane scroll = new ScrollPane(centerHost);

    private boolean rebuildPending;

    DoctorPane(Actions actions) {
        this.actions = actions;
        getStyleClass().add("doctor-pane");
        content.getStyleClass().add("doctor-content");
        content.setAlignment(Pos.TOP_LEFT);
        content.setFillWidth(true);
        content.setMinWidth(CONTENT_WIDTH);
        content.setPrefWidth(CONTENT_WIDTH);
        content.setMaxWidth(CONTENT_WIDTH);

        centerHost.getStyleClass().add("doctor-scroll-content");
        centerHost.setAlignment(Pos.TOP_CENTER);
        centerHost.setPadding(new Insets(MARGIN));
        centerHost.setMinWidth(CONTENT_WIDTH + 2 * MARGIN);

        scroll.getStyleClass().add("doctor-scroll");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);
        getChildren().add(scroll);
        rebuild();
    }

    // --- TabContent ---

    @Override
    public Node node() {
        return this;
    }

    @Override
    public String title() {
        return tr("doctor.tab");
    }

    @Override
    public Node icon() {
        return Icons.doctor();
    }

    /** Scales the page text to the editor text-zoom factor (the {@code WelcomePane.setFontScale} pattern). */
    void setFontScale(double zoom) {
        setStyle("-fx-font-size: " + Math.max(0.5, zoom) + "em;");
    }

    // --- content ---

    /** Replaces the whole check list (a fresh run's placeholders) and rebuilds immediately. */
    void setChecks(List<DoctorCheck> list) {
        checks.clear();
        for (DoctorCheck c : list) {
            checks.put(c.id(), c);
        }
        rebuild();
    }

    /** Updates one row in place (a probe result landed); rebuilds coalesced to one per pulse. */
    void updateCheck(DoctorCheck check) {
        checks.put(check.id(), check);
        scheduleRebuild();
    }

    /** Snapshot of the current rows, in display order (test seam). */
    List<DoctorCheck> currentChecks() {
        return List.copyOf(checks.values());
    }

    private void scheduleRebuild() {
        if (rebuildPending) {
            return;
        }
        rebuildPending = true;
        Platform.runLater(() -> {
            rebuildPending = false;
            rebuild();
        });
    }

    private void rebuild() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(header());
        String section = null;
        for (DoctorCheck c : checks.values()) {
            if (!c.sectionKey().equals(section)) {
                section = c.sectionKey();
                Label label = new Label(tr("doctor.section." + section));
                label.getStyleClass().add("doctor-section");
                nodes.add(label);
            }
            nodes.add(row(c));
        }
        content.getChildren().setAll(nodes);
    }

    private Node header() {
        Label title = new Label(tr("doctor.title"));
        title.getStyleClass().add("doctor-title");
        Label caption = new Label(tr("doctor.caption"));
        caption.getStyleClass().add("doctor-caption");

        Label summary = new Label(summaryText());
        summary.getStyleClass().add("doctor-summary");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button(tr("doctor.action.refresh"), Icons.refresh());
        refresh.getStyleClass().add("doctor-refresh");
        refresh.setOnAction(e -> actions.refresh());
        HBox statusLine = new HBox(12, summary, spacer, refresh);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        statusLine.getStyleClass().add("doctor-status-line");

        VBox box = new VBox(4, title, caption, statusLine);
        box.getStyleClass().add("doctor-header");
        return box;
    }

    private String summaryText() {
        DoctorSummary s = DoctorSummary.of(checks.values());
        String text = tr("doctor.summary", s.ok(), s.warn(), s.missing());
        return s.pending() ? text + " · " + tr("doctor.checking") : text;
    }

    private Node row(DoctorCheck c) {
        Label glyph = new Label(glyphFor(c.status()));
        glyph.getStyleClass().addAll("doctor-status", statusClass(c.status()));
        glyph.setMinWidth(22);
        glyph.setAlignment(Pos.CENTER);

        Label name = new Label(c.label());
        name.getStyleClass().add("doctor-name");
        HBox line = new HBox(8, glyph, name);
        line.setAlignment(Pos.CENTER_LEFT);
        if (!c.command().isEmpty()) {
            Label command = new Label(c.command());
            command.getStyleClass().add("doctor-command");
            line.getChildren().add(command);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        line.getChildren().add(spacer);
        if (!c.detail().isEmpty()) {
            Label detail = new Label(c.detail());
            detail.getStyleClass().add("doctor-detail");
            line.getChildren().add(detail);
        }
        if (c.install() != DoctorCheck.Install.NONE && c.status() == DoctorStatus.MISSING) {
            Button install = new Button(tr("doctor.action.install"));
            install.getStyleClass().add("doctor-install");
            install.setOnAction(e -> actions.install(c));
            line.getChildren().add(install);
        }
        if (!c.settingsKey().isEmpty() && c.status() != DoctorStatus.OK && c.status() != DoctorStatus.CHECKING) {
            Hyperlink settings = new Hyperlink(tr("doctor.action.settings"));
            settings.getStyleClass().add("doctor-settings-link");
            settings.setOnAction(e -> actions.openSettings(c.settingsKey()));
            line.getChildren().add(settings);
        }

        VBox row = new VBox(2, line);
        row.getStyleClass().add("doctor-row");
        if (!c.tipKey().isEmpty() && c.status() != DoctorStatus.OK && c.status() != DoctorStatus.CHECKING) {
            Label tip = new Label(tr(c.tipKey(), c.tipArgs().toArray()));
            tip.getStyleClass().add("doctor-tip");
            tip.setWrapText(true);
            VBox.setMargin(tip, new Insets(0, 0, 0, 30)); // align under the name, past the status glyph
            row.getChildren().add(tip);
        }
        return row;
    }

    private static String glyphFor(DoctorStatus status) {
        return switch (status) {
            case OK -> "✓";
            case WARN -> "!";
            case MISSING -> "✗";
            case DISABLED -> "–";
            case CHECKING -> "…";
        };
    }

    private static String statusClass(DoctorStatus status) {
        return "doctor-status-" + status.name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    protected void layoutChildren() {
        scroll.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
