package com.editora.ui;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.run.StackTraceLinks;
import com.editora.test.TestCounts;
import com.editora.test.TestNode;
import com.editora.test.TestNodeKind;
import com.editora.test.TestRun;
import com.editora.test.TestStatus;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * The "Test Results" tool window — an IntelliJ-style test runner. A header (status + progress bar + pass/fail/
 * skip counts + elapsed timer) and a small icon toolbar (rerun / rerun-failed / stop / show-passed) sit above a
 * {@link SplitPane}: a {@link TreeView} of suites → tests (status icon + name + muted duration) on the left, a
 * read-only detail console on the right showing the selected test's failure message / stack trace (with
 * clickable frames, via {@link RunPanel#installLinkClicks}) and captured output. The coordinator drives it on
 * the FX thread; tree updates reuse the same {@link TestNode} instances and only repaint visible cells
 * ({@link TreeView#refresh()}), so a live run stays cheap and keeps expansion + selection.
 */
final class TestRunnerPanel extends VBox implements ToolWindowContent {

    private final Label status = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label passedChip = chip("test-chip-pass");
    private final Label failedChip = chip("test-chip-fail");
    private final Label skippedChip = chip("test-chip-skip");
    private final Label elapsed = new Label();

    private final Button rerunButton = iconButton(Icons.run(), "testrunner.rerun");
    private final Button rerunFailedButton = iconButton(Icons.testFailed(), "testrunner.rerunFailed");
    private final Button stopButton = iconButton(Icons.stopSquare(), "testrunner.stop");
    private final ToggleButton showPassedToggle = new ToggleButton();
    private final ToggleButton followToggle = new ToggleButton();

    private final TreeItem<TestNode> rootItem = new TreeItem<>(null);
    private final TreeView<TestNode> tree = new TreeView<>(rootItem);
    private final CodeArea detail = new CodeArea();

    /** Same-instance {@link TestNode} → its tree item, so live updates never rebuild or duplicate the tree. */
    private final Map<TestNode, TreeItem<TestNode>> items = new IdentityHashMap<>();

    private TestNode lastRoot;
    private TestRun lastRun; // for the no-selection run summary in the detail pane
    private String runLabel = "";
    private Runnable onRerun;
    private Runnable onRerunFailed;
    private Runnable onStop;
    private Consumer<TestNode> onActivate;
    private Consumer<TestNode> onRerunOne;
    private Consumer<TestNode> onDebugOne;
    private java.util.function.BooleanSupplier debugAvailable = () -> false;
    private Consumer<StackTraceLinks.Link> onLink;

    TestRunnerPanel() {
        getStyleClass().add("test-runner");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(6);
        setPadding(new Insets(6));

        status.getStyleClass().add("test-status");
        progress.getStyleClass().add("test-progress");
        elapsed.getStyleClass().add("test-elapsed");

        rerunButton.setOnAction(e -> run(onRerun));
        rerunFailedButton.setOnAction(e -> run(onRerunFailed));
        stopButton.setOnAction(e -> run(onStop));
        showPassedToggle.setGraphic(Icons.testPassed());
        showPassedToggle.getStyleClass().add("toolbar-restore");
        showPassedToggle.setSelected(true);
        showPassedToggle.setTooltip(new Tooltip(tr("testrunner.showPassed")));
        showPassedToggle.setOnAction(e -> rebuild());
        // "Track running test": keep the newest result scrolled into view so a long run reads as live
        // progress. Auto-disabled the moment the user selects a row (they're inspecting — don't yank the view).
        followToggle.setGraphic(Icons.arrowDown());
        followToggle.getStyleClass().add("toolbar-restore");
        followToggle.setSelected(true);
        followToggle.setTooltip(new Tooltip(tr("testrunner.followRunning")));

        HBox toolbar = new HBox(2, rerunButton, rerunFailedButton, stopButton, showPassedToggle, followToggle);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox header = new HBox(10, status, progress, passedChip, failedChip, skippedChip, spacer(), elapsed, toolbar);
        header.setAlignment(Pos.CENTER_LEFT);

        tree.getStyleClass().add("test-tree");
        tree.setShowRoot(false);
        rootItem.setExpanded(true);
        tree.setCellFactory(t -> new TestTreeCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            showDetail(sel == null ? null : sel.getValue());
            if (sel != null) {
                // Selection only ever changes by user action here (we never select programmatically), so this
                // means "I'm reading this one" — stop auto-scrolling out from under them.
                followToggle.setSelected(false);
            }
        });
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                activateSelected();
            }
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                activateSelected();
            }
        });
        installContextMenu();

        detail.setEditable(false);
        detail.setWrapText(false);
        detail.getStyleClass().addAll("editor-area", "test-detail");
        RunPanel.installLinkClicks(detail, () -> onLink);

        SplitPane split = new SplitPane(tree, new VirtualizedScrollPane<>(detail));
        split.setDividerPositions(0.5);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(header, split);
        idle();
    }

    void setOnRerun(Runnable r) {
        this.onRerun = r;
    }

    void setOnRerunFailed(Runnable r) {
        this.onRerunFailed = r;
    }

    void setOnStop(Runnable r) {
        this.onStop = r;
    }

    void setOnActivate(Consumer<TestNode> c) {
        this.onActivate = c;
    }

    void setOnRerunOne(Consumer<TestNode> c) {
        this.onRerunOne = c;
    }

    void setOnDebugOne(Consumer<TestNode> c) {
        this.onDebugOne = c;
    }

    /** Whether "Debug Test" is offered (Java debugging configured + a JVM build tool). */
    void setDebugAvailable(java.util.function.BooleanSupplier available) {
        this.debugAvailable = available == null ? () -> false : available;
    }

    void setOnLink(Consumer<StackTraceLinks.Link> c) {
        this.onLink = c;
    }

    /** Matches the detail console font to the editor's code-area font (family + effective size). */
    void setOutputFont(String family, int size) {
        detail.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
    }

    /** No run yet / cleared. */
    void idle() {
        status.setText(tr("testrunner.idle"));
        progress.setProgress(0);
        setChips(TestCounts.ZERO);
        elapsed.setText("");
        rerunButton.setDisable(onRerun == null);
        rerunFailedButton.setDisable(true);
        stopButton.setDisable(true);
    }

    /** A run started: reset the tree/detail/counts and show the running state. */
    void startRun(String label) {
        items.clear();
        rootItem.getChildren().clear();
        lastRoot = null;
        lastRun = null;
        runLabel = label == null ? "" : label;
        status.setText(tr("testrunner.running", runLabel));
        setChips(TestCounts.ZERO);
        progress.setProgress(0);
        progress.getStyleClass().remove("test-progress-failed");
        rerunButton.setDisable(true);
        rerunFailedButton.setDisable(true);
        stopButton.setDisable(false);
        followToggle.setSelected(true); // a fresh run tracks again, even if the last one was pinned by a click
        showRunSummary(); // the detail pane shows the run header until a test is selected
    }

    /** Live update: sync the tree from the (mutated-in-place) root and refresh the header counts/progress. */
    void update(TestRun run) {
        update(run, null);
    }

    /**
     * As {@link #update(TestRun)}, plus {@code follow} — the test whose result just arrived (the run's
     * frontier). While the run is live and "track running test" is on, it is scrolled into view so the user
     * watches progress in real time.
     */
    void update(TestRun run, TestNode follow) {
        update0(run);
        revealFollow(run, follow);
    }

    private void update0(TestRun run) {
        lastRun = run;
        sync(run.root());
        TestCounts counts = run.counts();
        setChips(counts);
        int total = counts.total();
        progress.setProgress(total == 0 ? 0 : (double) counts.finished() / total);
        setProgressFailed(counts.anyFailed());
        if (tree.getSelectionModel().getSelectedItem() == null) {
            showRunSummary(); // keep the run summary live while nothing is selected
        }
    }

    /**
     * Scrolls the run's newest result into view (JavaFX only moves the viewport when the row isn't already
     * fully visible, so a steady run doesn't jitter). No-op once the run finishes, when the user turned
     * tracking off, or when the row is filtered out by "show passed".
     */
    private void revealFollow(TestRun run, TestNode follow) {
        if (follow == null || !followToggle.isSelected() || !run.isRunning()) {
            return;
        }
        TreeItem<TestNode> item = items.get(follow);
        if (item == null) {
            return;
        }
        for (TreeItem<TestNode> p = item.getParent(); p != null; p = p.getParent()) {
            p.setExpanded(true);
        }
        int row = tree.getRow(item);
        if (row >= 0) {
            tree.scrollTo(row);
        }
    }

    void setElapsed(long ms) {
        elapsed.setText(formatElapsed(ms));
    }

    /** The run finished: final header + enable rerun / rerun-failed, disable stop. */
    void finishRun(TestRun run, int exitCode) {
        update(run);
        TestCounts counts = run.counts();
        setElapsed(run.elapsedMillis(0)); // finished → elapsedMillis ignores the arg and uses the stored finish time
        status.setText(
                counts.anyFailed()
                        ? tr("testrunner.finishedFailed", counts.failedOrErrored(), counts.total())
                        : tr("testrunner.finishedOk", counts.passed(), counts.total()));
        rerunButton.setDisable(onRerun == null);
        rerunFailedButton.setDisable(!counts.anyFailed() || onRerunFailed == null);
        stopButton.setDisable(true);
        refreshDetail(); // a selected node's header flips from Running to its settled status
    }

    private void refreshDetail() {
        TreeItem<TestNode> sel = tree.getSelectionModel().getSelectedItem();
        showDetail(sel == null ? null : sel.getValue());
    }

    // --- tree sync ---------------------------------------------------------------------------------

    private void sync(TestNode root) {
        if (root == null) {
            return;
        }
        lastRoot = root;
        boolean showPassed = showPassedToggle.isSelected();
        for (TestNode suite : root.children()) {
            TreeItem<TestNode> suiteItem = items.get(suite);
            if (suiteItem == null) {
                suiteItem = new TreeItem<>(suite);
                suiteItem.setExpanded(true);
                items.put(suite, suiteItem);
                rootItem.getChildren().add(suiteItem);
            }
            for (TestNode test : suite.children()) {
                boolean visible = showPassed || test.status() != TestStatus.PASSED;
                TreeItem<TestNode> testItem = items.get(test);
                if (visible && testItem == null) {
                    testItem = new TreeItem<>(test);
                    items.put(test, testItem);
                    suiteItem.getChildren().add(testItem);
                } else if (!visible && testItem != null) {
                    suiteItem.getChildren().remove(testItem);
                    items.remove(test);
                }
            }
        }
        tree.refresh();
    }

    /** Full rebuild (used when the show-passed filter flips, so removed rows re-appear in tree order). */
    private void rebuild() {
        TestNode root = lastRoot;
        items.clear();
        rootItem.getChildren().clear();
        if (root != null) {
            sync(root);
        }
    }

    /**
     * Right-click a test/class row: jump to its source, rerun just it, or debug it. Rebuilt per request so the
     * items reflect the clicked node (and so "Debug" is only offered when Java debugging is actually usable).
     */
    private void installContextMenu() {
        ContextMenu menu = new ContextMenu();
        tree.setOnContextMenuRequested(e -> {
            TreeItem<TestNode> sel = tree.getSelectionModel().getSelectedItem();
            TestNode node = sel == null ? null : sel.getValue();
            if (node == null || node.className() == null) {
                menu.hide();
                return;
            }
            boolean isTest = node.kind() == TestNodeKind.TEST;
            menu.getItems().clear();

            MenuItem goTo = new MenuItem(isTest ? tr("testrunner.menu.goToTest") : tr("testrunner.menu.goToClass"));
            goTo.setGraphic(Icons.find());
            goTo.setOnAction(a -> {
                if (onActivate != null) {
                    onActivate.accept(node);
                }
            });

            MenuItem rerun = new MenuItem(isTest ? tr("testrunner.menu.rerunTest") : tr("testrunner.menu.rerunClass"));
            rerun.setGraphic(Icons.run());
            rerun.setOnAction(a -> {
                if (onRerunOne != null) {
                    onRerunOne.accept(node);
                }
            });

            menu.getItems().addAll(goTo, rerun);
            if (debugAvailable.getAsBoolean()) {
                MenuItem debug =
                        new MenuItem(isTest ? tr("testrunner.menu.debugTest") : tr("testrunner.menu.debugClass"));
                debug.setGraphic(Icons.debug());
                debug.setOnAction(a -> {
                    if (onDebugOne != null) {
                        onDebugOne.accept(node);
                    }
                });
                menu.getItems().add(debug);
            }
            menu.show(tree, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private void activateSelected() {
        TreeItem<TestNode> sel = tree.getSelectionModel().getSelectedItem();
        if (sel != null && sel.getValue() != null && sel.getValue().kind() == TestNodeKind.TEST && onActivate != null) {
            onActivate.accept(sel.getValue());
        }
    }

    // --- detail ------------------------------------------------------------------------------------

    private void showDetail(TestNode node) {
        if (node == null) {
            showRunSummary();
            return;
        }
        StringBuilder sb = new StringBuilder();
        // Fully-qualified name first: a bare method name isn't enough to know *which* test you're looking at.
        String fq = node.className() == null || node.methodName() == null
                ? node.displayName()
                : node.className() + "." + node.methodName();
        sb.append(fq).append('\n').append(statusLabel(node.status()));
        if (node.durationMs() > 0) {
            sb.append(" (").append(node.durationMs()).append(" ms)");
        }
        sb.append('\n');
        if (node.kind() != TestNodeKind.TEST) {
            TestCounts c = node.tally();
            sb.append(tr("testrunner.detail.suiteSummary", c.total(), c.passed(), c.failedOrErrored(), c.skipped()))
                    .append('\n');
        }
        sb.append('\n');
        if (node.failureType() != null || node.failureMessage() != null) {
            if (node.failureType() != null) {
                sb.append(node.failureType());
                if (node.failureMessage() != null) {
                    sb.append(": ");
                }
            }
            if (node.failureMessage() != null) {
                sb.append(node.failureMessage());
            }
            sb.append("\n\n");
        }
        if (node.stackTrace() != null && !node.stackTrace().equals(node.failureMessage())) {
            sb.append(node.stackTrace()).append("\n");
        }
        boolean hadOutput = hasText(node.stdout()) || hasText(node.stderr());
        appendSection(sb, tr("testrunner.detail.stdout"), node.stdout());
        appendSection(sb, tr("testrunner.detail.stderr"), node.stderr());
        // A passing JVM test usually has no per-test capture (Surefire writes system-out at the CLASS level),
        // which used to leave the pane showing just "Passed" — fall back to the class's captured output.
        if (!hadOutput && node.kind() == TestNodeKind.TEST && node.parent() != null) {
            TestNode suite = node.parent();
            boolean classOut = hasText(suite.stdout()) || hasText(suite.stderr());
            if (classOut) {
                appendSection(sb, tr("testrunner.detail.classOutput"), suite.stdout());
                appendSection(sb, tr("testrunner.detail.classErrOutput"), suite.stderr());
            } else if (!hasText(node.failureMessage()) && !hasText(node.stackTrace())) {
                sb.append('\n').append(tr("testrunner.detail.noOutput")).append('\n');
            }
        }
        detail.replaceText(sb.toString().strip());
        detail.moveTo(0);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static void appendSection(StringBuilder sb, String title, String body) {
        if (body != null && !body.isBlank()) {
            sb.append("\n--- ").append(title).append(" ---\n").append(body).append("\n");
        }
    }

    /** The detail pane when no node is selected: the run command + counts + elapsed. */
    private void showRunSummary() {
        if (lastRun == null) {
            detail.replaceText(runLabel.isBlank() ? "" : tr("testrunner.detail.runHeader", runLabel));
            detail.moveTo(0);
            return;
        }
        TestCounts c = lastRun.counts();
        String text = tr("testrunner.detail.runHeader", runLabel) + "\n"
                + tr("testrunner.detail.suiteSummary", c.total(), c.passed(), c.failedOrErrored(), c.skipped()) + "\n"
                + tr("testrunner.detail.elapsed", formatElapsed(lastRun.elapsedMillis(System.currentTimeMillis())));
        detail.replaceText(text);
        detail.moveTo(0);
    }

    private static String statusLabel(TestStatus status) {
        return switch (status) {
            case PASSED -> tr("testrunner.detail.status.passed");
            case FAILED -> tr("testrunner.detail.status.failed");
            case ERROR -> tr("testrunner.detail.status.error");
            case SKIPPED -> tr("testrunner.detail.status.skipped");
            case RUNNING -> tr("testrunner.detail.status.running");
        };
    }

    // --- header helpers ----------------------------------------------------------------------------

    private void setChips(TestCounts counts) {
        passedChip.setText(tr("testrunner.chip.passed", counts.passed()));
        int failed = counts.failedOrErrored();
        failedChip.setText(tr("testrunner.chip.failed", failed));
        failedChip.setVisible(failed > 0);
        failedChip.setManaged(failed > 0);
        skippedChip.setText(tr("testrunner.chip.skipped", counts.skipped()));
        skippedChip.setVisible(counts.skipped() > 0);
        skippedChip.setManaged(counts.skipped() > 0);
    }

    private void setProgressFailed(boolean failed) {
        if (failed) {
            if (!progress.getStyleClass().contains("test-progress-failed")) {
                progress.getStyleClass().add("test-progress-failed");
            }
        } else {
            progress.getStyleClass().remove("test-progress-failed");
        }
    }

    private static String formatElapsed(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) {
            return String.format("%.1f s", ms / 1000.0);
        }
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static Label chip(String colorClass) {
        Label l = new Label();
        l.getStyleClass().addAll("test-chip", colorClass);
        return l;
    }

    private static Button iconButton(Node graphic, String tooltipKey) {
        Button b = new Button();
        b.setGraphic(graphic);
        b.getStyleClass().add("toolbar-restore"); // reuses the flat floating-button look
        b.setTooltip(new Tooltip(tr(tooltipKey)));
        return b;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private void run(Runnable r) {
        if (r != null) {
            r.run();
        }
    }

    @Override
    public void focusFirstItem() {
        tree.requestFocus();
        if (!rootItem.getChildren().isEmpty()) {
            tree.getSelectionModel().select(0);
        }
    }

    /** Renders a node as a status icon + name + (for a test) a muted duration. */
    private static final class TestTreeCell extends TreeCell<TestNode> {
        @Override
        protected void updateItem(TestNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            Label name = new Label(node.displayName());
            HBox row = new HBox(6, statusIcon(node.status()), name);
            row.setAlignment(Pos.CENTER_LEFT);
            if (node.kind() == TestNodeKind.TEST && node.durationMs() > 0) {
                Label dur = new Label(node.durationMs() + " ms");
                dur.getStyleClass().add("test-duration");
                row.getChildren().add(dur);
            }
            setGraphic(row);
        }

        private static Node statusIcon(TestStatus status) {
            return switch (status) {
                case PASSED -> Icons.testPassed();
                case FAILED, ERROR -> Icons.testFailed();
                case SKIPPED -> Icons.testSkipped();
                case RUNNING -> Icons.testRunning();
            };
        }
    }
}
