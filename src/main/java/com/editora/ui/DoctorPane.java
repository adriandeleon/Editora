package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.config.PathDisplay;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorReport;
import com.editora.doctor.DoctorStatus;
import com.editora.doctor.DoctorSummary;
import com.editora.doctor.DoctorText;
import com.editora.editor.TabContent;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The Doctor screen — a full-tab health report of every external CLI/tool Editora's features rely on, shown
 * like the Welcome page (a real {@link TabContent} tab; same centered, scrollable layout).
 *
 * <p>What needs attention comes first: amber and red rows move to a "Needs attention" group at the top, a
 * section whose rows are all OK folds to one line, and tools that nothing in the window needs ("not installed")
 * fold to one line per section (review A4). Each row is a status pill (the Settings pages' pill family, review
 * B1), the tool with its command under it, the version or path in one left-aligned column, and a fixed action
 * column: Install…, Turn on, How to install and Settings… (review B2/C5).
 *
 * <p>Rows are built once per check id and updated <em>in place</em> as probe results land, so keyboard focus,
 * hover and tooltips survive a result (review C1); when a row changes group the nodes are re-parented and the
 * focus is put back on the same row's same control. A new run keeps every row's last result, dimmed, until its
 * probe returns (review C3). Updates are coalesced to one pass per FX pulse.
 */
final class DoctorPane extends Region implements TabContent {

    /** Row actions, implemented by {@code DoctorCoordinator}. */
    interface Actions {
        void refresh();

        void install(DoctorCheck check);

        void openSettings(String settingsKey);

        /** "Turn on": switch the row's feature on ({@link DoctorCheck#enableKey()}). */
        default void enable(DoctorCheck check) {}

        /** "How to install": open the row's help page. */
        default void openUrl(String url) {}

        /** "Reveal in File Manager" from a row's context menu. */
        default void reveal(Path path) {}

        /** The report was copied (the coordinator says so in the status bar). */
        default void reportCopied() {}
    }

    /** Narrowest the centered column gets before the page scrolls horizontally; tips wrap, so it is not scaled. */
    private static final double CONTENT_MIN_WIDTH = 560;

    /** Widest the centered column grows to on a wide window — a probed absolute path needs the room. */
    private static final double CONTENT_MAX_WIDTH = 1040;

    /** Outer margin around the centered content (mirrors {@code WelcomePane}), scaled with the text. */
    private static final double MARGIN = 48;

    /** Share of the column the tool name (with its command under it) takes; the version/path gets the rest. */
    private static final double NAME_WIDTH_SHARE = 0.34;

    /** Text longer than this gets a hover tooltip, since it may be ellipsized on a narrow window. */
    private static final int TOOLTIP_THRESHOLD = 36;

    /** The pseudo section the amber and red rows are gathered in, above every real section. */
    static final String ATTENTION = "attention";

    private static final String ROLE = "doctor.role";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Actions actions;

    /** Row id → latest check, in catalog order (first appearance orders the sections). */
    private final LinkedHashMap<String, DoctorCheck> checks = new LinkedHashMap<>();

    /** Rows whose shown result is from the previous run, being checked again. */
    private final Set<String> stale = new HashSet<>();

    /** Rows whose installer is running. */
    private final Set<String> installing = new HashSet<>();

    /** Sections (and {@code opt:<section>} folds) the user opened. */
    private final Set<String> expanded = new HashSet<>();

    private final Map<String, RowView> rows = new HashMap<>();
    private final Set<String> dirty = new LinkedHashSet<>();

    private final VBox content = new VBox();
    private final VBox body = new VBox();
    private final StackPane centerHost = new StackPane(content);
    private final ScrollPane scroll = new ScrollPane(centerHost);

    private final Label machine = new Label();
    private final Label remoteNote = new Label();
    private final HBox summary = new HBox(10);
    private final Button refresh = new Button(tr("doctor.action.refresh"));
    private final Node refreshIcon = Icons.refresh();
    private final Button copyReport = new Button(tr("doctor.action.copyReport"));
    private final Node copyIcon = Icons.copy();

    private boolean running;
    private @Nullable LocalTime checkedAt;
    private @Nullable String remoteTarget;
    private List<String> lastPlan = List.of();
    private boolean pulsePending;
    private @Nullable Node attentionHeader;

