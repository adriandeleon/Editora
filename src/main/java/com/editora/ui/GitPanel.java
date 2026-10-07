package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.git.CommitMessages;
import com.editora.git.GitConflicts;
import com.editora.git.GitFileStatus;
import com.editora.git.GitNumstat;
import com.editora.git.GitOperation;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;

import static com.editora.i18n.Messages.tr;

/**
 * The Git (Commit) tool window: the active repository's changes grouped into <em>Conflicts</em>
 * (unmerged paths, with Resolve / Accept Ours / Accept Theirs / Mark Resolved), <em>Staged</em>,
 * <em>Changes</em> (unstaged), and <em>Untracked</em>, with stage/unstage/discard actions and a
 * commit message box. While a merge, rebase, cherry-pick or revert is in progress a banner above the list
 * names it and offers Continue, Skip (where git has one) and Abort. Mirrors {@link BookmarksPanel}'s structure (a {@link TreeView} of rows that
 * route mutations back through an {@link Actions} callback so the controller — which knows the repo
 * root and which files are open — performs the actual {@code git} calls off-thread).
 *
 * <p>It is purely a view: it never shells out itself. The controller pushes a {@link GitStatus} via
 * {@link #setStatus} after each refresh.
 */
public final class GitPanel extends VBox implements ToolWindowContent {

    /**
     * Mutations the panel asks the controller to perform (all by repo-relative path). The
     * stage/unstage/discard operations take a <em>list</em> because the tree is multi-select: the
     * controller runs one {@code git} invocation over every path (and therefore one refresh) rather
     * than N of them.
     */
    public interface Actions {
        void open(String repoRelativePath);

        void stage(List<String> paths);

        void unstage(List<String> paths);

        /**
         * Reverts local changes: {@code tracked} paths are checked out from the index/HEAD and
         * {@code untracked} ones are deleted. Either list may be empty; the controller confirms once
         * for the whole set.
         */
        void discard(List<String> tracked, List<String> untracked);

        void stageAll();

        /**
         * Commits the index with {@code message}. {@code onDone} must be called once, on the FX thread, with
         * whether a commit was made — until then the panel treats the commit as running.
         */
        void commit(String message, java.util.function.Consumer<Boolean> onDone);

        /**
         * Commits with the options of the Commit window: amend, sign-off, and a push afterwards. The default
         * is the plain {@link #commit(String, java.util.function.Consumer)}.
         */
        default void commit(CommitRequest request, java.util.function.Consumer<Boolean> onDone) {
            commit(request.message(), onDone);
        }

        /** Told once, from the panel's constructor, which panel these actions serve. */
        default void attached(GitPanel panel) {}

        /**
         * Amend was switched on: answers with the commit that would be amended, or {@code null} (having said
         * why) when there is none to amend — a branch with no commits, an operation in progress.
         */
        default void amendTarget(java.util.function.Consumer<GitService.HeadCommit> onResult) {
            onResult.accept(null);
        }

        /** Whether commits in this repository are signed off ({@code -s}); remembered for the session. */
        default boolean signOff() {
            return false;
        }

        default void setSignOff(boolean signOff) {}

        /** The messages recently committed in this repository, newest first. */
        default List<String> messageHistory() {
            return List.of();
        }

        /** Unstages everything that is staged. */
        default void unstageAll() {}

        void push();

        void refresh();

        /** Review all files on one side: staged changes or unstaged/untracked working changes. */
        void review(boolean staged);

        /** Show a diff for the row: {@code staged} → index↔HEAD, else worktree↔index. */
        void diff(String repoRelativePath, boolean staged);

        /** Opens the three-way resolver for a conflicted path. */
        default void resolve(String repoRelativePath) {}

        /** Resolves conflicted paths by taking one whole side ({@code ours}, else theirs) and staging it. */
        default void acceptSide(List<String> paths, boolean ours) {}

        /** Continues the merge / rebase / cherry-pick / revert in progress. */
        default void continueOperation() {}

        /** Skips the commit the operation in progress stopped at. */
        default void skipOperation() {}

        /** Aborts the operation in progress (the controller confirms first). */
        default void abortOperation() {}
    }

    /**
     * One commit as the Commit window asks for it.
     *
     * @param message the message as typed (stripped of leading and trailing white space)
     * @param amend replace the last commit instead of adding one
     * @param signOff add a {@code Signed-off-by} trailer ({@code -s})
     * @param push push the branch once the commit is made
     */
    public record CommitRequest(String message, boolean amend, boolean signOff, boolean push) {}

    /** Which group a file row sits under. */
    private enum Group {
        /** Unmerged paths: above everything else, because nothing can be committed until they are gone. */
        CONFLICTS("gitpanel.group.conflicts"),
        STAGED("gitpanel.group.staged"),
        MODIFIED("gitpanel.group.modified"),
        UNTRACKED("gitpanel.group.untracked");
        final String key;

        Group(String key) {
            this.key = key;
        }
    }

    private sealed interface Row permits GroupRow, FileRow {}

    private record GroupRow(Group group, int count) implements Row {}

    private record FileRow(Group group, FileEntry entry) implements Row {}

    private final Actions actions;
    private final TreeView<Row> tree = new TreeView<>();
    private final TextField filterField = new TextField();
    private final HBox filterBar;
    private final TextArea message = new TextArea();
    private final Button commitButton = new Button(tr("gitpanel.commit"));
    /** The secondary half of the Commit button: Commit and Push. */
    private final MenuItem commitAndPushItem = new MenuItem(tr("gitpanel.commitAndPush"));

    private final MenuButton commitMenu = new MenuButton();
    /** Replace the last commit instead of adding one; see {@link #setAmending}. */
    private final CheckBox amendCheck = new CheckBox(tr("gitpanel.amend"));

    private final CheckBox signOffCheck = new CheckBox(tr("gitpanel.signOff"));
    /** Names the commit being amended; under it, the warning when that commit is already on the upstream. */
    private final Label amendInfo = new Label();

    private final Label amendWarning = new Label();
    private final VBox amendBox = new VBox(2, amendInfo, amendWarning);
    /** The subject-length guide: advice next to the message box, never a reason to refuse a commit. */
    private final Label guideLabel = new Label();

    private final MenuButton historyButton = new MenuButton();
    /** The commit Amend is about to replace; null while Amend is off (or its commit is still being read). */
    private GitService.HeadCommit amending;
    /** The message this panel put in the box for an amend, taken out again if Amend is switched off untouched. */
    private String amendPrefill;
    /** The repository's commit template, put in an empty box; see {@link #setCommitTemplate}. */
    private GitService.CommitTemplate template = GitService.CommitTemplate.NONE;
    /** Lines added and deleted per file, when they have been read for the status on show. */
    private GitNumstat.Changes lineCounts = GitNumstat.Changes.NONE;

    private final Label branchLabel = new Label();
    /** Push indicator: "↑N" (commits to push), "↑ publish" (no upstream), or "✓ pushed". */
    private final Label aheadLabel = new Label();

    private Button pushButton;
    private final MenuButton reviewButton;
    private final MenuItem reviewWorkingItem;
    private final MenuItem reviewStagedItem;
    /** The menu currently on screen, hidden before showing the next one (each is built per right-click,
     *  since its contents depend on the live selection). */
    private ContextMenu openMenu;

    private Runnable onGenerateCommitMessage = () -> {};
    /** Shown only while {@link #setAiAvailable} says AI Actions is enabled + reachable; sits in its own
     *  thin toolbar row directly above the commit message box (not the repo-wide header). */
    private final Button aiCommitButton =
            iconButton(Icons.aiGenerate(), tr("gitpanel.aiCommitTip"), () -> onGenerateCommitMessage.run());

