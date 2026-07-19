package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.config.Settings;
import com.editora.diff.PatchParser;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import com.editora.github.GitHubRemote;
import com.editora.github.GitHubService;
import com.editora.github.PrCreateArgs;
import com.editora.github.PrListParser;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * The stateful core of the GitHub integration (native {@code gh} CLI), the {@code GitCoordinator} analogue.
 * It owns the {@link GitHubService} (off-thread {@code gh} facade) + the cached availability, and the
 * user-facing flows: checking out a PR, reviewing a PR's diff in the existing diff viewer, opening the active
 * file on GitHub, creating a PR, and (slices 2/3) the PR/issue tool window + status-bar CI checks.
 *
 * <p>It rides on the Git integration: {@code gh} commands run with a working directory inside the repo (from
 * {@link GitCoordinator#repoRoot()}, else the active file's folder), so {@code gh} resolves owner/repo from
 * the git remote itself. PR diffs reuse the diff stack — {@code gh pr diff} → the pure {@link PatchParser} →
 * read-only text/text tabs via {@link DiffCoordinator#openDiff} — so no new diff code is needed.
 *
 * <p>Security: Editora never handles a token; {@code gh} holds the user's GitHub credentials and Editora only
 * shells out, consistent with the native-CLI Git design.
 */
final class GitHubCoordinator {

    /** Window surfaces the GitHub flows drive, beyond the shared {@link CoordinatorHost}. */
    interface WindowOps {
        /** After {@code gh pr checkout}, silently reload any open buffer whose file changed on disk. */
        void reloadAllFromDiskSilently();

        /** Re-checks the active file's on-disk stamp + reloads it if a gh command changed it under us. */
        void checkExternalChanges();

        /** This window's active keymap (for installing caret navigation on the create-PR form fields). */
        KeymapManager keymap();

        /** (Slice 2) Sets whether the GitHub tool-window stripe button is available (repo is a GitHub repo). */
        void setGitHubWindowAvailable(boolean available);

        /** (Slice 2) Opens/toggles the GitHub tool window. */
        void toggleGitHubWindow();

        /** (Slice 3) Pushes the active PR's CI-check roll-up to the status bar (null = hide). */
        void setStatusBarChecks(com.editora.github.ChecksParser.ChecksSummary summary);
    }

    /** Above this many changed files, "Open all files" confirms first so a big PR can't spam tabs. */
    private static final int MAX_OPEN_ALL_WITHOUT_CONFIRM = 12;

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private final DiffCoordinator diff;
    private final WindowOps ops;
    private final GitHubService service = new GitHubService();

    /** The repo root whose remote URL has been classified (so the async check runs once per root). */
    private Path remoteCheckedRoot;
    /** Whether {@link #remoteCheckedRoot}'s remote is a GitHub host (drives the always-on surfaces). */
    private boolean remoteIsGitHub;

    GitHubCoordinator(CoordinatorHost host, GitCoordinator git, DiffCoordinator diff, WindowOps ops) {
        this.host = host;
        this.git = git;
        this.diff = diff;
        this.ops = ops;
    }

    /** The off-thread {@code gh} facade (the slice-2 panel + slice-3 checks read through it). */
    GitHubService service() {
        return service;
    }

    /** Whether the GitHub integration is enabled in Settings. Off in Simple UI mode (like Git). */
    boolean isEnabled() {
        return host.settings().isGithubSupport() && !host.simpleModeActive();
    }

    /** Runs {@code action} only when GitHub is enabled; otherwise reports it (disables the command). */
    void ifEnabled(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.githubDisabled"));
        }
    }

    /**
     * Reconciles the GitHub UI with the setting. When on: configures the {@code gh} command + probes
     * availability, then re-gates. When off: clears the tool-window/status surfaces. Runs at startup and on
     * every settings apply (the {@code GitCoordinator.applySupport} lifecycle slot).
     */
    void applySupport() {
        boolean on = isEnabled();
        if (!on) {
            ops.setGitHubWindowAvailable(false);
            ops.setStatusBarChecks(null);
            return;
        }
        service.setCommand(host.settings().getGhPath());
        service.detect(a -> applyGating());
    }

    /** Re-derives the tool-window availability from cached state (called on tab switch, cheap/sync). */
    void refreshAvailability() {
        applyGating();
    }

    /** Re-derives the always-on surfaces from the cached availability + whether the repo is a GitHub repo. */
    private void applyGating() {
        boolean available = isEnabled() && ready() && contextDir() != null && repoIsGitHub();
        ops.setGitHubWindowAvailable(available);
        if (!available) {
            ops.setStatusBarChecks(null);
        }
    }

    /** Whether {@code gh} is present + authenticated (from the cached probe; false until probed). */
    private boolean ready() {
        GitHubService.Availability a = service.availability();
        return a != null && a.ready();
    }

    /**
     * Whether the current repo's remote is a GitHub host — gates the always-on surfaces (the tool window +
     * checks) so they stay hidden on a GitLab/Gitea repo. The remote URL is resolved off-thread once per repo
     * root (via the shared {@code GitService}) then re-gated; the palette commands still run regardless and
     * surface gh's own error. Returns false until the async classification for a new root completes.
     */
    private boolean repoIsGitHub() {
        Path root = git.repoRoot();
        if (root == null) {
            return false;
        }
        if (!root.equals(remoteCheckedRoot)) {
            remoteCheckedRoot = root;
            remoteIsGitHub = false;
            git.service().branches(root, b -> {
                remoteIsGitHub = GitHubRemote.isGitHub(b.remoteUrl());
                applyGating();
            });
            return false;
        }
        return remoteIsGitHub;
    }

    // --- readiness guard shared by every gh flow -------------------------------------------------

    /** Runs {@code then} with the repo working directory once GitHub is enabled + {@code gh} is usable + in a
     *  repo; otherwise echoes the precise reason (disabled / gh missing / not authenticated / no repo). */
    private void ready(Consumer<Path> then) {
        if (!isEnabled()) {
            host.setStatus(tr("statusbar.tip.githubDisabled"));
            return;
        }
        GitHubService.Availability a = service.availability();
        if (a == null) {
            service.detect(av -> applyGating()); // not probed yet — kick one off; the user can retry
            host.setStatus(tr("status.github.checking"));
            return;
        }
        if (!a.found()) {
            host.setStatus(tr("status.github.ghNotFound"));
            return;
        }
        if (!a.authenticated()) {
            host.setStatus(tr("status.github.notAuthenticated"));
            return;
        }
        Path dir = contextDir();
        if (dir == null) {
            host.setStatus(tr("status.github.noRepo"));
            return;
        }
        then.accept(dir);
    }

    /** The working directory for {@code gh}: the git repo root, else the active file's folder, else null. */
    private Path contextDir() {
        Path root = git.repoRoot();
        if (root != null) {
            return root;
        }
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null && Vfs.isLocal(file)) {
            return Files.isDirectory(file) ? file : file.getParent();
        }
        return null;
    }

    // --- refresh / toggle ------------------------------------------------------------------------

    /** Re-detects {@code gh}, re-gates the surfaces, and refreshes the CI-checks segment (the {@code github.refresh}
     *  command). No background polling — checks refresh only here + after a PR checkout. */
    void refresh() {
        ifEnabled(() -> service.detect(a -> {
            applyGating();
            host.setStatus(tr(a.ready() ? "status.github.ready" : "status.github.notReady"));
            if (a.ready()) {
                refreshChecks(contextDir());
            }
        }));
    }

    /** Flips the {@code githubSupport} setting (the {@code view.toggleGithub} palette command). */
    void toggleSupport() {
        Settings s = host.settings();
        s.setGithubSupport(!s.isGithubSupport());
        host.requestSave();
        applySupport();
        host.syncSettingsWindow();
        host.setStatus(tr("status.toggle.github", tr(s.isGithubSupport() ? "common.on" : "common.off")));
    }

    // --- PR checkout -----------------------------------------------------------------------------

    /** Picks an open PR and checks it out ({@code gh pr checkout}); refreshes Git + reloads changed buffers. */
    void checkoutPr() {
        ready(dir -> pickPr(tr("github.picker.checkoutTitle"), pr -> doCheckout(dir, pr.number())));
    }

    /** Checks out a specific PR by number (the panel's row action). */
    void checkoutNumber(int number) {
        ready(dir -> doCheckout(dir, number));
    }

    private void doCheckout(Path dir, int number) {
        host.setStatus(tr("status.github.checkingOut", number));
        service.prCheckout(dir, number, r -> {
            if (r.ok()) {
                host.setStatus(tr("status.github.checkedOut", number));
                git.afterMutation();
                ops.reloadAllFromDiskSilently();
                javafx.application.Platform.runLater(ops::checkExternalChanges);
                refreshChecks(dir); // the checked-out branch's PR may have CI checks
            } else {
                ghError(tr("status.github.checkoutFailed"), r.message());
            }
        });
    }

    // --- PR diff review --------------------------------------------------------------------------

    /** Picks an open PR and opens its diff in the read-only diff viewer (one file, or a file picker). */
    void viewPrDiff() {
        ready(dir -> pickPr(tr("github.picker.diffTitle"), pr -> doPrDiff(dir, pr.number())));
    }

    /** Opens a specific PR's diff by number (the panel's row action / double-click). */
    void reviewPrNumber(int number) {
        ready(dir -> doPrDiff(dir, number));
    }

    private void doPrDiff(Path dir, int number) {
        service.prDiff(dir, number, res -> {
            if (!res.ok()) {
                ghError(tr("status.github.diffFailed"), res.error());
                return;
            }
            List<PatchParser.FilePatch> files = res.files();
            if (files.isEmpty()) {
                host.setStatus(tr("status.github.prDiffEmpty", number));
                return;
            }
            if (files.size() == 1) {
                openFileDiff(number, files.get(0));
                return;
            }
            pickPrFile(number, files);
        });
    }

    // --- tool-window feeds (slice 2) -------------------------------------------------------------

    /** Fetches open PRs for the tool window; delivers an empty list (with a status) when gh isn't usable. */
    void fetchPrs(Consumer<List<PrListParser.PullRequest>> onResult) {
        ready(dir -> service.prList(dir, res -> {
            if (!res.ok()) {
                host.setStatus(tr("status.github.prListFailed"));
                onResult.accept(List.of());
            } else {
                onResult.accept(res.prs());
            }
        }));
    }

    /** Fetches open issues for the tool window. */
    void fetchIssues(Consumer<List<com.editora.github.IssueListParser.Issue>> onResult) {
        ready(dir -> service.issueList(dir, res -> onResult.accept(res.ok() ? res.issues() : List.of())));
    }

    /** Opens a GitHub URL in the browser (a panel row's "Open on GitHub"). */
    void openUrl(String url) {
        if (url == null || url.isBlank()) {
            host.setStatus(tr("status.github.noFile"));
            return;
        }
        host.openExternalUrl(url);
    }

    /** Copies a GitHub URL to the clipboard (a panel row's "Copy URL"). */
    void copyUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(url);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        host.setStatus(tr("status.github.copiedUrl"));
    }

    // --- CI checks status-bar roll-up (slice 3) --------------------------------------------------

    /** Fetches the current branch's PR checks and pushes the roll-up to the status bar (null hides it). */
    private void refreshChecks(Path dir) {
        if (dir == null) {
            ops.setStatusBarChecks(null);
            return;
        }
        service.prChecks(dir, 0, res -> {
            if (res.ok() && !res.runs().isEmpty()) {
                ops.setStatusBarChecks(com.editora.github.ChecksParser.ChecksSummary.of(res.runs()));
            } else {
                ops.setStatusBarChecks(null); // no PR for this branch, or no checks
            }
        });
    }

    /** Shows a file picker for a multi-file PR diff, with an "Open all N files" entry (guarded by a confirm). */
    private void pickPrFile(int prNumber, List<PatchParser.FilePatch> files) {
        // A null-file sentinel is the "Open all" row; every real row is one changed file.
        List<PrFileChoice> choices = new ArrayList<>();
        choices.add(new PrFileChoice(null, files.size()));
        for (PatchParser.FilePatch fp : files) {
            choices.add(new PrFileChoice(fp, 0));
        }
        QuickOpen<PrFileChoice> picker = new QuickOpen<>(
                tr("github.picker.fileTitle", prNumber),
                tr("github.picker.filePrompt"),
                () -> choices,
                c -> c.file() == null ? tr("github.picker.openAll", files.size()) : displayName(c.file()),
                c -> c.file() == null ? "" : changeStat(c.file()),
                c -> c.file() == null ? tr("github.picker.openAll", files.size()) : displayName(c.file()),
                c -> {
                    if (c.file() == null) {
                        openAllFiles(prNumber, files);
                    } else {
                        openFileDiff(prNumber, c.file());
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Opens every changed file of a PR as its own diff tab — confirming first past {@link #MAX_OPEN_ALL_WITHOUT_CONFIRM}. */
    private void openAllFiles(int prNumber, List<PatchParser.FilePatch> files) {
        if (files.size() > MAX_OPEN_ALL_WITHOUT_CONFIRM && !confirmOpenAll(files.size())) {
            return;
        }
        for (PatchParser.FilePatch fp : files) {
            openFileDiff(prNumber, fp);
        }
    }

    private boolean confirmOpenAll(int count) {
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("dialog.github.openAllConfirm", count),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.github.title"));
        confirm.setHeaderText(null);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Opens one PR file as a read-only side-by-side diff tab (base ↔ head), reusing {@link DiffCoordinator}. */
    private void openFileDiff(int prNumber, PatchParser.FilePatch fp) {
        String oldName = cleanLabel(fp.oldPath());
        String newName = cleanLabel(fp.newPath());
        String name = !newName.isEmpty() ? newName : (!oldName.isEmpty() ? oldName : tr("github.pr.file"));
        String leftName = !oldName.isEmpty() ? oldName : name;
        String rightName = !newName.isEmpty() ? newName : name;
        String leftText = String.join("\n", fp.oldLines());
        String rightText = String.join("\n", fp.newLines());
        diff.openDiff(
                tr("diff.title.prFile", name, prNumber),
                tr("diff.side.prBase"),
                tr("diff.side.prHead"),
                leftName,
                rightName,
                cb -> cb.accept(leftText),
                cb -> cb.accept(rightText),
                DiffViewerPane.EditableSide.NONE,
                null);
    }

    // --- open on GitHub --------------------------------------------------------------------------

    /** Opens the active file (at the caret line, on the current branch) on GitHub in the browser. */
    void openOnGitHub() {
        ready(dir -> {
            EditorBuffer b = host.activeBuffer();
            Path file = b == null ? null : b.getPath();
            if (file == null || !Vfs.isLocal(file)) {
                host.setStatus(tr("status.github.noFile"));
                return;
            }
            Path root = git.repoRoot();
            String rel = root == null ? null : GitService.repoRelative(root, file);
            if (rel == null) {
                host.setStatus(tr("status.github.noRepo"));
                return;
            }
            int line = b.getArea().getCurrentParagraph() + 1; // 1-based
            String branch = git.branchName();
            service.browse(dir, rel + ":" + line, branch, r -> {
                String url = r.out() == null ? "" : r.out().strip();
                if (r.ok() && !url.isEmpty()) {
                    host.openExternalUrl(url);
                    host.setStatus(tr("status.github.opened"));
                } else {
                    ghError(tr("status.github.browseFailed"), r.message());
                }
            });
        });
    }

    // --- create PR (slice 3) ---------------------------------------------------------------------

    /** Opens the create-PR form (title / body / base / draft) and runs {@code gh pr create}. */
    void createPr() {
        ready(dir -> {
            KeymapManager keymap = ops.keymap();
            TextField titleField = new TextField();
            titleField.setPromptText(tr("dialog.createPr.titlePrompt"));
            titleField.setPrefColumnCount(34);
            TextInputKeymap.install(titleField, keymap);
            TextArea bodyField = new TextArea();
            bodyField.setPromptText(tr("dialog.createPr.bodyPrompt"));
            bodyField.setPrefRowCount(5);
            bodyField.setWrapText(true);
            TextInputKeymap.install(bodyField, keymap);
            TextField baseField = new TextField(); // blank ⇒ gh uses the repo's default base branch
            baseField.setPromptText(tr("dialog.createPr.basePrompt"));
            TextInputKeymap.install(baseField, keymap);
            CheckBox draft = new CheckBox(tr("dialog.createPr.draft"));

            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(8);
            grid.add(new Label(tr("dialog.createPr.titleLabel")), 0, 0);
            grid.add(titleField, 1, 0);
            grid.add(new Label(tr("dialog.createPr.bodyLabel")), 0, 1);
            grid.add(bodyField, 1, 1);
            grid.add(new Label(tr("dialog.createPr.baseLabel")), 0, 2);
            grid.add(baseField, 1, 2);
            grid.add(draft, 1, 3);
            GridPane.setHgrow(titleField, Priority.ALWAYS);
            GridPane.setHgrow(bodyField, Priority.ALWAYS);
            GridPane.setHgrow(baseField, Priority.ALWAYS);

            BooleanProperty valid =
                    new SimpleBooleanProperty(!titleField.getText().isBlank());
            titleField.textProperty().addListener((o, a, b) -> valid.set(!b.isBlank()));

            OverlayInput.show(
                    host.overlayHost(),
                    tr("dialog.createPr.title"),
                    grid,
                    titleField,
                    tr("dialog.createPr.button"),
                    valid,
                    () -> {
                        List<String> args = PrCreateArgs.build(
                                titleField.getText(), bodyField.getText(), baseField.getText(), draft.isSelected());
                        host.setStatus(tr("status.github.creatingPr"));
                        service.prCreate(dir, args, r -> {
                            String url = r.out() == null ? "" : r.out().strip();
                            if (r.ok()) {
                                host.setStatus(tr("status.github.createdPr"));
                                if (!url.isEmpty()) {
                                    host.openExternalUrl(lastUrl(url));
                                }
                            } else {
                                ghError(tr("status.github.createFailed"), r.message());
                            }
                        });
                    },
                    null,
                    true);
        });
    }

    // --- shared helpers --------------------------------------------------------------------------

    /** Fetches open PRs and shows a picker; reports the failure / empty cases distinctly. */
    private void pickPr(String title, Consumer<PrListParser.PullRequest> onPick) {
        host.setStatus(tr("status.github.loadingPrs"));
        service.prList(contextDir(), res -> {
            if (!res.ok()) {
                ghError(tr("status.github.prListFailed"), res.error());
                return;
            }
            if (res.prs().isEmpty()) {
                host.setStatus(tr("status.github.noPrs"));
                return;
            }
            QuickOpen<PrListParser.PullRequest> picker = new QuickOpen<>(
                    title,
                    tr("github.picker.prPrompt"),
                    res::prs,
                    pr -> "#" + pr.number() + "  " + pr.title() + (pr.draft() ? "  " + tr("github.draft") : ""),
                    pr -> pr.authorLogin() + " · " + pr.headRefName(),
                    pr -> "#" + pr.number() + " " + pr.title() + " " + pr.authorLogin() + " " + pr.headRefName(),
                    onPick);
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** The display name for a changed file: its new path, else its old path. */
    private static String displayName(PatchParser.FilePatch fp) {
        String n = cleanLabel(fp.newPath());
        return !n.isEmpty() ? n : cleanLabel(fp.oldPath());
    }

    /** A muted "+adds/-dels" stat for a file row. */
    private static String changeStat(PatchParser.FilePatch fp) {
        return "+" + fp.newLines().size() + " −" + fp.oldLines().size();
    }

    /** {@code ""} for a missing / {@code /dev/null} patch label, else the label unchanged. */
    private static String cleanLabel(String path) {
        return path == null || path.isBlank() || "/dev/null".equals(path) ? "" : path;
    }

    /** The last whitespace-separated token of {@code gh}'s stdout — the created PR URL is the last line. */
    private static String lastUrl(String out) {
        String[] lines = out.strip().split("\\s+");
        return lines.length == 0 ? out.strip() : lines[lines.length - 1];
    }

    /** A scrollable error dialog for a {@code gh} command's (often multi-line) output — mirrors gitError. */
    private void ghError(String summary, String detail) {
        host.setStatus(summary);
        String body = detail == null || detail.isBlank() ? summary : detail.strip();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.github.title"));
        alert.setHeaderText(summary);
        TextArea area = new TextArea(body);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(52);
        area.setPrefRowCount(Math.min(14, (int) body.lines().count() + 1));
        area.getStyleClass().add("git-error-text");
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    void shutdown() {
        service.shutdown();
    }

    /** One row in the multi-file PR-diff picker: a real file, or (file == null) the "Open all" sentinel. */
    private record PrFileChoice(PatchParser.FilePatch file, int count) {}
}
