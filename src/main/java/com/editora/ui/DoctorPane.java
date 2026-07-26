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
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorStatus;
import com.editora.doctor.DoctorSummary;
import com.editora.doctor.DoctorText;
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

    /** Narrowest the centered column gets before the page scrolls horizontally. */
    private static final double CONTENT_MIN_WIDTH = 720;

    /** Widest the centered column grows to on a wide window — a probed absolute path needs the room. */
    private static final double CONTENT_MAX_WIDTH = 1040;

    /** Outer margin around the centered content (mirrors {@code WelcomePane}). */
    private static final double MARGIN = 48;

    /** Share of the column the command token may occupy before it ellipsizes (the tool name never shrinks). */
    private static final double COMMAND_WIDTH_SHARE = 0.42;

    /** Share of the column the probed detail (version or resolved path) may occupy before it ellipsizes. */
    private static final double DETAIL_WIDTH_SHARE = 0.58;

    /** Text longer than this gets a hover tooltip, since it may be ellipsized on a narrow window. */
    private static final int TOOLTIP_THRESHOLD = 36;

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
        centerHost.getStyleClass().add("doctor-scroll-content");
        centerHost.setAlignment(Pos.TOP_CENTER);
        centerHost.setPadding(new Insets(MARGIN));
        applyColumnWidth(1);

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
        double scale = Math.max(0.5, zoom);
        setStyle("-fx-font-size: " + scale + "em;");
        // The column is measured in pixels while the rows are text, so it has to scale with the font — else a
        // zoomed-in page truncates its paths and a zoomed-out one strands the version column far to the right.
        applyColumnWidth(scale);
    }

    private void applyColumnWidth(double scale) {
        double min = CONTENT_MIN_WIDTH * scale;
        double max = CONTENT_MAX_WIDTH * scale;
        content.setMinWidth(min);
        content.setPrefWidth(max);
        content.setMaxWidth(max);
        centerHost.setMinWidth(min + 2 * MARGIN);
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
        // The tool name identifies the row, so it must never ellipsize — the two path columns below absorb
        // all shrinking instead (an HBox otherwise shrinks every label by an equal share, crushing the name).
        name.setMinWidth(Region.USE_PREF_SIZE);
        HBox line = new HBox(8, glyph, name);
        line.setAlignment(Pos.CENTER_LEFT);

        String home = System.getProperty("user.home", "");
        String command = DoctorText.collapseHome(c.command(), home);
        String detail = DoctorText.collapseHome(c.detail(), home);
        if (DoctorText.detailRepeatsCommand(command, detail)) {
            detail = ""; // a command configured as an absolute path resolves to itself
        } else if (DoctorText.commandRepeatsDetail(command, detail)) {
            command = ""; // "jdtls" beside "~/…/bin/jdtls" — the path already says it
        }
        if (!command.isEmpty()) {
            line.getChildren().add(secondary(command, "doctor-command", COMMAND_WIDTH_SHARE));
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        line.getChildren().add(spacer);
        if (!detail.isEmpty()) {
            line.getChildren().add(secondary(detail, "doctor-detail", DETAIL_WIDTH_SHARE));
        }
        if (c.install() != DoctorCheck.Install.NONE && c.status() == DoctorStatus.MISSING) {
            Button install = new Button(tr("doctor.action.install"));
            install.getStyleClass().add("doctor-install");
            install.setMinWidth(Region.USE_PREF_SIZE);
            install.setOnAction(e -> actions.install(c));
            line.getChildren().add(install);
        }
        if (!c.settingsKey().isEmpty() && c.status() != DoctorStatus.OK && c.status() != DoctorStatus.CHECKING) {
            Hyperlink settings = new Hyperlink(tr("doctor.action.settings"));
            settings.getStyleClass().add("doctor-settings-link");
            settings.setMinWidth(Region.USE_PREF_SIZE);
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

    /**
     * A muted secondary column (the command, or the probed version/path). Capped to a share of the column so
     * it ellipsizes itself rather than squeezing the name, from the <i>left</i> for a path so the binary name
     * survives, with the full text on hover.
     */
    private Label secondary(String text, String styleClass, double widthShare) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setMinWidth(0);
        label.maxWidthProperty().bind(content.widthProperty().multiply(widthShare));
        if (DoctorText.isPathLike(text)) {
            label.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        }
        if (text.length() > TOOLTIP_THRESHOLD) {
            label.setTooltip(new Tooltip(text));
        }
        return label;
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