    /** Amend and Sign-off, the length guide, and the message helpers (recent messages, AI); it wraps. */
    private final WrapRow messageToolbar;

    /** The status last pushed by the controller, so a filter change can re-render without a fresh {@code
     *  git status} (filtering is a view, not a refresh). */
    private GitStatus lastStatus;

    /** Whether the last status has anything staged — with {@link #committing}, what enables Commit. */
    private boolean hasStaged;
    /** Unmerged paths in the last status: while there are any, nothing can be committed. */
    private int conflicts;
    /** The merge / rebase / cherry-pick / revert the repository is in the middle of. */
    private GitOperation operation = GitOperation.NONE;
    /** The merge message this panel put in the box itself; cleared again if the merge ends with it untouched. */
    private String prefilledMessage;

    private final Label operationLabel = new Label();
    private final Button continueButton = new Button(tr("gitpanel.operation.continue"));
    private final Button skipButton = new Button(tr("gitpanel.operation.skip"));
    private final Button abortButton = new Button(tr("gitpanel.operation.abort"));
    /**
     * The banner: what is in progress on a line of its own (it wraps — in a narrow dock it used to be cut
     * to "Merge in progress — …"), and under it the buttons that move the operation on.
     */
    private final VBox operationBanner =
            new VBox(4, operationLabel, new WrapRow(6, 4, continueButton, skipButton, abortButton));
    /** Holds the Commit button so a tooltip can explain it while it is disabled (a disabled node shows none). */
    private final StackPane commitRow = new StackPane(commitButton);

    private final Tooltip commitBlockedTip = new Tooltip();
    /** A commit is running (hooks can take minutes): no second one may start until it reports back. */
    private boolean committing;
    /** Groups the user collapsed; a status update rebuilds the rows but must not reopen them. */
    private final java.util.EnumSet<Group> collapsed = java.util.EnumSet.noneOf(Group.class);

    private final StackPane placeholderPane;
    private final Label placeholder = new Label(tr("gitpanel.placeholder"));
    private final Button cloneButton = new Button(tr("gitpanel.clone"));
    private Runnable onClone = () -> {};

    public GitPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("git-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        // Header: current branch (ellipsized, takes remaining width) + compact icon buttons on the right.
        branchLabel.getStyleClass().add("git-branch-label");
        branchLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(branchLabel, Priority.ALWAYS);
        // The branch name keeps a readable minimum; its full text is in the tooltip.
        branchLabel.setMinWidth(BRANCH_MIN_WIDTH);
        branchLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        Tooltip branchTip = new Tooltip();
        branchTip.textProperty().bind(branchLabel.textProperty());
        branchLabel.setTooltip(branchTip);
        // The push indicator is a few characters ("↑2 ↓1", "↑ publish") and is never cut: cut, it read "↑…".
        aheadLabel.setMinWidth(Region.USE_PREF_SIZE);
        aheadLabel.getStyleClass().add("git-ahead");
        Button stageAll = iconButton(Icons.stageAll(), tr("gitpanel.stageAllTip"), actions::stageAll);
        reviewWorkingItem = new MenuItem(tr("gitpanel.reviewWorking"));
        reviewWorkingItem.setOnAction(e -> actions.review(false));
        reviewStagedItem = new MenuItem(tr("gitpanel.reviewStaged"));
        reviewStagedItem.setOnAction(e -> actions.review(true));
        reviewButton = new MenuButton();
        reviewButton.setGraphic(Icons.diff());
        reviewButton.getItems().setAll(reviewWorkingItem, reviewStagedItem);
        reviewButton.getStyleClass().addAll("flat", "git-toolbar-button");
        reviewButton.setFocusTraversable(false);
        reviewButton.setAccessibleText(tr("gitpanel.reviewTip"));
        reviewButton.setTooltip(new Tooltip(tr("gitpanel.reviewTip")));
        reviewButton.setDisable(true);
        pushButton = iconButton(Icons.gitPush(), tr("gitpanel.pushTip"), actions::push);
        Button refresh = iconButton(Icons.refresh(), tr("gitpanel.refreshTip"), actions::refresh);
        HBox headerButtons = new HBox(2, reviewButton, stageAll, pushButton, refresh);
        headerButtons.setAlignment(Pos.CENTER_LEFT);
        // The branch name gives way first (down to BRANCH_MIN_WIDTH, its full text in the tooltip); when
        // even that does not make room — a long translation of "publish", a very narrow dock — the buttons
        // move to a second line instead of anything being cut to "…".
        WrapRow header = new WrapRow(2, 2, WrapRow.setGrow(branchLabel), aheadLabel, headerButtons);
        header.getStyleClass().add("git-toolbar");

        // Filter/search row, mirroring the Bookmarks / Personal Notes tool windows (same style classes, the
        // same Down/Enter + C-n/C-p hand-off into the results via FilterFieldNav).
        filterField.setPromptText(tr("gitpanel.filterPrompt"));
        filterField.getStyleClass().add("bookmarks-filter");
        // Where the list's own key is written down: on the field focus lands on, and for a screen reader on
        // the list itself. (Rows keep their tooltip for the path.)
        filterField.setTooltip(new Tooltip(tr("gitpanel.spaceHint")));
        tree.setAccessibleHelp(tr("gitpanel.spaceHint"));
        filterField.textProperty().addListener((o, w, n) -> renderFiles());
        FilterFieldNav.install(filterField, tree, this::openSelected);
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
        filterBar = new HBox(6, filterField, clearFilter);
        filterBar.getStyleClass().add("project-filter-bar");
        filterBar.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.getStyleClass().add("git-tree");
        tree.setCellFactory(t -> new GitCell());
        tree.addEventFilter(KeyEvent.KEY_PRESSED, this::onTreeKey);
        // Multi-select: Shift+Up/Down extends from the keyboard, Shift/Ctrl+click from the mouse, and the
        // context menu then acts on the whole selection (see actionTargets).
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        // Keyboard menu key / Shift+F10: the event targets the focused *TreeView* (cells aren't focusable),
        // so a cell handler never sees it — open the menu for the focused row instead. Mouse requests are
        // consumed by the cell, so they never reach here.
        tree.setOnContextMenuRequested(e -> {
            TreeItem<Row> focused =
                    tree.getFocusModel() == null ? null : tree.getFocusModel().getFocusedItem();
            if (focused != null && focused.getValue() != null) {
                showMenu(tree, focused, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        aiCommitButton.setVisible(false); // hidden until setAiAvailable(true) — off by default
        aiCommitButton.setManaged(false);
        amendCheck.getStyleClass().add("git-commit-option");
        amendCheck.setTooltip(new Tooltip(tr("gitpanel.amendTip")));
        amendCheck.setOnAction(e -> setAmending(amendCheck.isSelected()));
        signOffCheck.getStyleClass().add("git-commit-option");
        signOffCheck.setTooltip(new Tooltip(tr("gitpanel.signOffTip")));
        signOffCheck.setOnAction(e -> actions.setSignOff(signOffCheck.isSelected()));
        guideLabel.getStyleClass().add("git-message-guide");
        guideLabel.setMinWidth(Region.USE_PREF_SIZE);
        guideLabel.setTooltip(new Tooltip(tr("gitpanel.guide.tip")));
        historyButton.setGraphic(Icons.history());
        historyButton.getStyleClass().addAll("flat", "git-toolbar-button");
        historyButton.setFocusTraversable(false);
        historyButton.setAccessibleText(tr("gitpanel.historyTip"));
        historyButton.setTooltip(new Tooltip(tr("gitpanel.historyTip")));
        // Filled when it opens: the list changes with every commit and with the active repository. A
        // placeholder item keeps the button clickable while there is nothing to list.
        historyButton.getItems().setAll(new MenuItem(tr("gitpanel.historyEmpty")));
        historyButton.setOnShowing(e -> fillHistoryMenu());
        Region toolbarGap = new Region();
        toolbarGap.setMinWidth(0);
        messageToolbar = new WrapRow(
                8, 2, amendCheck, signOffCheck, WrapRow.setGrow(toolbarGap), historyButton, aiCommitButton, guideLabel);
        messageToolbar.getStyleClass().add("git-message-toolbar");
        amendInfo.getStyleClass().add("git-amend-info");
        amendInfo.setWrapText(true);
        amendInfo.setMinHeight(Region.USE_PREF_SIZE);
        amendWarning.getStyleClass().add("git-amend-warning");
        amendWarning.setWrapText(true);
        amendWarning.setMinHeight(Region.USE_PREF_SIZE);
        show(amendWarning, false);
        show(amendBox, false);

        message.setPromptText(tr("gitpanel.commitPrompt"));
        message.getStyleClass().add("git-commit-message");
        message.setWrapText(true);
        message.setPrefRowCount(3);
        // Ctrl/Cmd+Enter commits, like most Git UIs.
        message.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && (e.isControlDown() || e.isMetaDown())) {
                doCommit();
                e.consume();
            }
        });
        message.textProperty().addListener((o, was, now) -> updateGuide());
        commitButton.setMaxWidth(Double.MAX_VALUE);
        commitButton.setDefaultButton(false);
        commitButton.setOnAction(e -> doCommit(false));
        HBox.setHgrow(commitButton, Priority.ALWAYS);
        commitAndPushItem.setGraphic(Icons.gitPush());
        commitAndPushItem.setOnAction(e -> doCommit(true));
        commitMenu.getItems().setAll(commitAndPushItem);
        commitMenu.getStyleClass().add("git-commit-more");
        commitMenu.setAccessibleText(tr("gitpanel.commitMoreTip"));
        commitMenu.setTooltip(new Tooltip(tr("gitpanel.commitMoreTip")));
        commitRow.getChildren().setAll(new HBox(2, commitButton, commitMenu));
        updateGuide();

        operationBanner.getStyleClass().add("git-operation-banner");
        operationLabel.getStyleClass().add("git-operation-label");
        operationLabel.setWrapText(true);
        operationLabel.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE); // wrap, never clip a line
        continueButton.getStyleClass().add("git-operation-continue");
        continueButton.setOnAction(e -> actions.continueOperation());
        skipButton.getStyleClass().add("git-operation-skip");
        skipButton.setOnAction(e -> actions.skipOperation());
        abortButton.getStyleClass().addAll("git-operation-abort", "danger");
        abortButton.setOnAction(e -> actions.abortOperation());
        operationBanner.setVisible(false);
        operationBanner.setManaged(false);

        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        cloneButton.getStyleClass().add("flat");
        cloneButton.setOnAction(e -> onClone.run());
        VBox emptyBox = new VBox(8, placeholder, cloneButton);
        emptyBox.setAlignment(Pos.CENTER);
        placeholderPane = new StackPane(emptyBox);
        VBox.setVgrow(placeholderPane, Priority.ALWAYS);

        getChildren().setAll(placeholderPane);
        // Start with no repo until the controller pushes a status.
        getProperties().put("git.header", header);
        actions.attached(this);
    }