    DoctorPane(Actions actions) {
        this.actions = actions;
        getStyleClass().add("doctor-pane");
        content.getStyleClass().add("doctor-content");
        content.setAlignment(Pos.TOP_LEFT);
        content.setFillWidth(true);
        body.getStyleClass().add("doctor-body");
        body.setFillWidth(true);
        centerHost.getStyleClass().add("doctor-scroll-content");
        centerHost.setAlignment(Pos.TOP_CENTER);
        content.getChildren().setAll(header(), body);
        applyColumnWidth(1);

        scroll.getStyleClass().add("doctor-scroll");
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(false);
        getChildren().add(scroll);
        refreshAll();
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
        // The column is measured in pixels while the rows are text, so its widest size scales with the font; its
        // narrowest does not, so a zoomed-in page fits its window instead of scrolling sideways (review B4).
        applyColumnWidth(scale);
        for (Node icon : List.of(refreshIcon, copyIcon)) {
            icon.setScaleX(scale);
            icon.setScaleY(scale);
        }
    }

    private void applyColumnWidth(double s) {
        double max = CONTENT_MAX_WIDTH * s;
        double margin = MARGIN * s;
        content.setMinWidth(CONTENT_MIN_WIDTH);
        content.setPrefWidth(max);
        content.setMaxWidth(max);
        centerHost.setPadding(new Insets(margin));
        centerHost.setMinWidth(CONTENT_MIN_WIDTH + 2 * margin);
    }

    // --- content ---

    /** Replaces the whole check list with final rows and shows them at once (tests; a run uses beginRun). */
    void setChecks(List<DoctorCheck> list) {
        checks.clear();
        stale.clear();
        for (DoctorCheck c : list) {
            checks.put(c.id(), c);
        }
        rows.keySet().retainAll(checks.keySet());
        running = list.stream().anyMatch(c -> c.status() == DoctorStatus.CHECKING);
        refreshAll();
    }

    /**
     * Starts a run: the catalog's rows in order. A row that was already shown keeps its last result, marked as
     * being checked again, until its probe returns; a new row shows "Checking…"; a row no longer in the catalog
     * goes away.
     */
    void beginRun(List<DoctorCheck> placeholders) {
        LinkedHashMap<String, DoctorCheck> next = new LinkedHashMap<>();
        stale.clear();
        for (DoctorCheck p : placeholders) {
            DoctorCheck old = checks.get(p.id());
            if (old != null && p.status() == DoctorStatus.CHECKING && old.status() != DoctorStatus.CHECKING) {
                next.put(p.id(), old);
                stale.add(p.id());
            } else {
                next.put(p.id(), p);
            }
        }
        checks.clear();
        checks.putAll(next);
        rows.keySet().retainAll(checks.keySet());
        running = true;
        refreshAll();
    }

    /** Updates one row in place (a probe result landed); coalesced to one pass per pulse. */
    void updateCheck(DoctorCheck check) {
        checks.put(check.id(), check);
        stale.remove(check.id());
        dirty.add(check.id());
        schedule();
    }

    /** The run finished: every probe has answered. */
    void endRun() {
        running = false;
        checkedAt = LocalTime.now();
        dirty.addAll(stale);
        stale.clear();
        schedule();
    }

    /** A row's installer started: its button reads "Installing…" until {@link #installFinished}. */
    void installStarted(String id) {
        installing.add(id);
        dirty.add(id);
        schedule();
    }

    void installFinished(String id) {
        installing.remove(id);
        dirty.add(id);
        schedule();
    }

    /** The machine the window's project runs its tools on when that is not this one (null: this computer). */
    void setMachine(@Nullable String target) {
        remoteTarget = target == null || target.isBlank() ? null : target;
        updateHeader();
    }

    /** Snapshot of the current rows, in catalog order (test seam). */
    List<DoctorCheck> currentChecks() {
        return List.copyOf(checks.values());
    }

    /** Whether a run is in flight (test seam). */
    boolean isRunning() {
        return running;
    }

    private void schedule() {
        if (pulsePending) {
            return;
        }
        pulsePending = true;
        Platform.runLater(() -> {
            pulsePending = false;
            flush();
        });
    }

