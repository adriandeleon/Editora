package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.stage.FileChooser;

import com.editora.diff.PatchParser;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LineEndings;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.git.GitFormat;
import com.editora.git.GitService;
import com.editora.git.PatchOutcome;
import com.editora.process.ProcessRunner;

import static com.editora.i18n.Messages.tr;

/**
 * Patches in and out of the repository: {@code git.applyPatch} (a {@code .patch}/{@code .diff} file, or the
 * active buffer when it is one) with the patch-review tab's Apply pair, and {@code git.createPatch} (the
 * staged changes, the unstaged changes or one commit, to a file or a new buffer).
 *
 * <p>Applying goes through {@link GitService#applyPatch}: checked first, so a patch that does not fit
 * changes nothing; git's reason is then shown with the offer to retry as a three-way merge. A working-tree
 * apply runs inside {@link GitCoordinator#aroundWorkingTreeMutation}, so open buffers reload. Owned by
 * {@link GitCoordinator}.
 */
final class GitPatchCoordinator {

    /** What a created patch holds. */
    enum Source {
        STAGED,
        UNSTAGED,
        COMMIT
    }

    /** Where a created patch goes. */
    enum Destination {
        FILE,
        BUFFER
    }

    /** Largest patch file read for applying; matches the cap on a captured Git blob. */
    static final long MAX_PATCH_BYTES = 10L * 1024 * 1024;

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private Consumer<EditorBuffer> openTab;

    /** Asks whether to retry a rejected patch as a three-way merge, given git's reason. Replaced by tests. */
    Predicate<String> confirmThreeWay = this::askThreeWay;

    /** Picks the patch file to apply, or {@code null}. Replaced by tests. */
    Supplier<Path> choosePatchFile = this::askPatchFile;

    /** Picks where to save a created patch, given a suggested name, or {@code null}. Replaced by tests. */
    Function<String, Path> chooseSaveFile = this::askSaveFile;

    GitPatchCoordinator(CoordinatorHost host, GitCoordinator git) {
        this.host = host;
        this.git = git;
    }

    void attach(Consumer<EditorBuffer> openTab) {
        this.openTab = openTab;
    }

    // --- apply -------------------------------------------------------------------------------------

    /**
     * {@code git.applyPatch}: the active buffer when it holds a patch, otherwise a file the user picks; then
     * the target — working tree or index.
     */
    void applyPatchCommand() {
        if (git.reportIfNoRepo()) {
            return;
        }
        EditorBuffer active = host.activeBuffer();
        if (active != null && isPatch(active.getTitle(), active.getContent())) {
            chooseTarget(patchBytes(active));
            return;
        }
        Path file = choosePatchFile.get();
        if (file != null) {
            readPatchFile(file, this::chooseTarget);
        }
    }

    /** Reads a patch file off the FX thread; reports and stops when it cannot be read or is too large. */
    void readPatchFile(Path file, Consumer<byte[]> onBytes) {
        Thread reader = new Thread(
                () -> {
                    byte[] bytes = null;
                    String problem = null;
                    try {
                        if (Files.size(file) > MAX_PATCH_BYTES) {
                            problem = tr("status.git.patchTooLarge", file.getFileName());
                        } else {
                            bytes = Files.readAllBytes(file);
                        }
                    } catch (IOException | RuntimeException e) {
                        problem = tr("status.git.patchReadFailed", file.getFileName());
                    }
                    byte[] read = bytes;
                    String failure = problem;
                    Platform.runLater(() -> {
                        if (failure != null) {
                            host.setError(failure);
                        } else {
                            onBytes.accept(read);
                        }
                    });
                },
                "git-patch-read");
        reader.setDaemon(true);
        reader.start();
    }