    /** A compact, legible icon button for the panel toolbar (graphic + tooltip, no truncated text). */
    private static Button iconButton(javafx.scene.Node icon, String tip, Runnable action) {
        return Icons.toolbarButton(icon, tip, action, "flat", "git-toolbar-button"); // tooltip + accessible name
    }

    /**
     * Commits — from the button and from Ctrl/Cmd+Enter alike, so the shortcut obeys the button's disabled
     * state: with nothing staged it used to run {@code git commit} anyway and end in a "Commit failed" dialog,
     * and during a slow hook a second press queued a second commit.
     */
    private void doCommit() {
        doCommit(false);
    }

    /** {@code push}: Commit and Push — the push follows only a commit that was made. */
    private boolean doCommit(boolean push) {
        String submitted = message.getText() == null ? "" : message.getText();
        String msg = submitted.strip();
        if (messageIsEmpty() || committing || commitButton.isDisabled()) {
            return false;
        }
        committing = true;
        updateCommitEnabled();
        boolean amend = amending != null;
        actions.commit(new CommitRequest(msg, amend, signOffCheck.isSelected(), push), committed -> {
            committing = false;
            if (committed) {
                // One amend per tick of the box: the next commit is an ordinary one again.
                amendPrefill = null;
                clearAmend();
            }
            updateCommitEnabled();
            // Clear only the message that was committed: text typed while the commit ran is the next one.
            if (committed && submitted.equals(message.getText())) {
                message.clear();
                applyTemplate();
            }
        });
        return true;
    }

    /**
     * Whether the box holds nothing git would commit: blank, or — in a repository with a commit template —
     * only the template's comment lines.
     */
    private boolean messageIsEmpty() {
        String text = message.getText() == null ? "" : message.getText();
        return template.configured()
                ? CommitMessages.strip(text, template.commentChar()).isEmpty()
                : text.isBlank();
    }

    private void updateCommitEnabled() {
        // A merge whose conflicts are all resolved can be concluded with nothing left staged (the result
        // equals HEAD), so it does not need staged changes; unmerged files block every commit — git refuses
        // it, and used to say so in an error dialog after the click.
        boolean concludesMerge = operation.kind() == GitOperation.Kind.MERGE;
        // An amend needs nothing staged either: rewording the last commit is the commonest reason for one.
        commitButton.setDisable(conflicts > 0 || committing || !(hasStaged || concludesMerge || amending != null));
        commitMenu.setDisable(commitButton.isDisabled());
        commitButton.setText(tr(amending != null ? "gitpanel.commitAmend" : "gitpanel.commit"));
        // Amend rewrites HEAD: not while a merge, rebase, cherry-pick or revert is using it.
        amendCheck.setDisable(committing || operation.inProgress() || conflicts > 0);
        if (conflicts > 0) {
            commitBlockedTip.setText(tr("gitpanel.commitBlockedConflicts", conflicts));
            Tooltip.install(commitRow, commitBlockedTip);
        } else {
            Tooltip.uninstall(commitRow, commitBlockedTip);
        }
    }

    /** The reason Commit is unavailable right now, or {@code ""}: what its tooltip says while it is disabled. */
    String commitBlockedReason() {
        return conflicts > 0 ? commitBlockedTip.getText() : "";
    }

    /**
     * Commits with the typed message exactly as the Commit button would. False — nothing started — when the
     * box is empty, a commit is running or the button is disabled.
     */
    public boolean commitNow() {
        return doCommit(false);
    }

    /** {@link #commitNow}, followed by a push when the commit is made ({@code git.commitAndPush}). */
    public boolean commitAndPushNow() {
        return doCommit(true);
    }

    // --- amend ---------------------------------------------------------------------------------------

    /** Whether the next commit replaces the last one. */
    public boolean isAmending() {
        return amendCheck.isSelected();
    }