    private void refreshAll() {
        dirty.addAll(checks.keySet());
        flush();
    }

    /** Renders the dirty rows in place, re-groups if the grouping changed, and updates the header. */
    private void flush() {
        for (String id : dirty) {
            DoctorCheck c = checks.get(id);
            if (c != null) {
                rows.computeIfAbsent(id, k -> new RowView()).render(c);
            }
        }
        dirty.clear();
        List<String> plan = plan();
        if (!plan.equals(lastPlan)) {
            lastPlan = plan;
            regroup(plan);
        }
        updateHeader();
    }

    // --- grouping ---

    /**
     * The page as a list of tokens — {@code H:<section>} (a header), {@code E:<section>} (an opened all-OK
     * section), {@code F:<section>:<n>} (a folded one), {@code R:<id>} (a row), {@code O:<section>:<n>} (a fold
     * of n not-installed rows) and {@code X:<section>:<n>} (the "Hide" after them once opened) — so a pass can
     * tell whether anything moved.
     */
    private List<String> plan() {
        List<String> plan = new ArrayList<>();
        List<String> attentionIds = new ArrayList<>();
        LinkedHashMap<String, List<DoctorCheck>> sections = new LinkedHashMap<>();
        for (DoctorCheck c : checks.values()) {
            if (c.status().needsAttention()) {
                attentionIds.add(c.id());
            } else {
                sections.computeIfAbsent(c.sectionKey(), k -> new ArrayList<>()).add(c);
            }
        }
        if (!attentionIds.isEmpty()) {
            plan.add("H:" + ATTENTION);
            attentionIds.forEach(id -> plan.add("R:" + id));
        }
        for (var e : sections.entrySet()) {
            String sec = e.getKey();
            List<DoctorCheck> list = e.getValue();
            // A row being checked again keeps its old result, so a folded section stays folded while it is.
            boolean allOk = list.stream().allMatch(c -> c.status() == DoctorStatus.OK);
            if (allOk && !expanded.contains(sec)) {
                plan.add("F:" + sec + ":" + list.size());
                continue;
            }
            plan.add((allOk ? "E:" : "H:") + sec);
            List<DoctorCheck> optional = list.stream()
                    .filter(c -> c.status() == DoctorStatus.OPTIONAL)
                    .toList();
            boolean foldOptional = optional.size() >= 2;
            boolean optionalOpen = expanded.contains("opt:" + sec);
            for (DoctorCheck c : list) {
                if (c.status() != DoctorStatus.OPTIONAL || !foldOptional || optionalOpen) {
                    plan.add("R:" + c.id());
                }
            }
            if (foldOptional) {
                plan.add((optionalOpen ? "X:" : "O:") + sec + ":" + optional.size());
            }
        }
        return plan;
    }

    private void regroup(List<String> plan) {
        FocusKey focus = focusKey();
        List<Node> nodes = new ArrayList<>();
        attentionHeader = null;
        for (String token : plan) {
            String kind = token.substring(0, 1);
            String rest = token.substring(2);
            switch (kind) {
                case "H" -> {
                    Label label = sectionLabel(rest);
                    if (ATTENTION.equals(rest)) {
                        label.getStyleClass().add("doctor-attention");
                        attentionHeader = label;
                    }
                    nodes.add(label);
                }
                case "E" -> nodes.add(foldHeader(rest, false, 0));
                case "F" -> {
                    int cut = rest.lastIndexOf(':');
                    nodes.add(foldHeader(rest.substring(0, cut), true, Integer.parseInt(rest.substring(cut + 1))));
                }
                case "R" -> {
                    RowView row = rows.get(rest); // flush() renders every check before planning
                    if (row != null) {
                        nodes.add(row.node);
                    }
                }
                case "O", "X" -> {
                    int cut = rest.lastIndexOf(':');
                    nodes.add(optionalFold(
                            rest.substring(0, cut), Integer.parseInt(rest.substring(cut + 1)), "O".equals(kind)));
                }
                default -> {}
            }
        }
        body.getChildren().setAll(nodes);
        restoreFocus(focus);
    }

    private static Label sectionLabel(String sec) {
        Label label = new Label(tr("doctor.section." + sec));
        label.getStyleClass().add("doctor-section");
        return label;
    }

