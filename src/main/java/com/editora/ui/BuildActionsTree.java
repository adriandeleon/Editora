package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.build.BuildAction;
import com.editora.build.BuildActionsProvider;

import static com.editora.i18n.Messages.tr;

/**
 * A build tool's IntelliJ-style tasks tool window: a mini icon toolbar (Run / Refresh / Stop / Run custom,
 * plus an optional tool-specific "Load all tasks…" for Gradle) over a {@link TreeView} of the sections
 * ({@code Lifecycle}, {@code Scripts}, {@code Targets}, …) produced by a {@link BuildActionsProvider}. A
 * {@link BuildAction.Task} is a runnable leaf (double-click / Enter / the Run button runs {@code executable +
 * task.args + provider.toggleArgs(active)}); a {@link BuildAction.Toggle} is a checkbox leaf (Maven's
 * {@code <profile>}s, Cargo's {@code --release}) that re-queries the provider so a checked toggle can reveal
 * extra rows. Complements the searchable {@link BuildActionsPopup} (kept for the command palette). One
 * instance per tool; the streaming output goes to the separate {@link BuildToolPanel} console window.
 */
public final class BuildActionsTree extends VBox implements ToolWindowContent {

    /** Runs the selected task's args plus the extra argv from the active toggles (mirrors the popup). */
    @FunctionalInterface
    public interface RunHandler {
        void run(List<String> taskArgs, List<String> toggleArgs);
    }

    private sealed interface Item permits SectionItem, TaskItem, ToggleItem, ProjectItem {}

    private record SectionItem(String title, String tooltip) implements Item {}

    private record TaskItem(BuildAction.Task task) implements Item {}

    private record ToggleItem(BuildAction.Toggle toggle) implements Item {}

    /** A detected build root of the open project (the "Projects" section); selecting it switches the tree. */
    private record ProjectItem(Path root, String label, boolean current) implements Item {}

    /** The workspace-trust state the header shows for the detected root. */
    public enum TrustState {
        /** The tool's builds are not gated (npm, Cargo, …). */
        NOT_GATED,
        TRUSTED,
        UNTRUSTED
    }

    private final TreeView<Item> tree = new TreeView<>();
    private final Label placeholder = new Label();
    private final StackPane body = new StackPane();
    private final HBox toolbar = new HBox(2);
    private final TextField filterField = new TextField();
    private final Button runButton;
    private final Button stopButton;
    private Button secondaryButton;

    /** The active toggle ids — the coordinator's set for the detected root (shared with the popup, so a
     *  profile checked here is the one the popup runs with), or this view's own until one is handed in. */
    private Set<String> activeToggles = new LinkedHashSet<>();

    private BuildActionsProvider provider;

    // --- header: the detected root + its trust state; the "Projects" section; a parse-error notice ---
    private final Label rootLabel = new Label();
    private final Label trustPill = new Label();
    private final Hyperlink revokeLink = new Hyperlink(tr("buildtree.revokeTrust"));
    private final HBox header = new HBox(6, rootLabel, trustPill, revokeLink);
    private final Label noticeLabel = new Label();
    private final Hyperlink noticeLink = new Hyperlink();
    private final VBox notice = new VBox(2, noticeLabel, noticeLink);
    private List<Path> projects = List.of();
    private Path currentRoot;
    private Path projectRoot;
    private Consumer<Path> onSelectProject = p -> {};
    private Runnable onRevokeTrust = () -> {};
    /** Placeholder text for a null provider (null ⇒ the generic "nothing detected"). */
    private String emptyMessage;

    /** The sections currently on screen — lets {@link #setProvider} skip a no-op re-root (see its javadoc).
     *  Held <em>unfiltered</em>, with the filter that produced the visible tree beside it, so the skip stays
     *  correct while a filter is active (same tasks + same query ⇒ same tree). */
    private List<BuildAction.Section> rendered;

    private String renderedFilter = "";

    private RunHandler onRun = (a, b) -> {};
    private Runnable onRefresh = () -> {};
    private Runnable onRunCustom = () -> {};
    private Runnable onStop = () -> {};