    /**
     * Switches Amend on or off ({@code git.commitAmend}, the checkbox). On: the commit to amend is read, named
     * under the options, and its message put in the box when the box is empty (or holds only the template);
     * a commit already on the upstream gets a warning in place, not a dialog. Off: the message is taken out
     * again if it was not edited.
     */
    public void setAmending(boolean on) {
        amendCheck.setSelected(on);
        if (!on) {
            String current = message.getText() == null ? "" : message.getText();
            if (amendPrefill != null && amendPrefill.equals(current)) {
                message.clear();
                applyTemplate();
            }
            amendPrefill = null;
            clearAmend();
            updateCommitEnabled();
            return;
        }
        actions.amendTarget(head -> {
            if (!amendCheck.isSelected()) {
                return; // switched off again while the commit was being read
            }
            if (head == null) {
                clearAmend();
                updateCommitEnabled();
                return;
            }
            boolean first = amending == null;
            amending = head;
            amendInfo.setText(tr("gitpanel.amending", head.shortHash(), head.subject()));
            amendWarning.setText(head.pushed() ? tr("gitpanel.amendPushed", head.upstream()) : "");
            show(amendWarning, head.pushed());
            show(amendBox, true);
            String current = message.getText() == null ? "" : message.getText();
            if (first && (current.isBlank() || current.equals(template.text()))) {
                amendPrefill = head.message();
                setCommitMessage(amendPrefill);
            }
            updateCommitEnabled();
        });
    }

    private void clearAmend() {
        amending = null;
        amendCheck.setSelected(false);
        show(amendBox, false);
    }

    // --- message aids --------------------------------------------------------------------------------

    /**
     * The repository's {@code commit.template} (and comment character). The template is put in the box when
     * it is empty — or still holds the previous repository's untouched template — and its comment lines are
     * not part of the message: a box with nothing else in it cannot be committed.
     */
    public void setCommitTemplate(GitService.CommitTemplate next) {
        GitService.CommitTemplate previous = template;
        template = next == null ? GitService.CommitTemplate.NONE : next;
        String current = message.getText() == null ? "" : message.getText();
        if (previous.configured() && current.equals(previous.text()) && !current.equals(template.text())) {
            message.clear();
        }
        applyTemplate();
        updateGuide();
        updateCommitEnabled();
    }

    private void applyTemplate() {
        String current = message.getText() == null ? "" : message.getText();
        if (template.configured() && current.isBlank() && amending == null) {
            message.setText(template.text());
            message.positionCaret(0); // the first line is where the subject goes
        }
    }

    /** Puts {@code text} in the message box unless something is being written there; true when it was put. */
    public boolean restoreCommitMessage(String text) {
        String current = message.getText() == null ? "" : message.getText();
        if (!current.isBlank() && !current.equals(template.text())) {
            return false;
        }
        setCommitMessage(text);
        return true;
    }

    private void updateGuide() {
        String text = message.getText() == null ? "" : message.getText();
        CommitMessages.Guide guide = CommitMessages.guide(text, template.commentChar());
        guideLabel.getStyleClass().removeAll("git-guide-long", "git-guide-too-long");
        if (guide.subjectLength() == 0) {
            guideLabel.setText("");
            return;
        }
        guideLabel.setText(
                guide.longBodyLines() > 0
                        ? tr("gitpanel.guide.subjectAndBody", guide.subjectLength(), guide.longBodyLines())
                        : tr("gitpanel.guide.subject", guide.subjectLength()));
        switch (guide.subject()) {
            case LONG -> guideLabel.getStyleClass().add("git-guide-long");
            case TOO_LONG -> guideLabel.getStyleClass().add("git-guide-too-long");
            case OK -> {
                if (guide.longBodyLines() > 0) {
                    guideLabel.getStyleClass().add("git-guide-long");
                }
            }
        }
    }

    /** Longest subject shown for a recent message in the dropdown. */
    private static final int HISTORY_LABEL_MAX = 60;

    private void fillHistoryMenu() {
        List<String> history = actions.messageHistory();
        if (history.isEmpty()) {
            MenuItem none = new MenuItem(tr("gitpanel.historyEmpty"));
            none.setDisable(true);
            historyButton.getItems().setAll(none);
            return;
        }
        List<MenuItem> items = new ArrayList<>(history.size());
        for (String entry : history) {
            String subject = CommitMessages.subject(entry);
            MenuItem item = new MenuItem(
                    subject.length() > HISTORY_LABEL_MAX ? subject.substring(0, HISTORY_LABEL_MAX - 1) + "…" : subject);
            item.setMnemonicParsing(false); // a subject is text: "fix_a" must not lose its underscore
            item.setOnAction(e -> {
                setCommitMessage(entry);
                message.requestFocus();
            });
            items.add(item);
        }
        historyButton.getItems().setAll(items);
    }

    /** Shows the lines added and deleted next to each staged and changed file ({@code NONE} hides them). */
    public void setLineCounts(GitNumstat.Changes counts) {
        lineCounts = counts == null ? GitNumstat.Changes.NONE : counts;
        tree.refresh();
    }

    /** Whether the panel is on screen — reads only worth making for a visible list are skipped otherwise. */
    public boolean isOnScreen() {
        return getScene() != null && isVisible();
    }

    /**
     * Shows the operation the repository is in the middle of ({@link GitOperation#NONE} hides the banner
     * unless files are unmerged). Called before {@link #setStatus} on every refresh. A merge brings its
     * prepared message: it is put in the commit box when the box is empty, because a merge commit should
     * say what was merged, and taken out again if the merge ends without the user having touched it.
     */
    public void setOperation(GitOperation next) {
        GitOperation previous = operation;
        operation = next == null ? GitOperation.NONE : next;
        boolean merging = operation.kind() == GitOperation.Kind.MERGE;
        String current = message.getText() == null ? "" : message.getText();
        if (merging) {
            // Offered once per merge, on the refresh that first sees it: a user who deletes the text must
            // not find it back after the next status refresh.
            if (previous.kind() != GitOperation.Kind.MERGE
                    && !operation.message().isEmpty()
                    && current.isBlank()) {
                prefilledMessage = operation.message();
                setCommitMessage(prefilledMessage);
            }
        } else if (previous.kind() == GitOperation.Kind.MERGE) {
            if (prefilledMessage != null && prefilledMessage.equals(current)) {
                message.clear();
            }
            prefilledMessage = null;
        }
        updateBanner();
        updateCommitEnabled();
    }

    /**
     * Git's reason for refusing to work in this folder ({@code ""} when there is none): the placeholder then
     * says that instead of "Not a Git repository", and does not offer to clone into a folder that already
     * holds a repository.
     */
    public void setRefusal(String reason) {
        boolean refused = reason != null && !reason.isBlank();
        placeholder.setText(refused ? tr("gitpanel.refused", reason) : tr("gitpanel.placeholder"));
        cloneButton.setVisible(!refused);
        cloneButton.setManaged(!refused);
    }