    /** An all-OK section: folded to "Version Control · 2 OK  Show", or opened with a "Hide". */
    private Node foldHeader(String sec, boolean folded, int okCount) {
        Label label = sectionLabel(sec);
        HBox box = new HBox(10, label);
        box.setAlignment(Pos.BASELINE_LEFT);
        box.getStyleClass().add("doctor-section-line");
        if (folded) {
            Label count = new Label(tr("doctor.fold.allOk", okCount));
            count.getStyleClass().add("doctor-fold-count");
            box.getChildren().add(count);
        }
        Hyperlink toggle = new Hyperlink(tr(folded ? "doctor.fold.show" : "doctor.fold.hide"));
        toggle.getStyleClass().add("doctor-fold-link");
        toggle.getProperties().put(ROLE, "fold:" + sec);
        toggle.setOnAction(e -> toggleExpanded(sec));
        box.getChildren().add(toggle);
        return box;
    }

    /** The not-installed rows of a section, folded to one line (or the "Hide" after them once opened). */
    private Node optionalFold(String sec, int count, boolean folded) {
        HBox box = new HBox(10);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("doctor-optional-fold");
        if (folded) {
            Label pill = new Label(tr("doctor.status.optional"));
            pill.getStyleClass().addAll("settings-git-status", "settings-git-neutral", "doctor-pill");
            pill.setMinWidth(Region.USE_PREF_SIZE);
            List<String> names = checks.values().stream()
                    .filter(c -> c.sectionKey().equals(sec) && c.status() == DoctorStatus.OPTIONAL)
                    .map(DoctorCheck::label)
                    .toList();
            Label text = new Label(tr("doctor.fold.optional", count, String.join(", ", names)));
            text.getStyleClass().add("doctor-fold-count");
            text.setMinWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            box.getChildren().addAll(pill, text);
        }
        Hyperlink toggle = new Hyperlink(tr(folded ? "doctor.fold.show" : "doctor.fold.hideOptional", count));
        toggle.getStyleClass().add("doctor-fold-link");
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        toggle.getProperties().put(ROLE, "fold:opt:" + sec);
        toggle.setOnAction(e -> toggleExpanded("opt:" + sec));
        box.getChildren().add(toggle);
        return box;
    }

    /** Opens or folds a section ({@code opt:<section>} for its not-installed rows) — test seam. */
    void toggleExpandedForTest(String key) {
        toggleExpanded(key);
    }

    private void toggleExpanded(String key) {
        if (!expanded.remove(key)) {
            expanded.add(key);
        }
        flush();
    }

    // --- focus ---

    /** Where the keyboard focus is inside the page: a row's control, or a fold link. */
    private record FocusKey(
            @Nullable String rowId, @Nullable String role) {}

    private @Nullable FocusKey focusKey() {
        Node f = getScene() == null ? null : getScene().getFocusOwner();
        if (f == null || !isInside(f, body)) {
            return null;
        }
        String role = roleOf(f);
        for (var e : rows.entrySet()) {
            if (isInside(f, e.getValue().node)) {
                return new FocusKey(e.getKey(), role);
            }
        }
        return role == null ? null : new FocusKey(null, role);
    }

    private void restoreFocus(@Nullable FocusKey key) {
        if (key == null) {
            return;
        }
        Parent scope = key.rowId() == null ? body : rows.containsKey(key.rowId()) ? rows.get(key.rowId()).node : null;
        if (scope == null || scope.getScene() == null) {
            // The row went into a fold (it became one of a section's not-installed tools, or its section is all OK
            // now): the fold's own link takes the focus, so it never stays on a control that left the page.
            DoctorCheck c = key.rowId() == null ? null : checks.get(key.rowId());
            Node fold = c == null ? null : findRole(body, "fold:opt:" + c.sectionKey());
            if (fold == null && c != null) {
                fold = findRole(body, "fold:" + c.sectionKey());
            }
            (fold != null ? fold : refresh).requestFocus();
            return;
        }
        Node target = key.role() == null ? null : findRole(scope, key.role());
        if (target == null && key.rowId() != null) {
            target = firstFocusable(scope);
        }
        if (target != null && target.getScene() != null && target.getScene().getFocusOwner() != target) {
            target.requestFocus();
        }
    }