    public BuildActionsTree() {
        getStyleClass().add("build-tasks-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        runButton = iconButton(Icons.run(), tr("buildtree.run"), this::runSelected);
        Button refreshButton = iconButton(Icons.refresh(), tr("buildtree.refresh"), () -> onRefresh.run());
        stopButton = iconButton(Icons.stopSquare(), tr("buildtree.stop"), () -> onStop.run());
        Button customButton = iconButton(Icons.edit(), tr("buildtree.runCustom"), () -> onRunCustom.run());
        toolbar.getChildren().addAll(runButton, refreshButton, stopButton, customButton);
        toolbar.getStyleClass().add("build-tasks-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Filter/search row, mirroring the Bookmarks / Personal Notes tool windows (same style classes, same
        // Down/Enter hand-off into the results via FilterFieldNav).
        filterField.setPromptText(tr("buildtree.filterPrompt"));
        filterField.getStyleClass().add("bookmarks-filter");
        filterField.textProperty().addListener((o, w, n) -> rebuild());
        FilterFieldNav.install(filterField, tree, this::runSelected);
        HBox.setHgrow(filterField, Priority.ALWAYS);
        Button clearFilter = new Button("✕");
        clearFilter.getStyleClass().add("project-filter-clear");
        clearFilter.setFocusTraversable(false);
        clearFilter.setTooltip(new Tooltip(tr("project.filterClear")));
        clearFilter.setOnAction(e -> {
            filterField.clear();
            filterField.requestFocus();
        });
        clearFilter.visibleProperty().bind(filterField.textProperty().isEmpty().not());
        clearFilter.managedProperty().bind(clearFilter.visibleProperty());
        HBox filterBar = new HBox(6, filterField, clearFilter);
        filterBar.getStyleClass().add("project-filter-bar");
        filterBar.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.getStyleClass().add("build-tasks-tree");
        tree.setCellFactory(t -> new ItemCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                runSelected();
            }
        });
        tree.addEventFilter(KeyEvent.KEY_PRESSED, this::onTreeKey);

        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        body.getChildren().addAll(tree, placeholder);
        VBox.setVgrow(body, Priority.ALWAYS);

        // The header names the folder a build runs in (the detected root, which may differ from the active
        // file's module after a Projects selection) and whether it is trusted; Revoke undoes the trust.
        header.getStyleClass().add("build-tasks-header");
        header.setAlignment(Pos.CENTER_LEFT);
        rootLabel.getStyleClass().add("build-tasks-root");
        trustPill.getStyleClass().add("build-tasks-trust");
        revokeLink.getStyleClass().add("build-tasks-revoke");
        revokeLink.setFocusTraversable(false);
        revokeLink.setOnAction(e -> onRevokeTrust.run());
        header.setVisible(false);
        header.managedProperty().bind(header.visibleProperty());

        // The parse-error notice: the reason the build file is broken, with a link to open it.
        notice.getStyleClass().add("build-tasks-notice");
        noticeLabel.getStyleClass().add("build-tasks-notice-text");
        noticeLabel.setWrapText(true);
        noticeLink.setFocusTraversable(false);
        notice.setVisible(false);
        notice.managedProperty().bind(notice.visibleProperty());

        getChildren().addAll(toolbar, header, notice, filterBar, body);

        // Run does nothing on a section, a profile or a project row: say so by greying it (review B30).
        runButton
                .disableProperty()
                .bind(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> {
                            TreeItem<Item> selected = tree.getSelectionModel().getSelectedItem();
                            return selected == null || !(selected.getValue() instanceof TaskItem);
                        },
                        tree.getSelectionModel().selectedItemProperty()));