    private void updateBanner() {
        String name = GitCoordinator.operationName(operation.kind());
        if (operation.kind() == GitOperation.Kind.REBASE && operation.total() > 0) {
            name = tr("gitpanel.operation.step", name, operation.step(), operation.total());
        }
        String text;
        if (operation.inProgress()) {
            text = conflicts > 0
                    ? tr("gitpanel.operation.conflicts", name, conflicts)
                    : tr("gitpanel.operation.inProgress", name);
        } else {
            // Unmerged files with no operation: a stash pop or apply that conflicted. There is nothing to
            // continue or abort — resolving and staging the files is the whole of it.
            text = conflicts > 0 ? tr("gitpanel.conflictsOnly", conflicts) : "";
        }
        operationLabel.setText(text);
        show(continueButton, operation.canContinue());
        show(skipButton, operation.canSkip());
        show(abortButton, operation.canAbort());
        show(operationBanner, !text.isEmpty());
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** Sets the action run by the "Clone Repository…" button shown when there's no repo. */
    public void setOnClone(Runnable onClone) {
        this.onClone = onClone == null ? () -> {} : onClone;
    }

    /** Sets the action run by the header's "Generate Commit Message" (AI) button. */
    public void setOnGenerateCommitMessage(Runnable onGenerateCommitMessage) {
        this.onGenerateCommitMessage = onGenerateCommitMessage == null ? () -> {} : onGenerateCommitMessage;
    }

    /** Shows/hides the "Generate Commit Message" (AI) toolbar row above the message box — the effective
     *  gate (AI Actions enabled + a cached connectivity probe), pushed from the controller; never
     *  toggled per-selection/keystroke. */
    public void setAiAvailable(boolean available) {
        aiCommitButton.setVisible(available);
        aiCommitButton.setManaged(available);
    }

    /**
     * Rebuilds the panel from {@code status}. A {@code null} or non-repo status shows the
     * "Not a Git repository" placeholder and hides the commit UI.
     */
    public void setStatus(GitStatus status) {
        if (status == null || !status.isRepo()) {
            lastStatus = null;
            hasStaged = false;
            conflicts = 0;
            lineCounts = GitNumstat.Changes.NONE;
            if (amendCheck.isSelected()) {
                setAmending(false);
            }
            updateBanner();
            updateCommitEnabled();
            reviewButton.setDisable(true);
            reviewWorkingItem.setDisable(true);
            reviewStagedItem.setDisable(true);
            getChildren().setAll(placeholderPane);
            return;
        }
        lastStatus = status;
        branchLabel.setText("⎇ " + (status.branch().isBlank() ? tr("gitpanel.detached") : status.branch()));
        updatePushIndicator(status);

        // The commit affordances read the FULL status, never the filtered view: hiding a staged file behind
        // a filter must not disable Commit.
        hasStaged = status.files().stream().anyMatch(FileEntry::staged);
        conflicts = GitConflicts.unmerged(status).size();
        updateBanner();
        boolean hasWorking = status.files().stream().anyMatch(file -> file.unstaged() || file.untracked());
        reviewStagedItem.setDisable(!hasStaged);
        reviewWorkingItem.setDisable(!hasWorking);
        reviewButton.setDisable(!hasStaged && !hasWorking);
        signOffCheck.setSelected(actions.signOff());
        if (amendCheck.isSelected() && (operation.inProgress() || conflicts > 0)) {
            setAmending(false);
        } else if (amendCheck.isSelected()) {
            setAmending(true); // HEAD may have moved (a commit from a terminal): name the commit it is now
        }
        updateCommitEnabled();
        // Nothing to summarize without a staged diff — grey it out instead of silently no-op'ing on click.
        aiCommitButton.setDisable(!hasStaged);

        renderFiles();
    }

    /**
     * (Re)builds the file tree from {@link #lastStatus} through the current filter, and swaps in the right
     * body — the tree, the "nothing to commit" note, or a "no files match" note when a filter is what
     * emptied it. Runs on each status push and on every filter keystroke; the filter never re-runs
     * {@code git status}.
     */
    private void renderFiles() {
        if (lastStatus == null) {
            return;
        }
        Node header = (Node) getProperties().get("git.header");
        String query = filterQuery();
        // The rows are rebuilt on every status push — tab switch, save, window focus, each stage — so what
        // the user had in hand is carried across by row identity (group + path), in the SAME root: replacing
        // the root reset the scroll position, the selection and every collapsed group each time.
        TreeViewState kept = TreeViewState.capture(tree, GitPanel::rowKey);
        TreeItem<Row> root = tree.getRoot();
        if (root == null) {
            root = new TreeItem<>();
            tree.setRoot(root);
        }
        List<TreeItem<Row>> groups = new ArrayList<>(4);
        addGroup(groups, Group.CONFLICTS, matching(lastStatus.files().stream().filter(FileEntry::unmerged), query));
        addGroup(groups, Group.STAGED, matching(lastStatus.files().stream().filter(FileEntry::staged), query));
        addGroup(groups, Group.MODIFIED, matching(lastStatus.files().stream().filter(GitPanel::changed), query));
        addGroup(groups, Group.UNTRACKED, matching(lastStatus.files().stream().filter(FileEntry::untracked), query));
        root.getChildren().setAll(groups);
        kept.restore(tree, GitPanel::rowKey);

        if (root.getChildren().isEmpty()) {
            boolean filteredOut = !query.isEmpty() && !lastStatus.files().isEmpty();
            Label note = new Label(filteredOut ? tr("gitpanel.noMatches") : tr("gitpanel.clean"));
            note.getStyleClass().add("tool-window-placeholder");
            note.setWrapText(true);
            StackPane notePane = new StackPane(note);
            VBox.setVgrow(notePane, Priority.ALWAYS);
            // The filter bar stays even with nothing to show, or a filter that matches nothing would remove
            // the only control that can clear it.
            getChildren()
                    .setAll(header, operationBanner, filterBar, notePane, messageToolbar, amendBox, message, commitRow);
        } else {
            getChildren()
                    .setAll(header, operationBanner, filterBar, tree, messageToolbar, amendBox, message, commitRow);
        }
    }

    /** An unstaged change that is not a conflict: unmerged paths have a group of their own. */
    private static boolean changed(FileEntry entry) {
        return entry.unstaged() && !entry.unmerged();
    }

    /** The filter text, normalized (lower-cased + stripped); empty when nothing is being filtered. */
    private String filterQuery() {
        String text = filterField.getText();
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * {@code files} narrowed to those whose repo-relative path contains {@code query} (case-insensitive).
     * A query that names a group ("staged", "untracked") is <em>not</em> special-cased here — the group's
     * own title is matched by {@link #addGroup}, which keeps that whole group.
     */
    private static List<FileEntry> matching(Stream<FileEntry> files, String query) {
        List<FileEntry> out = new ArrayList<>();
        files.forEach(f -> {
            if (query.isEmpty() || f.path().toLowerCase(Locale.ROOT).contains(query)) {
                out.add(f);
            }
        });
        return out;
    }

    /**
     * Updates the header push indicator + Push button emphasis from the branch's ahead/behind state:
     * {@code ↑N} when there are commits to push, {@code ↑ publish} when the branch has no upstream yet
     * (everything is unpushed), or {@code ✓ pushed} when up to date.
     */
    private void updatePushIndicator(GitStatus status) {
        boolean noUpstream = status.upstream() == null || status.upstream().isBlank();
        int ahead = status.ahead();
        int behind = status.behind();
        boolean needsPush = noUpstream || ahead > 0;

        String text;
        String tip;
        if (noUpstream) {
            text = tr("gitpanel.publish");
            tip = tr("gitpanel.publishTip");
        } else if (ahead > 0 && behind > 0) {
            text = "↑" + ahead + " ↓" + behind;
            tip = tr("gitpanel.pushPull", ahead, behind);
        } else if (ahead > 0) {
            text = "↑" + ahead;
            tip = tr(ahead == 1 ? "gitpanel.toPush.one" : "gitpanel.toPush.many", ahead);
        } else if (behind > 0) {
            text = "↓" + behind;
            tip = tr(behind == 1 ? "gitpanel.toPull.one" : "gitpanel.toPull.many", behind);
        } else {
            text = tr("gitpanel.pushed");
            tip = tr("gitpanel.upToDate", status.upstream());
        }
        aheadLabel.setText(text);
        aheadLabel.setTooltip(new Tooltip(tip));
        aheadLabel.getStyleClass().removeAll("git-ahead-active", "git-ahead-clean");
        aheadLabel.getStyleClass().add(needsPush ? "git-ahead-active" : "git-ahead-clean");
        pushButton.getStyleClass().remove("git-needs-push");
        if (needsPush) {
            pushButton.getStyleClass().add("git-needs-push");
        }
    }

    /** What identifies a row across rebuilds: its group, and for a file its path. */
    private static String rowKey(Row row) {
        return switch (row) {
            case GroupRow g -> g.group().name();
            case FileRow f -> f.group().name() + '/' + f.entry().path();
        };
    }

    private void addGroup(List<TreeItem<Row>> groups, Group group, List<FileEntry> files) {
        // A query naming the group itself ("untracked") lists that whole group, even when no path matches.
        String query = filterQuery();
        if (!query.isEmpty()
                && files.isEmpty()
                && lastStatus != null
                && tr(group.key).toLowerCase(Locale.ROOT).contains(query)) {
            files = allIn(group);
        }
        if (files.isEmpty()) {
            return;
        }
        TreeItem<Row> node = new TreeItem<>(new GroupRow(group, files.size()));
        for (FileEntry f : files) {
            node.getChildren().add(new TreeItem<>(new FileRow(group, f)));
        }
        node.setExpanded(!collapsed.contains(group));
        node.expandedProperty().addListener((o, was, expanded) -> {
            if (expanded) {
                collapsed.remove(group);
            } else {
                collapsed.add(group);
            }
        });
        groups.add(node);
    }

    /** Every file of {@code group} in the last status, ignoring the filter (the group-title match). */
    private List<FileEntry> allIn(Group group) {
        return switch (group) {
            case CONFLICTS ->
                lastStatus.files().stream().filter(FileEntry::unmerged).toList();
            case STAGED -> lastStatus.files().stream().filter(FileEntry::staged).toList();
            case MODIFIED ->
                lastStatus.files().stream().filter(GitPanel::changed).toList();
            case UNTRACKED ->
                lastStatus.files().stream().filter(FileEntry::untracked).toList();
        };
    }

    /** Double-click / Enter: opens the file — or, for a conflicted one, the three-way resolver on it. */
    private void openSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && item.getValue() instanceof FileRow f) {
            if (f.group() == Group.CONFLICTS) {
                actions.resolve(f.entry().path());
            } else {
                actions.open(f.entry().path());
            }
        }
    }

    // --- keyboard navigation (mirrors BookmarksPanel) ---

    /**
     * Emacs navigation inside the tree: {@code C-n}/{@code C-p} move, {@code C-f}/{@code C-b} expand a group
     * or step out of it, {@code Enter}/{@code C-m} opens the selected file. The panel marks itself
     * {@code editora.ownsKeys}, so the global dispatcher leaves these chords to it — with no handler here
     * they were simply swallowed. Plain arrows (including Shift+arrow, which extends the selection) stay
     * with the {@link TreeView}.
     */
    private void onTreeKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER) {
            openSelected();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.SPACE && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
            toggleStagedOfSelection();
            e.consume(); // also when there was nothing to toggle: Space must not fall through to the tree
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
                openSelected();
                e.consume();
            }
            default -> {}
        }
    }

    /**
     * Moves the selection by {@code delta} rows, wrapping. {@code clearAndSelect}, not {@code select} — the
     * tree is multi-select, where plain {@code select} <em>adds</em> a row, so each press would grow the
     * selection instead of moving it.
     */
    private void move(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int idx = tree.getSelectionModel().getSelectedIndex();
        int next = idx < 0 ? (delta > 0 ? 0 : rows - 1) : Math.floorMod(idx + delta, rows);
        tree.getSelectionModel().clearAndSelect(next);
        tree.scrollTo(next);
    }

    private void expandOrDescend() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
        } else {
            move(1);
        }
    }

    private void collapseOrAscend() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            move(-1);
            return;
        }
        if (!item.isLeaf() && item.isExpanded()) {
            item.setExpanded(false);
        } else if (item.getParent() != null && item.getParent() != tree.getRoot()) {
            tree.getSelectionModel().clearAndSelect(tree.getRow(item.getParent()));
            tree.scrollTo(tree.getSelectionModel().getSelectedIndex());
        } else {
            move(-1);
        }
    }

    // --- multi-selection -------------------------------------------------------------------------

    /**
     * The rows a context-menu action applies to: the whole selection when the right-clicked row is part
     * of a multi-selection (so a Shift-selected run stages together), else just the clicked row — which
     * is then made the selection, the standard "right-click outside the selection re-selects" behavior.
     * Group rows are filtered out, so a Shift-range crossing a group header is harmless.
     */
    private List<FileRow> actionTargets(TreeItem<Row> clicked) {
        List<TreeItem<Row>> selected = tree.getSelectionModel().getSelectedItems();
        boolean clickedInSelection = clicked != null && selected.contains(clicked);
        if (clickedInSelection) {
            List<FileRow> rows = fileRows(selected);
            if (rows.size() > 1) {
                return rows;
            }
        }
        if (clicked == null || !(clicked.getValue() instanceof FileRow f)) {
            return List.of();
        }
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(clicked);
        return List.of(f);
    }

    /** The file rows among {@code items} (group rows and nulls dropped), in selection order. */
    private static List<FileRow> fileRows(List<TreeItem<Row>> items) {
        List<FileRow> rows = new ArrayList<>();
        for (TreeItem<Row> item : items) {
            if (item != null && item.getValue() instanceof FileRow f) {
                rows.add(f);
            }
        }
        return rows;
    }

    /** The currently selected file rows (used by the palette {@code git.stageSelected}/{@code unstageSelected}). */
    private List<FileRow> selectedFileRows() {
        return fileRows(tree.getSelectionModel().getSelectedItems());
    }

    /**
     * Stages every selected row that isn't already staged. Returns false (and does nothing) when the
     * selection holds no such row, so the caller can echo why.
     */
    public boolean stageSelected() {
        LinkedHashSet<String> all = new LinkedHashSet<>(conflictPaths(selectedFileRows())); // = mark resolved
        all.addAll(paths(selectedFileRows(), false));
        List<String> paths = List.copyOf(all);
        if (paths.isEmpty()) {
            return false;
        }
        actions.stage(paths);
        return true;
    }

    /** Unstages every selected staged row; mirrors {@link #stageSelected}. */
    public boolean unstageSelected() {
        List<String> paths = paths(selectedFileRows(), true);
        if (paths.isEmpty()) {
            return false;
        }
        actions.unstage(paths);
        return true;
    }

    /**
     * The distinct paths of the rows on the given side of the staged divide (a file can be in both).
     * Conflicted rows are on neither: they are resolved, not staged or unstaged ({@link #conflictPaths}).
     */
    private static List<String> paths(List<FileRow> rows, boolean staged) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (FileRow r : rows) {
            if (r.group() != Group.CONFLICTS && (r.group() == Group.STAGED) == staged) {
                out.add(r.entry().path());
            }
        }
        return List.copyOf(out);
    }

    /** The distinct paths of the conflicted rows among {@code rows}. */
    private static List<String> conflictPaths(List<FileRow> rows) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (FileRow r : rows) {
            if (r.group() == Group.CONFLICTS) {
                out.add(r.entry().path());
            }
        }
        return List.copyOf(out);
    }

    /** Builds and shows the context menu for {@code clicked}'s action targets at the given screen point. */
    private void showMenu(Node anchor, TreeItem<Row> clicked, double screenX, double screenY) {
        ContextMenu menu;
        if (clicked != null && clicked.getValue() instanceof GroupRow group) {
            tree.getSelectionModel().clearSelection();
            tree.getSelectionModel().select(clicked);
            menu = buildGroupMenu(group.group());
        } else {
            List<FileRow> targets = actionTargets(clicked);
            menu = targets.isEmpty() ? null : buildMenu(targets);
        }
        if (menu == null || menu.getItems().isEmpty()) {
            return;
        }
        if (openMenu != null) {
            openMenu.hide();
        }
        openMenu = menu;
        openMenu.show(anchor, screenX, screenY);
    }

    /**
     * The context menu of a group header: the whole group at once — Stage All for Changes and Untracked,
     * Unstage All for Staged, Discard All / Delete All for what is not staged. It acts on every file of the
     * group, whatever the filter shows. The Conflicts group has none: conflicts are resolved one by one.
     */
    private ContextMenu buildGroupMenu(Group group) {
        ContextMenu menu = new ContextMenu();
        if (lastStatus == null || group == Group.CONFLICTS) {
            return menu;
        }
        List<String> all = allIn(group).stream().map(FileEntry::path).toList();
        if (all.isEmpty()) {
            return menu;
        }
        if (group == Group.STAGED) {
            MenuItem unstage = new MenuItem(tr("gitpanel.menu.unstageAll"));
            unstage.setGraphic(Icons.remove());
            unstage.setOnAction(a -> actions.unstageAll());
            menu.getItems().add(unstage);
            return menu;
        }
        MenuItem stage = new MenuItem(tr("gitpanel.menu.stageGroup"));
        stage.setGraphic(Icons.stageAll());
        stage.setOnAction(a -> actions.stage(all));
        boolean untracked = group == Group.UNTRACKED;
        MenuItem discard = new MenuItem(tr(untracked ? "gitpanel.menu.deleteGroup" : "gitpanel.menu.discardGroup"));
        discard.setGraphic(Icons.trash());
        discard.setOnAction(a -> actions.discard(untracked ? List.of() : all, untracked ? all : List.of()));
        menu.getItems().addAll(stage, discard);
        return menu;
    }

    /**
     * Space in the list: stages the selected rows that are not staged and unstages the ones that are — on a
     * group header, the whole group. Conflicted rows are left alone (staging one declares it resolved; that
     * is its own menu item). False when the selection has nothing to toggle.
     */
    boolean toggleStagedOfSelection() {
        LinkedHashSet<String> stage = new LinkedHashSet<>();
        LinkedHashSet<String> unstage = new LinkedHashSet<>();
        boolean wholeStagedGroup = false;
        for (TreeItem<Row> item : tree.getSelectionModel().getSelectedItems()) {
            if (item == null || lastStatus == null) {
                continue;
            }
            if (item.getValue() instanceof FileRow file && file.group() != Group.CONFLICTS) {
                (file.group() == Group.STAGED ? unstage : stage)
                        .add(file.entry().path());
            } else if (item.getValue() instanceof GroupRow group && group.group() != Group.CONFLICTS) {
                wholeStagedGroup |= group.group() == Group.STAGED;
                for (FileEntry entry : allIn(group.group())) {
                    (group.group() == Group.STAGED ? unstage : stage).add(entry.path());
                }
            }
        }
        if (stage.isEmpty() && unstage.isEmpty()) {
            return false;
        }
        if (!stage.isEmpty()) {
            actions.stage(List.copyOf(stage));
        }
        if (wholeStagedGroup) {
            actions.unstageAll();
        } else if (!unstage.isEmpty()) {
            actions.unstage(List.copyOf(unstage));
        }
        return true;
    }

    /**
     * The context menu for {@code targets}. Open/Show Diff are single-row actions; stage, unstage and
     * discard each appear when at least one target is on their side of the staged divide, so a mixed
     * selection offers both Stage and Unstage — each acting only on the rows it applies to.
     */
    private ContextMenu buildMenu(List<FileRow> targets) {
        ContextMenu menu = new ContextMenu();
        if (targets.size() == 1) {
            FileRow only = targets.get(0);
            FileEntry e = only.entry();
            if (only.group() == Group.CONFLICTS) {
                MenuItem resolve = new MenuItem(tr("gitpanel.menu.resolve"));
                resolve.setGraphic(Icons.merge());
                resolve.setOnAction(a -> actions.resolve(e.path()));
                menu.getItems().add(resolve);
            }
            MenuItem open = new MenuItem(tr("gitpanel.menu.open"));
            open.setGraphic(Icons.fileSheet());
            open.setOnAction(a -> actions.open(e.path()));
            menu.getItems().add(open);
            if (!e.untracked() && !e.unmerged()) { // an untracked file has no committed/index version to diff against
                MenuItem showDiff = new MenuItem(tr("gitpanel.menu.showDiff"));
                showDiff.setGraphic(Icons.diff());
                boolean staged = only.group() == Group.STAGED;
                showDiff.setOnAction(a -> actions.diff(e.path(), staged));
                menu.getItems().add(showDiff);
            }
        }
        List<String> conflicted = conflictPaths(targets);
        if (!conflicted.isEmpty()) {
            // Named by what each side is in the operation in progress: during a rebase git's "ours" is the
            // branch being rebased onto, so the labels say that and carry git's term in parentheses.
            MenuItem ours = new MenuItem(tr(GitCoordinator.acceptSideKey(operation.kind(), true)));
            ours.setOnAction(a -> actions.acceptSide(conflicted, true));
            MenuItem theirs = new MenuItem(tr(GitCoordinator.acceptSideKey(operation.kind(), false)));
            theirs.setOnAction(a -> actions.acceptSide(conflicted, false));
            // Staging is how git is told a conflict is resolved; the controller asks first when the file
            // still has conflict markers in it.
            MenuItem resolved = new MenuItem(tr("gitpanel.menu.markResolved"));
            resolved.setGraphic(Icons.stageAll());
            resolved.setOnAction(a -> actions.stage(conflicted));
            menu.getItems().addAll(ours, theirs, resolved);
        }
        List<String> toStage = paths(targets, false);
        if (!toStage.isEmpty()) {
            MenuItem stage = new MenuItem(
                    toStage.size() == 1 ? tr("gitpanel.menu.stage") : tr("gitpanel.menu.stageMany", toStage.size()));
            stage.setGraphic(Icons.stageAll());
            stage.setOnAction(a -> actions.stage(toStage));
            menu.getItems().add(stage);
        }
        List<String> toUnstage = paths(targets, true);
        if (!toUnstage.isEmpty()) {
            MenuItem unstage = new MenuItem(
                    toUnstage.size() == 1
                            ? tr("gitpanel.menu.unstage")
                            : tr("gitpanel.menu.unstageMany", toUnstage.size()));
            unstage.setGraphic(Icons.remove());
            unstage.setOnAction(a -> actions.unstage(toUnstage));
            menu.getItems().add(unstage);
        }
        addDiscardItem(menu, targets);
        return menu;
    }

    /** Adds the Discard/Delete item for the non-staged targets (nothing when there are none). */
    private void addDiscardItem(ContextMenu menu, List<FileRow> targets) {
        LinkedHashSet<String> tracked = new LinkedHashSet<>();
        LinkedHashSet<String> untracked = new LinkedHashSet<>();
        for (FileRow r : targets) {
            if (r.group() == Group.STAGED || r.entry().unmerged()) {
                continue; // git refuses to check out an unmerged path: it is resolved, not discarded
            }
            (r.entry().untracked() ? untracked : tracked).add(r.entry().path());
        }
        int total = tracked.size() + untracked.size();
        if (total == 0) {
            return;
        }
        String label;
        if (total == 1) {
            label = tracked.isEmpty() ? tr("gitpanel.menu.deleteUntracked") : tr("gitpanel.menu.discard");
        } else {
            label = tracked.isEmpty()
                    ? tr("gitpanel.menu.deleteUntrackedMany", total)
                    : tr("gitpanel.menu.discardMany", total);
        }
        List<String> trackedPaths = List.copyOf(tracked);
        List<String> untrackedPaths = List.copyOf(untracked);
        MenuItem discard = new MenuItem(label);
        discard.setGraphic(Icons.trash());
        discard.setOnAction(a -> actions.discard(trackedPaths, untrackedPaths));
        menu.getItems().add(discard);
    }

    // --- ToolWindowContent ---

    public void focusContent() {
        if (tree.getRoot() != null && !tree.getRoot().getChildren().isEmpty()) {
            filterField.requestFocus();
        } else {
            message.requestFocus();
        }
    }

    @Override
    public void focusFirstItem() {
        // Land on the filter field so a file can be typed for immediately; Down/Enter and C-n/C-p move into
        // and through the results (FilterFieldNav), as in the Project / Bookmarks / Notes windows.
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
            tree.scrollTo(0);
        }
        focusContent();
    }

    /** Moves keyboard focus to the commit message box (for the commit command). */
    public void focusCommitMessage() {
        message.requestFocus();
    }

    /** Replaces the commit message box's content (AI-generated message; the user edits before committing). */
    public void setCommitMessage(String text) {
        message.setText(text == null ? "" : text);
        message.positionCaret(message.getLength());
    }

    /** The row's status letter — derived from the same {@link GitFileStatus} that colors the row, so the two
     *  can't disagree (picking the letter off one porcelain side alone rendered e.g. an index-modified,
     *  worktree-deleted file as "M" in the deleted/grey color). */
    private static String statusLetter(FileEntry e) {
        return GitFileStatus.of(e).letter();
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Style classes a Git cell may carry, cleared before each render (the group-row marker + the per-status
     *  color classes shared with the Project tree). */
    private static final String[] STATUS_CLASSES = {
        "git-group-row",
        "git-status-added",
        "git-status-modified",
        "git-status-deleted",
        "git-status-renamed",
        "git-status-untracked",
        "git-status-conflict"
    };

    /** Narrowest the branch label gets in the header (room for the glyph and a short branch name). */
    static final double BRANCH_MIN_WIDTH = 64;

    private final class GitCell extends TreeCell<Row> {

        /** A file row: glyph and status letter, the path (elided from its start), then the line counts. */
        private final Label pathLabel = new Label();

        private final Label addedLabel = new Label();
        private final Label deletedLabel = new Label();
        private final StackPane glyph = new StackPane();
        private final HBox fileRow = new HBox(6, glyph, pathLabel, addedLabel, deletedLabel);

        GitCell() {
            // Rows are as wide as the tree, never wider: a long path is elided (see updateItem) instead of
            // pushing its file name behind a horizontal scrollbar.
            setPrefWidth(0);
            // Built per request rather than stored via setContextMenu: the items depend on the current
            // selection, which changes long after updateItem last ran for this cell.
            setOnContextMenuRequested(e -> {
                if (getItem() != null) {
                    showMenu(this, getTreeItem(), e.getScreenX(), e.getScreenY());
                    e.consume();
                }
            });
            fileRow.setAlignment(Pos.CENTER_LEFT);
            // The row takes the cell's width, whatever its content would like: the path gives way.
            fileRow.setMinWidth(0);
            fileRow.setMaxWidth(Double.MAX_VALUE);
            fileRow.setMouseTransparent(true);
            pathLabel.setMinWidth(0);
            pathLabel.setMaxWidth(Double.MAX_VALUE);
            pathLabel.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
            // The path is drawn in the cell's own colour — status tint, selection — like the text it replaces.
            pathLabel.textFillProperty().bind(textFillProperty());
            HBox.setHgrow(pathLabel, Priority.ALWAYS);
            addedLabel.getStyleClass().addAll("git-count", "git-count-added");
            deletedLabel.getStyleClass().addAll("git-count", "git-count-deleted");
            addedLabel.setMinWidth(Region.USE_PREF_SIZE);
            deletedLabel.setMinWidth(Region.USE_PREF_SIZE);
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(STATUS_CLASSES); // includes "git-group-row" + the per-status colors
            // Cells are recycled: a group row or an empty one must not keep the path of the file row this
            // cell showed before.
            setTooltip(null);
            setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item instanceof GroupRow g) {
                setText(tr(g.group().key) + " (" + g.count() + ")");
                getStyleClass().add("git-group-row");
                setGraphic(null);
                setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            } else if (item instanceof FileRow f) {
                FileEntry e = f.entry();
                // The cell keeps the path as its text (what assistive technology reads), but draws the row
                // itself: the status letter can be bold on its own, and the line counts sit at the right edge.
                setText(e.path());
                setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
                setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
                // Elide the leading directories, so the file name — the part that identifies the row —
                // stays visible; the tooltip below has the whole path.
                pathLabel.setText(e.path());
                glyph.getChildren().setAll(FileIcons.withStatusLetter(Icons.fileSheet(), statusLetter(e)));
                showCounts(countsOf(f));
                setGraphic(fileRow);
                // Color the row by status (same palette as the Project tree) so the two windows match.
                getStyleClass().add(GitFileStatus.of(e).cssClass());
                setTooltip(new Tooltip(e.path()));
            }
        }

        private void showCounts(GitNumstat.Counts counts) {
            boolean lines = counts != null && !counts.binary();
            addedLabel.setText(lines && counts.added() > 0 ? "+" + counts.added() : "");
            deletedLabel.setText(lines && counts.deleted() > 0 ? "\u2212" + counts.deleted() : "");
            show(addedLabel, !addedLabel.getText().isEmpty());
            show(deletedLabel, !deletedLabel.getText().isEmpty());
        }
    }

    /** The line counts of a row: the staged change for a Staged row, the unstaged one for a Changes row. */
    private GitNumstat.Counts countsOf(FileRow row) {
        return switch (row.group()) {
            case STAGED -> lineCounts.staged().get(row.entry().path());
            case MODIFIED -> lineCounts.unstaged().get(row.entry().path());
            default -> null;
        };
    }

    /** What a file row shows for its line counts ("+3 −1"), or {@code ""}; for tests and tooltips. */
    String countsText(String group, String path) {
        GitNumstat.Counts counts = countsOf(new FileRow(Group.valueOf(group), new FileEntry(path, '.', '.', null)));
        if (counts == null || counts.binary()) {
            return "";
        }
        return ((counts.added() > 0 ? "+" + counts.added() : "") + " "
                        + (counts.deleted() > 0 ? "\u2212" + counts.deleted() : ""))
                .strip();
    }
}