    private static boolean isInside(Node n, Node root) {
        for (Node p = n; p != null; p = p.getParent()) {
            if (p == root) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String roleOf(Node n) {
        for (Node p = n; p != null; p = p.getParent()) {
            Object r = p.getProperties().get(ROLE);
            if (r != null) {
                return r.toString();
            }
        }
        return null;
    }

    private static @Nullable Node findRole(Parent root, String role) {
        for (Node ch : root.getChildrenUnmodifiable()) {
            if (role.equals(ch.getProperties().get(ROLE))) {
                return ch;
            }
            if (ch instanceof Parent p) {
                Node found = findRole(p, role);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static @Nullable Node firstFocusable(Parent root) {
        for (Node ch : root.getChildrenUnmodifiable()) {
            if (ch.isFocusTraversable() && ch.isVisible() && !ch.isDisabled()) {
                return ch;
            }
            if (ch instanceof Parent p) {
                Node found = firstFocusable(p);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // --- header ---

    private Node header() {
        Label title = new Label(tr("doctor.title"));
        title.getStyleClass().add("doctor-title");
        Label caption = new Label(tr("doctor.caption"));
        caption.getStyleClass().add("doctor-caption");
        caption.setWrapText(true);
        machine.getStyleClass().add("doctor-machine");
        remoteNote.getStyleClass().add("doctor-remote-note");
        remoteNote.setWrapText(true);
        remoteNote.managedProperty().bind(remoteNote.visibleProperty());
        remoteNote.setVisible(false);

        summary.getStyleClass().add("doctor-summary");
        summary.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        // Icons wrapped in a Group so a scaled icon (text zoom) takes its scaled size in the layout.
        copyReport.setGraphic(new Group(copyIcon));
        copyReport.getStyleClass().add("doctor-copy");
        copyReport.setMinWidth(Region.USE_PREF_SIZE);
        copyReport.setOnAction(e -> copyReport());
        refresh.setGraphic(new Group(refreshIcon));
        refresh.getStyleClass().add("doctor-refresh");
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        // Never disabled while a run is in flight: a focused button that is disabled loses the keyboard focus. A
        // second Refresh simply starts over; the summary says a run is going and how far it got.
        refresh.setOnAction(e -> actions.refresh());
        HBox statusLine = new HBox(12, summary, spacer, copyReport, refresh);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        statusLine.getStyleClass().add("doctor-status-line");

        VBox box = new VBox(4, title, caption, machine, remoteNote, statusLine);
        box.getStyleClass().add("doctor-header");
        return box;
    }

    private void updateHeader() {
        DoctorSummary s = DoctorSummary.of(checks.values());
        List<Node> parts = new ArrayList<>();
        if (s.missing() > 0) {
            parts.add(jump(tr("doctor.summary.problems", s.missing()), "doctor-summary-problems"));
        }
        if (s.warn() > 0) {
            parts.add(jump(tr("doctor.summary.warnings", s.warn()), "doctor-summary-warnings"));
        }
        if (s.issues() == 0 && !running) {
            parts.add(summaryText(tr("doctor.summary.noProblems"), "doctor-summary-clear"));
        }
        parts.add(summaryText(tr("doctor.summary.ok", s.ok()), null));
        if (s.optional() > 0) {
            parts.add(summaryText(tr("doctor.summary.optional", s.optional()), null));
        }
        if (s.disabled() > 0) {
            parts.add(summaryText(tr("doctor.summary.off", s.disabled()), null));
        }
        if (running) {
            int total = checks.size();
            int pending = s.checking() + stale.size();
            parts.add(summaryText(tr("doctor.summary.checking", total - pending, total), "doctor-summary-checking"));
        }
        summary.getChildren().setAll(parts);

        machine.setText(
                running
                        ? tr("doctor.machine.checking")
                        : checkedAt == null
                                ? tr("doctor.machine.here")
                                : tr("doctor.machine.checked", TIME.format(checkedAt)));
        remoteNote.setVisible(remoteTarget != null);
        remoteNote.setText(remoteTarget == null ? "" : tr("doctor.remoteNote", remoteTarget));
    }

    private static Label summaryText(String text, @Nullable String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().add("doctor-summary-part");
        if (styleClass != null) {
            l.getStyleClass().add(styleClass);
        }
        return l;
    }

    /** A summary count that scrolls to the "Needs attention" group. */
    private Hyperlink jump(String text, String styleClass) {
        Hyperlink link = new Hyperlink(text);
        link.getStyleClass().addAll("doctor-summary-link", styleClass);
        link.setOnAction(e -> scrollTo(attentionHeader));
        return link;
    }

    private void scrollTo(@Nullable Node target) {
        if (target == null || target.getScene() == null) {
            return;
        }
        double contentHeight = centerHost.getHeight();
        double viewport = scroll.getViewportBounds().getHeight();
        if (contentHeight <= viewport) {
            return;
        }
        double y = centerHost.sceneToLocal(target.localToScene(0, 0)).getY();
        scroll.setVvalue(Math.max(0, Math.min(1, (y - 8) / (contentHeight - viewport))));
    }

    // --- report ---

    /** The report "Copy report" puts on the clipboard (test seam). */
    String reportText() {
        String home = System.getProperty("user.home", "");
        List<String> header = new ArrayList<>();
        header.add(com.editora.AppInfo.versionLine());
        header.add(System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") + " ("
                + System.getProperty("os.arch", "") + ") · Java " + System.getProperty("java.version", ""));
        header.add(machine.getText());
        if (remoteTarget != null) {
            header.add(remoteNote.getText());
        }
        DoctorSummary s = DoctorSummary.of(checks.values());
        header.add(tr("doctor.report.counts", s.missing(), s.warn(), s.ok(), s.optional(), s.disabled()));
        List<DoctorReport.Line> lines = new ArrayList<>();
        for (DoctorCheck c : checks.values()) {
            String[] shown = shownCommandAndDetail(c, home);
            String tip = showsTip(c) ? tr(c.tipKey(), c.tipArgs().toArray()) : "";
            lines.add(new DoctorReport.Line(
                    tr("doctor.section." + c.sectionKey()),
                    statusWord(c.status()),
                    c.label(),
                    shown[0],
                    shown[1],
                    tip));
        }
        return DoctorReport.render(tr("doctor.title"), header, lines);
    }

    private void copyReport() {
        putOnClipboard(reportText());
        actions.reportCopied();
    }

    // --- rows ---

    /** The command and detail as a row shows them: home written ~, and whichever repeats the other dropped. */
    static String[] shownCommandAndDetail(DoctorCheck c, String home) {
        String command = PathDisplay.collapseHome(c.command(), home);
        String detail = DoctorText.collapseHomeEverywhere(c.detail(), home);
        if (DoctorText.detailRepeatsCommand(command, detail)) {
            detail = ""; // a command configured as an absolute path resolves to itself
        } else if (DoctorText.commandRepeatsDetail(command, detail)) {
            command = ""; // "jdtls" beside "~/…/bin/jdtls" — the path already says it
        }
        return new String[] {command, detail};
    }

    private static boolean showsTip(DoctorCheck c) {
        return !c.tipKey().isEmpty() && c.status() != DoctorStatus.OK && c.status() != DoctorStatus.CHECKING;
    }

    static String statusWord(DoctorStatus status) {
        return tr("doctor.status." + status.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String pillClass(DoctorStatus status) {
        return switch (status) {
            case OK -> "settings-git-found";
            case MISSING -> "settings-git-missing";
            case WARN -> "doctor-pill-warn";
            case OPTIONAL, DISABLED, CHECKING -> "settings-git-neutral";
        };
    }

    /** One row's nodes: a stable box whose children are rebuilt when its check changes. */
    private final class RowView {
        final VBox node = new VBox(2);

        RowView() {
            node.getStyleClass().add("doctor-row");
        }

        void render(DoctorCheck c) {
            String focusedRole = null;
            Node f = node.getScene() == null ? null : node.getScene().getFocusOwner();
            if (f != null && isInside(f, node)) {
                focusedRole = roleOf(f);
            }
            String[] shown = shownCommandAndDetail(c, System.getProperty("user.home", ""));

            Label pill = new Label(statusWord(c.status()));
            pill.getStyleClass().addAll("settings-git-status", pillClass(c.status()), "doctor-pill");
            pill.setMinWidth(Region.USE_PREF_SIZE);

            Label name = new Label(c.label());
            name.getStyleClass().add("doctor-name");
            // The tool name identifies the row, so it never ellipsizes; the command under it and the detail
            // beside it absorb all shrinking.
            name.setMinWidth(Region.USE_PREF_SIZE);
            VBox nameBox = new VBox(0, name);
            nameBox.getStyleClass().add("doctor-name-box");
            nameBox.prefWidthProperty().bind(content.widthProperty().multiply(NAME_WIDTH_SHARE));
            if (!shown[0].isEmpty()) {
                nameBox.getChildren().add(secondary(shown[0], "doctor-command"));
            }

            Label detail = secondary(shown[1], "doctor-detail");
            detail.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(detail, Priority.ALWAYS);

            HBox actionBox = new HBox(6);
            actionBox.setAlignment(Pos.CENTER_RIGHT);
            actionBox.getStyleClass().add("doctor-actions");
            actionBox.setMinWidth(Region.USE_PREF_SIZE);
            addActions(c, actionBox);

            HBox line = new HBox(12, pill, nameBox, detail, actionBox);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("doctor-row-line");

            List<Node> children = new ArrayList<>(List.of(line));
            String tipText = showsTip(c) ? tr(c.tipKey(), c.tipArgs().toArray()) : "";
            if (!tipText.isEmpty()) {
                Label tip = new Label(tipText);
                tip.getStyleClass().add("doctor-tip");
                tip.setWrapText(true);
                tip.setMinHeight(Region.USE_PREF_SIZE);
                tip.setMinWidth(0);
                tip.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(tip, Priority.ALWAYS);
                // Under the name: indented by the pill's width and the row's gap, whatever the text size.
                Region indent = new Region();
                indent.minWidthProperty().bind(pill.widthProperty().add(line.getSpacing()));
                children.add(new HBox(indent, tip));
            }
            node.getChildren().setAll(children);
            node.getStyleClass()
                    .setAll("doctor-row", "doctor-row-" + c.status().name().toLowerCase(java.util.Locale.ROOT));
            if (stale.contains(c.id())) {
                node.getStyleClass().add("doctor-stale");
            }
            node.getProperties().put("doctor.id", c.id());
            // Screen readers: one sentence for the row, on its pill, the first thing in it (review C6).
            String sentence = c.label() + ", " + statusWord(c.status())
                    + (shown[1].isEmpty() ? "" : ", " + shown[1])
                    + (tipText.isEmpty() ? "" : ". " + tipText);
            pill.setAccessibleText(sentence);
            node.setOnContextMenuRequested(e -> {
                rowMenu(c).show(node, e.getScreenX(), e.getScreenY());
                e.consume();
            });

            if (focusedRole != null) {
                Node target = findRole(node, focusedRole);
                if (target == null) {
                    target = firstFocusable(node);
                }
                if (target != null) {
                    target.requestFocus();
                }
            }
        }

        private void addActions(DoctorCheck c, HBox box) {
            DoctorStatus st = c.status();
            if (c.offersInstall()) {
                boolean busy = installing.contains(c.id());
                // Not disabled while busy (a disabled button drops the focus): it says so and ignores clicks;
                // the installer itself refuses a second run.
                Button install = action(tr(busy ? "doctor.action.installing" : "doctor.action.install"), "install");
                install.getStyleClass().add(busy ? "doctor-installing" : "doctor-install");
                install.setOnAction(e -> {
                    if (!installing.contains(c.id())) {
                        actions.install(c);
                    }
                });
                box.getChildren().add(install);
            }
            if (!c.enableKey().isEmpty() && (st == DoctorStatus.DISABLED || st == DoctorStatus.MISSING)) {
                Button enable = action(tr("doctor.action.turnOn"), "enable");
                enable.getStyleClass().add("doctor-turn-on");
                enable.setOnAction(e -> actions.enable(c));
                box.getChildren().add(enable);
            }
            if (!c.helpUrl().isEmpty()
                    && (st == DoctorStatus.WARN || st == DoctorStatus.OPTIONAL || st == DoctorStatus.MISSING)) {
                Button help = action(tr("doctor.action.howToInstall"), "help");
                help.setOnAction(e -> actions.openUrl(c.helpUrl()));
                box.getChildren().add(help);
            }
            if (!c.settingsKey().isEmpty()) {
                Button settings = action(tr("doctor.action.settings"), "settings");
                settings.getStyleClass().addAll("flat", "doctor-settings-link");
                settings.setOnAction(e -> actions.openSettings(c.settingsKey()));
                boolean quiet = st == DoctorStatus.OK || st == DoctorStatus.OPTIONAL || st == DoctorStatus.CHECKING;
                if (quiet) {
                    // On every row, but seen only on hover or focus where nothing is wrong: forty identical links
                    // down the right edge were noise (review B2). Opacity, not visibility, so Tab still reaches it.
                    settings.opacityProperty()
                            .bind(Bindings.when(node.hoverProperty().or(settings.focusedProperty()))
                                    .then(1.0)
                                    .otherwise(0.0));
                }
                box.getChildren().add(settings);
            }
        }

        private Button action(String text, String role) {
            Button b = new Button(text);
            b.getStyleClass().addAll("small", "doctor-action");
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.getProperties().put(ROLE, role);
            return b;
        }

        /** Copy the row, copy its path, reveal the path, open its settings. */
        private ContextMenu rowMenu(DoctorCheck c) {
            ContextMenu menu = new ContextMenu();
            MenuItem copy = new MenuItem(tr("doctor.menu.copyRow"), Icons.copy());
            copy.setOnAction(e -> putOnClipboard(rowText(c)));
            menu.getItems().add(copy);
            Path path = pathIn(c.detail());
            if (path != null) {
                MenuItem copyPath = new MenuItem(tr("doctor.menu.copyPath"));
                copyPath.setOnAction(e -> putOnClipboard(path.toString()));
                MenuItem reveal = new MenuItem(tr("menu.revealInFileManager"), Icons.revealInFiles());
                reveal.setOnAction(e -> actions.reveal(path));
                menu.getItems().addAll(copyPath, reveal);
            }
            if (!c.settingsKey().isEmpty()) {
                MenuItem settings = new MenuItem(tr("doctor.action.settings"));
                settings.setOnAction(e -> actions.openSettings(c.settingsKey()));
                menu.getItems().add(settings);
            }
            return menu;
        }
    }

    /** One row as a line of text ({@code [Missing] XML (lemminx): …}), for "Copy". */
    static String rowText(DoctorCheck c) {
        String[] shown = shownCommandAndDetail(c, System.getProperty("user.home", ""));
        StringBuilder sb = new StringBuilder("[")
                .append(statusWord(c.status()))
                .append("] ")
                .append(c.label());
        if (!shown[0].isEmpty()) {
            sb.append(" (").append(shown[0]).append(')');
        }
        if (!shown[1].isEmpty()) {
            sb.append(": ").append(shown[1]);
        }
        return sb.toString();
    }

    /** The absolute path in a detail ("1.2 — /usr/bin/x", "/usr/bin/x (PATH)"), when it names an existing file. */
    static @Nullable Path pathIn(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        String candidate = detail;
        int dash = detail.lastIndexOf(" — ");
        if (dash >= 0) {
            candidate = detail.substring(dash + 3);
        }
        int paren = candidate.indexOf(" (");
        if (paren > 0) {
            candidate = candidate.substring(0, paren);
        }
        candidate = candidate.strip();
        if (!DoctorText.isPathLike(candidate) || candidate.startsWith("~")) {
            return null;
        }
        try {
            Path p = Path.of(candidate);
            return p.isAbsolute() && Files.exists(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void putOnClipboard(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /**
     * A muted secondary text (the command, or the probed version/path) that ellipsizes itself rather than
     * squeezing the name — from the <i>left</i> for a path so the binary name survives — with the full text on
     * hover.
     */
    private static Label secondary(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setMinWidth(0);
        if (DoctorText.isPathLike(text)) {
            label.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        }
        if (text.length() > TOOLTIP_THRESHOLD) {
            label.setTooltip(new Tooltip(text));
        }
        return label;
    }

    @Override
    protected void layoutChildren() {
        scroll.resizeRelocate(0, 0, getWidth(), getHeight());
    }
}