        setRunning(false); // nothing is running yet — Stop must not look live on a fresh window
        setProvider(null);
    }

    /** Selecting a row of the "Projects" section asks the coordinator to switch to that build root. */
    public void setOnSelectProject(Consumer<Path> onSelectProject) {
        this.onSelectProject = onSelectProject;
    }

    public void setOnRevokeTrust(Runnable onRevokeTrust) {
        this.onRevokeTrust = onRevokeTrust;
    }

    /**
     * Hands this view the toggle set it reads and writes — the coordinator's, per detected root, shared with
     * the popup. Toggle state used to live in each view, so a profile ticked here was not the one the popup
     * ran with (review B8).
     */
    public void setActiveToggles(Set<String> toggles) {
        this.activeToggles = toggles == null ? new LinkedHashSet<>() : toggles;
    }

    /** The toggle set this view currently reads (for a test, or a view that owns its own). */
    public Set<String> activeToggles() {
        return activeToggles;
    }

    /**
     * Shows the detected root and its trust state above the tree; a {@code null} root hides the header. The
     * name shown is the root relative to {@code project} when it is inside it ("." for the project itself),
     * with the full path in the tooltip.
     */
    public void setHeader(Path root, Path project, TrustState trust) {
        currentRoot = root;
        projectRoot = project;
        if (root == null) {
            header.setVisible(false);
            rebuildProjects();
            return;
        }
        rootLabel.setText(tr("buildtree.root", displayName(root, project)));
        rootLabel.setTooltip(new Tooltip(root.toAbsolutePath().normalize().toString()));
        trustPill.setVisible(trust != TrustState.NOT_GATED);
        trustPill.setManaged(trust != TrustState.NOT_GATED);
        trustPill.getStyleClass().removeAll("build-tasks-trusted", "build-tasks-untrusted");
        if (trust == TrustState.TRUSTED) {
            trustPill.setText(tr("buildtree.trusted"));
            trustPill.getStyleClass().add("build-tasks-trusted");
        } else if (trust == TrustState.UNTRUSTED) {
            trustPill.setText(tr("buildtree.untrusted"));
            trustPill.getStyleClass().add("build-tasks-untrusted");
        }
        revokeLink.setVisible(trust == TrustState.TRUSTED);
        revokeLink.setManaged(trust == TrustState.TRUSTED);
        header.setVisible(true);
        rebuildProjects();
    }

    /** The build roots found below the open project (the "Projects" section); empty hides the section. */
    public void setProjects(List<Path> roots) {
        this.projects = roots == null ? List.of() : List.copyOf(roots);
        rebuildProjects();
    }

    /** Re-renders when the Projects section's content changed; a no-op while the tree holds nothing. */
    private void rebuildProjects() {
        if (provider != null || !projects.isEmpty()) {
            rendered = null; // the section list changed in a way the provider's sections do not show
            rebuild();
        }
    }

    /** The reason the build file could not be parsed, with a link that opens it; {@code null} clears it. */
    public void setNotice(String message, String linkText, Runnable onLink) {
        if (message == null || message.isBlank()) {
            notice.setVisible(false);
            return;
        }
        noticeLabel.setText(message);
        noticeLink.setText(linkText == null ? "" : linkText);
        noticeLink.setVisible(onLink != null && linkText != null);
        noticeLink.setManaged(noticeLink.isVisible());
        noticeLink.setOnAction(e -> {
            if (onLink != null) {
                onLink.run();
            }
        });
        notice.setVisible(true);
    }

    /** A root's name for the header and the Projects rows: relative to the project when inside it. */
    static String displayName(Path root, Path project) {
        Path r = root.toAbsolutePath().normalize();
        if (project != null) {
            Path p = project.toAbsolutePath().normalize();
            if (r.equals(p)) {
                Path name = p.getFileName();
                return name == null ? p.toString() : name.toString();
            }
            if (r.startsWith(p)) {
                return p.relativize(r).toString().replace(java.io.File.separatorChar, '/');
            }
        }
        Path name = r.getFileName();
        return name == null ? r.toString() : name.toString();
    }

    /** Adds a tool-specific toolbar action (Gradle's "Load all tasks…"), appended to the toolbar. */
    public void setSecondaryAction(String label, Runnable action) {
        if (secondaryButton != null) {
            toolbar.getChildren().remove(secondaryButton);
        }
        secondaryButton = iconButton(Icons.tools(), label, action);
        toolbar.getChildren().add(secondaryButton);
    }

    public void setOnRun(RunHandler onRun) {
        this.onRun = onRun;
    }

    public void setOnRefresh(Runnable onRefresh) {
        this.onRefresh = onRefresh;
    }

    public void setOnRunCustom(Runnable onRunCustom) {
        this.onRunCustom = onRunCustom;
    }

    public void setOnStop(Runnable onStop) {
        this.onStop = onStop;
    }

    /**
     * Mirrors whether a build is currently running: Stop is live only then, and Run is greyed meanwhile (Maven
     * review B13) — a double-click or Enter on a row still reaches the coordinator, which offers to stop the
     * running build and start the new one.
     */
    public void setRunning(boolean running) {
        stopButton.setDisable(!running);
        runButton.setDisable(running);
    }

    /**
     * Rebuilds the tree from {@code provider}'s current sections (given the active toggles). {@code null}
     * clears it to a placeholder (marker absent or malformed). Called on every detection change and after a
     * toggle flip / on-demand task load.
     *
     * <p>Two things this must get right, because {@code BuildCoordinator.refresh()} calls it on every save,
     * tab switch, window focus-regain and settings apply — with a <em>freshly parsed</em> provider each time:
     *
     * <ul>
     *   <li><b>Stale toggles are dropped.</b> {@code activeToggles} holds raw ids and {@code toggleArgs} joins
     *       whatever is in it, so a profile that disappeared from the pom (or one carried over when the window
     *       moves to a different project) would keep appending e.g. {@code -Pprod} to every run — invisibly,
     *       since its checkbox row no longer exists.</li>
     *   <li><b>An unchanged tree isn't re-rooted.</b> Swapping the root discards the selection and scroll
     *       position, so a background re-detect would silently deselect the task the user just clicked.</li>
     * </ul>
     */
    public void setProvider(BuildActionsProvider provider) {
        setProvider(provider, null);
    }

    /**
     * As {@link #setProvider(BuildActionsProvider)}, but with the message to show when there is nothing to
     * render. A null provider means either "no build file here" or "the build file is broken" — the tree used
     * to say "No build tool detected" for both, which is actively wrong for the second: the stripe is only
     * visible *because* a marker was found.
     */
    public void setProvider(BuildActionsProvider provider, String emptyMessage) {
        this.provider = provider;
        this.emptyMessage = emptyMessage;
        if (provider == null) {
            activeToggles.clear();
            rendered = null;
            setNotice(null, null, null);
            rebuild();
            return;
        }
        List<BuildAction.Section> sections = provider.sections(activeToggles);
        if (activeToggles.retainAll(toggleIds(sections))) {
            sections = provider.sections(activeToggles); // re-query without the stale ids
        }
        if (sections.equals(rendered) && filterQuery().equals(renderedFilter) && tree.getRoot() != null) {
            return; // same tasks + same filter as what's on screen — keep the selection + scroll position
        }
        rebuild(sections);
    }

    /** The argv the active toggles add to a run ({@code -Pdev}, the reactor's {@code -f … -pl … -am}); empty with no provider. */
    public List<String> activeToggleArgs() {
        return provider == null ? List.of() : provider.toggleArgs(activeToggles);
    }

    /** Re-renders the current provider in place (after a toggle flip or Gradle's loaded tasks land). */
    public void refreshFromProvider() {
        rebuild();
    }

    /** The ids of every toggle the given sections expose (the only ones that may stay active). */
    private static Set<String> toggleIds(List<BuildAction.Section> sections) {
        Set<String> ids = new LinkedHashSet<>();
        for (BuildAction.Section section : sections) {
            for (BuildAction.Row row : section.rows()) {
                if (row instanceof BuildAction.Toggle t) {
                    ids.add(t.id());
                    if (t.triState()) {
                        ids.add(t.offId());
                    }
                }
            }
        }
        return ids;
    }

    private void rebuild() {
        rebuild(provider == null ? null : provider.sections(activeToggles));
    }

    private void rebuild(List<BuildAction.Section> sections) {
        String query = filterQuery();
        boolean detected = provider != null && sections != null;
        List<BuildAction.Section> visible = !detected || query.isEmpty() ? sections : filtered(sections, query);
        boolean hasProjects = !projects.isEmpty() && query.isEmpty();
        boolean has = (detected && !visible.isEmpty()) || hasProjects;
        // "No matching tasks" only when a filter is what emptied the tree — otherwise the marker-absent /
        // build-file-broken message the caller gave us.
        placeholder.setText(
                detected && !query.isEmpty()
                        ? tr("buildtree.noMatches")
                        : (emptyMessage != null ? emptyMessage : tr("buildtree.empty")));
        placeholder.setVisible(!has);
        tree.setVisible(has);
        rendered = detected ? List.copyOf(sections) : null;
        renderedFilter = query;
        if (!has) {
            tree.setRoot(null);
            return;
        }
        TreeItem<Item> root = new TreeItem<>(null);
        if (hasProjects) {
            // The build roots found below the open project — a workspace whose poms sit one folder down
            // showed no build tool at all until a file inside a module was opened (review B11).
            TreeItem<Item> projectsItem = new TreeItem<>(new SectionItem(tr("buildtree.projects"), ""));
            projectsItem.setExpanded(true);
            for (Path p : projects) {
                boolean current = currentRoot != null
                        && p.toAbsolutePath()
                                .normalize()
                                .equals(currentRoot.toAbsolutePath().normalize());
                projectsItem
                        .getChildren()
                        .add(new TreeItem<>(new ProjectItem(p, displayName(p, projectRoot), current)));
            }
            root.getChildren().add(projectsItem);
        }
        for (BuildAction.Section section : detected ? visible : List.<BuildAction.Section>of()) {
            TreeItem<Item> sectionItem = new TreeItem<>(new SectionItem(section.title(), section.tooltip()));
            // A collapsed-by-default group (Maven's per-plugin goal lists) still expands while a filter is
            // active — a match the user just typed for must not stay hidden inside a folded node.
            sectionItem.setExpanded(!section.collapsed() || !query.isEmpty());
            for (BuildAction.Row row : section.rows()) {
                Item item = row instanceof BuildAction.Task task
                        ? new TaskItem(task)
                        : new ToggleItem((BuildAction.Toggle) row);
                sectionItem.getChildren().add(new TreeItem<>(item));
            }
            root.getChildren().add(sectionItem);
        }
        tree.setRoot(root);
    }

    /** The filter text, normalized (lower-cased + stripped); empty when nothing is being filtered. */
    private String filterQuery() {
        return BuildActionsFilter.normalize(filterField.getText());
    }

    /** {@code sections} narrowed to what matches {@code query}; see {@link BuildActionsFilter#filtered}. */
    static List<BuildAction.Section> filtered(List<BuildAction.Section> sections, String query) {
        return BuildActionsFilter.filtered(sections, query);
    }

    private void runSelected() {
        TreeItem<Item> selected = tree.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (selected.getValue() instanceof TaskItem taskItem) {
            onRun.run(taskItem.task().args(), provider == null ? List.of() : provider.toggleArgs(activeToggles));
        } else if (selected.getValue() instanceof ProjectItem p) {
            onSelectProject.accept(p.root());
        }
    }

    private void toggle(BuildAction.Toggle toggle, boolean on) {
        if (on) {
            activeToggles.add(toggle.id());
        } else {
            activeToggles.remove(toggle.id());
        }
        if (toggle.triState()) {
            activeToggles.remove(toggle.offId());
        }
        rebuild(); // sections() is re-queried so a checked toggle can reveal extra rows
    }

    /**
     * A tri-state toggle's states, in click order: off → on ({@code -Pid}) → explicitly off ({@code -P!id})
     * → off. The explicit state is what deactivates a profile the pom or the environment would activate.
     */
    enum ToggleState {
        OFF,
        ON,
        EXPLICIT_OFF
    }

    static ToggleState stateOf(Set<String> active, BuildAction.Toggle toggle) {
        if (active.contains(toggle.id())) {
            return ToggleState.ON;
        }
        return toggle.triState() && active.contains(toggle.offId()) ? ToggleState.EXPLICIT_OFF : ToggleState.OFF;
    }

    /** Advances {@code toggle} to its next state and re-queries the provider. */
    private void cycle(BuildAction.Toggle toggle) {
        ToggleState next = switch (stateOf(activeToggles, toggle)) {
            case OFF -> ToggleState.ON;
            case ON -> toggle.triState() ? ToggleState.EXPLICIT_OFF : ToggleState.OFF;
            case EXPLICIT_OFF -> ToggleState.OFF;
        };
        activeToggles.remove(toggle.id());
        activeToggles.remove(toggle.offId());
        if (next == ToggleState.ON) {
            activeToggles.add(toggle.id());
        } else if (next == ToggleState.EXPLICIT_OFF) {
            activeToggles.add(toggle.offId());
        }
        rebuild();
    }

    private Button iconButton(javafx.scene.Node icon, String tooltip, Runnable action) {
        return Icons.toolbarButton(icon, tooltip, action, "button-icon", "flat", "toolbar-button");
    }

    @Override
    public void focusFirstItem() {
        // Land on the filter field so the task can be typed for immediately; Down/Enter move into / run the
        // results (FilterFieldNav), as in the Project / Bookmarks / Notes windows.
        if (tree.getRoot() != null && !tree.getRoot().getChildren().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
        filterField.requestFocus();
    }

    /** Moves keyboard focus into the panel (the filter field), for window-switching. */
    public void focusContent() {
        filterField.requestFocus();
    }

    // --- keyboard navigation (mirrors BookmarksPanel / StructurePanel) ---

    /**
     * Emacs navigation inside the tree: {@code C-n}/{@code C-p} move, {@code C-f}/{@code C-b} expand a
     * section or step out of it, and {@code Enter}/{@code C-m} runs the selected task. The panel marks
     * itself {@code editora.ownsKeys}, so the global dispatcher leaves these chords to it — without a
     * handler here they were simply swallowed. Plain arrows stay with the {@link TreeView} itself.
     */
    private void onTreeKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER) {
            runSelected();
            e.consume();
            return;
        }
        if (!e.isControlDown()) {
            return;
        }
        switch (e.getCode()) {
            case N -> {
                move(1);
                e.consume();
            }
            case P -> {
                move(-1);
                e.consume();
            }
            case F -> {
                expandOrDescend();
                e.consume();
            }
            case B -> {
                collapseOrAscend();
                e.consume();
            }
            case M -> {
                runSelected();
                e.consume();
            }
            default -> {}
        }
    }

    /** Moves the selection by {@code delta} rows, wrapping (the BookmarksPanel behavior). */
    private void move(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int idx = tree.getSelectionModel().getSelectedIndex();
        int next = idx < 0 ? (delta > 0 ? 0 : rows - 1) : Math.floorMod(idx + delta, rows);
        tree.getSelectionModel().select(next);
        tree.scrollTo(next);
    }

    private void expandOrDescend() {
        TreeItem<Item> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
        } else {
            move(1);
        }
    }

    private void collapseOrAscend() {
        TreeItem<Item> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            move(-1);
            return;
        }
        if (!item.isLeaf() && item.isExpanded()) {
            item.setExpanded(false);
        } else if (item.getParent() != null && item.getParent() != tree.getRoot()) {
            tree.getSelectionModel().select(item.getParent());
            tree.scrollTo(tree.getSelectionModel().getSelectedIndex());
        } else {
            move(-1);
        }
    }

    /** Tree cell: a bold section header, a runnable task (label + tooltip), a checkbox toggle, or a project. */
    private final class ItemCell extends TreeCell<Item> {
        private final CheckBox check = new CheckBox();

        ItemCell() {
            check.setOnAction(e -> {
                if (getItem() instanceof ToggleItem t) {
                    // A plain toggle follows the box (on = checked); a tri-state one cycles its three states.
                    if (t.toggle().triState()) {
                        cycle(t.toggle());
                    } else {
                        toggle(t.toggle(), check.isSelected());
                    }
                }
            });
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass()
                    .removeAll("build-tasks-section", "build-tasks-task", "build-tasks-toggle", "build-tasks-project");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            switch (item) {
                case SectionItem s -> {
                    getStyleClass().add("build-tasks-section");
                    setGraphic(null);
                    setText(s.title());
                    setTooltip(s.tooltip() == null || s.tooltip().isEmpty() ? null : new Tooltip(s.tooltip()));
                }
                case TaskItem t -> {
                    getStyleClass().add("build-tasks-task");
                    setGraphic(null);
                    setText(t.task().label());
                    String tip = t.task().tooltip();
                    setTooltip(tip == null || tip.isEmpty() ? null : new Tooltip(tip));
                }
                case ToggleItem tg -> {
                    getStyleClass().add("build-tasks-toggle");
                    setText(null);
                    setTooltip(null);
                    BuildAction.Toggle toggle = tg.toggle();
                    String note = toggle.note();
                    check.setText(note == null || note.isEmpty() ? toggle.label() : toggle.label() + " " + note);
                    // A locked toggle (a settings.xml profile) is on whatever the argv says: checked, greyed.
                    check.setDisable(toggle.locked());
                    ToggleState state = toggle.locked() ? ToggleState.ON : stateOf(activeToggles, toggle);
                    check.setAllowIndeterminate(false);
                    check.setIndeterminate(state == ToggleState.EXPLICIT_OFF);
                    check.setSelected(state == ToggleState.ON);
                    check.setTooltip(
                            toggle.triState() && !toggle.locked() ? new Tooltip(tr("buildtree.triStateTip")) : null);
                    setGraphic(check);
                }
                case ProjectItem p -> {
                    getStyleClass().add("build-tasks-project");
                    setGraphic(null);
                    setText(p.current() ? tr("buildtree.projectCurrent", p.label()) : p.label());
                    setTooltip(new Tooltip(p.root().toAbsolutePath().normalize().toString()));
                }
            }
        }
    }
}