    private void chooseTarget(byte[] patch) {
        record Target(String label, boolean cached) {}
        List<Target> targets = List.of(
                new Target(tr("diff.patch.applyWorktree"), false), new Target(tr("diff.patch.applyIndex"), true));
        QuickOpen<Target> picker = new QuickOpen<>(
                tr("git.applyPatch.title"),
                tr("git.applyPatch.prompt"),
                () -> targets,
                Target::label,
                t -> "",
                t -> apply(patch, t.cached()));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Applies {@code patch} in the active repository: to the index only ({@code cached}) or the working tree. */
    void apply(byte[] patch, boolean cached) {
        if (git.reportIfNoRepo()) {
            return;
        }
        run(git.repoRoot(), patch, cached, false);
    }

    private void run(Path root, byte[] patch, boolean cached, boolean threeWay) {
        boolean writesFiles = !cached || threeWay;
        // git patches the files on disk: an unsaved edit of one of them would be left behind, then conflict
        // with the patched file on its next save.
        if (writesFiles && !git.saveUnsaved(root, touchedPaths(patch))) {
            return;
        }
        Consumer<Consumer<ProcessRunner.Result>> operation = done ->
                git.service().applyPatch(root, patch, cached, threeWay, git.running(new String[] {"apply"}, done));
        Consumer<ProcessRunner.Result> report = result -> report(root, patch, cached, threeWay, result);
        if (writesFiles) {
            git.aroundWorkingTreeMutation(root, operation, report, null);
        } else {
            operation.accept(result -> {
                report.accept(result);
                git.afterMutation();
            });
        }
    }

    private void report(Path root, byte[] patch, boolean cached, boolean threeWay, ProcessRunner.Result result) {
        switch (PatchOutcome.classify(result.ok(), result.out(), result.err())) {
            case APPLIED -> host.setStatus(tr(cached ? "status.git.patchAppliedIndex" : "status.git.patchApplied"));
            // The files are written, with markers: the Commit window lists them as conflicted after the refresh.
            case CONFLICTS -> host.setError(tr("status.git.patchConflicts"));
            case REJECTED -> {
                if (ProcessRunner.CANCELLED.equals(result.message())) {
                    host.setStatus(tr("status.git.cancelled"));
                } else if (threeWay) {
                    git.gitError(tr("status.git.patchFailed"), result.message());
                } else if (confirmThreeWay.test(result.message())) {
                    run(root, patch, cached, true);
                } else {
                    host.setError(tr("status.git.patchFailed"));
                }
            }
        }
    }

    /** Shows git's reason for rejecting the patch and offers the three-way retry. */
    private boolean askThreeWay(String reason) {
        ButtonType retry = new ButtonType(tr("dialog.patch.threeWay"), ButtonBar.ButtonData.OK_DONE);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION, "", retry, ButtonType.CANCEL));
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.git.title"));
        alert.setHeaderText(tr("dialog.patch.rejected"));
        String body = reason == null || reason.isBlank() ? tr("status.git.patchFailed") : reason.strip();
        TextArea area = new TextArea(body + "\n\n" + tr("dialog.patch.threeWayHint"));
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(52);
        area.setPrefRowCount(Math.min(14, (int) body.lines().count() + 4));
        area.getStyleClass().add("git-error-text");
        alert.getDialogPane().setContent(area);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == retry;
    }

    private Path askPatchFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("git.applyPatch.title"));
        chooser.getExtensionFilters()
                .addAll(
                        new FileChooser.ExtensionFilter("Patch (*.patch, *.diff)", "*.patch", "*.diff"),
                        new FileChooser.ExtensionFilter("*", "*.*", "*"));
        Path root = git.repoRoot();
        if (root != null && Files.isDirectory(root)) {
            chooser.setInitialDirectory(root.toFile());
        }
        File picked = chooser.showOpenDialog(host.window());
        return picked == null ? null : picked.toPath();
    }

    /**
     * Whether a buffer named {@code name} with {@code content} is a patch: it must parse as a unified diff,
     * and either be named like one or begin like one. A Markdown file that merely quotes a diff is not.
     */
    static boolean isPatch(String name, String content) {
        if (content == null || content.isBlank() || PatchParser.parse(content).isEmpty()) {
            return false;
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".patch") || lower.endsWith(".diff")) {
            return true;
        }
        String head = content.stripLeading();
        return head.startsWith("diff --git ")
                || head.startsWith("--- ")
                || head.startsWith("Index: ")
                || (head.startsWith("From ") && head.contains("\ndiff --git "));
    }

    /** A buffer's text as the bytes a save would write: its line ending and charset, so the patch still fits. */
    static byte[] patchBytes(EditorBuffer buffer) {
        return EditorConfigCharset.encode(
                LineEndings.apply(buffer.getContent(), buffer.getLineEnding()), buffer.getEffectiveCharset());
    }

    /**
     * The repo-relative paths a patch touches, for saving their open buffers first. Empty — which
     * {@link GitCoordinator#saveUnsaved} reads as "every buffer of the repository" — when the patch cannot be
     * parsed as text (a binary patch).
     */
    static List<String> touchedPaths(byte[] patch) {
        Set<String> paths = new LinkedHashSet<>();
        String text = new String(patch, java.nio.charset.StandardCharsets.ISO_8859_1); // paths are bytes: keep them
        for (PatchParser.FilePatch file : PatchParser.parse(text)) {
            for (String path : new String[] {file.oldPath(), file.newPath()}) {
                if (path != null && !path.isBlank() && !"/dev/null".equals(path)) {
                    paths.add(new String(
                            path.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        return new ArrayList<>(paths);
    }

    // --- create ------------------------------------------------------------------------------------

    /** {@code git.createPatch}: pick what the patch holds, then where it goes. */
    void createPatchCommand() {
        if (git.reportIfNoRepo()) {
            return;
        }
        Path root = git.repoRoot();
        record Choice(String label, Source source) {}
        List<Choice> choices = List.of(
                new Choice(tr("git.createPatch.staged"), Source.STAGED),
                new Choice(tr("git.createPatch.unstaged"), Source.UNSTAGED),
                new Choice(tr("git.createPatch.commit"), Source.COMMIT));
        QuickOpen<Choice> picker = new QuickOpen<>(
                tr("git.createPatch.title"), tr("git.createPatch.prompt"), () -> choices, Choice::label, c -> "", c -> {
                    if (c.source() == Source.COMMIT) {
                        chooseCommit(root);
                    } else {
                        chooseDestination(destination -> create(root, c.source(), null, destination));
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    private void chooseCommit(Path root) {
        git.service().log(root, null, 300, commits -> {
            if (commits.isEmpty()) {
                host.setStatus(tr("status.git.patchEmpty"));
                return;
            }
            QuickOpen<GitService.Commit> picker = new QuickOpen<>(
                    tr("git.createPatch.title"),
                    tr("git.createPatch.commitPrompt"),
                    () -> commits,
                    c -> c.shortHash() + "  " + c.subject(),
                    c -> c.author() + " · " + c.date(),
                    c -> c.hash() + " " + c.subject() + " " + c.author(),
                    c -> createCommitPatch(root, c.hash()));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Creates a patch of commit {@code hash} of the active repository (an entry point for the Git Log). */
    void createCommitPatch(String hash) {
        if (!git.reportIfNoRepo()) {
            createCommitPatch(git.repoRoot(), hash);
        }
    }

    /** As above, in the repository the commit was listed from. */
    void createCommitPatch(Path root, String hash) {
        chooseDestination(destination -> create(root, Source.COMMIT, hash, destination));
    }

    private void chooseDestination(Consumer<Destination> onPick) {
        record Choice(String label, Destination destination) {}
        List<Choice> choices = List.of(
                new Choice(tr("git.createPatch.toFile"), Destination.FILE),
                new Choice(tr("git.createPatch.toBuffer"), Destination.BUFFER));
        QuickOpen<Choice> picker = new QuickOpen<>(
                tr("git.createPatch.title"),
                tr("git.createPatch.destinationPrompt"),
                () -> choices,
                Choice::label,
                c -> "",
                c -> onPick.accept(c.destination()));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /**
     * Reads the patch for {@code source} ({@code hash} for a commit) from the repository at {@code root} and
     * delivers it to {@code destination}: a file the user names, or a new untitled buffer.
     */
    void create(Path root, Source source, String hash, Destination destination) {
        if (root == null) {
            return;
        }
        Consumer<GitService.PatchText> deliver = patch -> {
            if (!patch.ok()) {
                git.gitError(tr("status.git.patchCreateFailed"), patch.error());
            } else if (patch.bytes().length == 0) {
                host.setStatus(tr(
                        source == Source.STAGED
                                ? "status.diff.noStagedChanges"
                                : source == Source.UNSTAGED
                                        ? "status.diff.noWorkingChanges"
                                        : "status.git.patchEmpty"));
            } else {
                deliver(patch, suggestedName(source, hash), destination);
            }
        };
        switch (source) {
            case STAGED -> git.service().diffPatch(root, true, deliver);
            case UNSTAGED -> git.service().diffPatch(root, false, deliver);
            case COMMIT -> git.service().commitPatch(root, hash, deliver);
        }
    }

    static String suggestedName(Source source, String hash) {
        return switch (source) {
            case STAGED -> "staged.patch";
            case UNSTAGED -> "changes.patch";
            case COMMIT -> GitFormat.shortHash(hash) + ".patch";
        };
    }

    private void deliver(GitService.PatchText patch, String name, Destination destination) {
        if (destination == Destination.BUFFER) {
            if (openTab == null) {
                return;
            }
            EditorBuffer buffer = new EditorBuffer();
            buffer.setDisplayName(name);
            buffer.setContent(patch.text());
            openTab.accept(buffer);
            host.setStatus(tr("status.git.patchOpened", name));
            return;
        }
        Path target = chooseSaveFile.apply(name);
        if (target == null) {
            return;
        }
        Thread writer = new Thread(
                () -> {
                    String failure = null;
                    try {
                        Files.write(target, patch.bytes());
                    } catch (IOException | RuntimeException e) {
                        failure = e.getMessage() == null ? "" : e.getMessage();
                    }
                    String problem = failure;
                    Platform.runLater(() -> host.setStatus(
                            problem == null
                                    ? tr("status.diff.patchSaved", target.getFileName())
                                    : tr("status.diff.patchFailed", problem)));
                },
                "git-patch-write");
        writer.setDaemon(true);
        writer.start();
    }

    private Path askSaveFile(String name) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("git.createPatch.title"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Patch (*.patch)", "*.patch"));
        chooser.setInitialFileName(name);
        File picked = chooser.showSaveDialog(host.window());
        return picked == null ? null : picked.toPath();
    }
}
