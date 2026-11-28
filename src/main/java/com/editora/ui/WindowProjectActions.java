package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;

import com.editora.config.Project;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A window's project actions: opening a folder as a project, switching to, closing and deleting projects, the project
 * chrome and the window title, and following the files the Project tree (or a tab's Rename) moves or deletes, in
 * this window or across all of them. It also opens a recent file or a bookmark in the window of the project it
 * belongs to. The state stays on {@link MainController}; this resolves it when called. It lived inside
 * {@code MainController}; it is a file of its own only because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowProjectActions {

    private final MainController mc;

    WindowProjectActions(MainController mc) {
        this.mc = mc;
    }

    /** Tracks the last-applied project-support state to detect off→on transitions (reveal the panel). */
    private boolean projectSupportApplied;

    /**
     * In the global ("No Project") window, points the Project tool window at the active file's parent
     * folder and titles it "Current Folder" — so it doubles as a file explorer for the file being edited,
     * tracking tab switches. No-op when a project is open (the tree stays the project root and the title
     * "Project") or while a remote folder is mounted (that root is set explicitly). With no local file
     * active it shows the placeholder and the default title.
     */
    void updateProjectFolderView() {
        if (mc.windowProject != null
                || !mc.projectsEnabled()
                || (mc.remoteCoordinator != null && mc.remoteCoordinator.isMounted())) {
            return; // a project / remote mount owns the tree root + title — leave them alone
        }
        EditorBuffer b = mc.activeBuffer();
        Path path = b == null ? null : b.getPath();
        Path dir = (path != null && com.editora.vfs.Vfs.isLocal(path))
                ? path.toAbsolutePath().getParent()
                : null;
        if (!com.editora.config.PathKeys.samePath(mc.projectRoot(), dir)) {
            mc.setProjectRoot(dir); // null => placeholder (unsaved/Welcome tab); rebuilds only on change
        }
        if (mc.projectToolWindow != null) {
            mc.projectToolWindow.setTitle(tr(dir != null ? "toolwindow.currentFolder" : "toolwindow.project"));
        }
    }

    /** Creates/reuses a project for {@code root} and switches to it. */
    void openProjectRoot(Path root) {
        String name = root.getFileName() == null
                ? root.toString()
                : root.getFileName().toString();
        switchToProject(mc.projects.createOrGet(name, root));
    }

    void refreshProjectPanelList() {
        // The toolbar combo shows THIS window's project as selected (each window is one project).
        if (mc.toolbar.toolbarProjectCombo != null) {
            mc.toolbar.toolbarProjectCombo.setProjects(mc.projects.list(), mc.projectKey);
        }
    }

    /** Display name for a bookmark/note project key: {@code ""} → "General", else the project's name (fallback:
     *  the raw key, e.g. a deleted project's id). Used to label the per-project groups in the Bookmarks/Notes panels. */
    String projectDisplayName(String key) {
        if (key == null || key.isEmpty()) {
            return tr("scope.general");
        }
        return mc.config.projects().list().stream()
                .filter(p -> p.id().equals(key))
                .map(Project::name)
                .findFirst()
                .orElse(key);
    }

    /** The projects list with a leading "No Project" entry (returns to the global session). */
    List<Project> projectsWithNoProject() {
        List<Project> all = new ArrayList<>();
        all.add(ProjectCombo.NO_PROJECT);
        all.addAll(mc.projects.list());
        return all;
    }

    /** Shows/hides all project UI per the "Enable projects" setting: toolbar icon + combo, tool window. */
    void applyProjectSupport() {
        if (mc.projectPanel != null) { // else applied when the panel is built
            mc.projectPanel.setShowHidden(mc.config.getSettings().isProjectShowHidden()); // hidden-files toggle
            mc.projectPanel.applyMapCardAids(); // semantic highlighting, inlay hints, sticky scroll in the Map's cards
            mc.projectPanel.setRespectGitignore(
                    mc.config.getSettings().isSearchRespectGitignore()); // skip .gitignore'd files in the filter search
        }
        boolean on = mc.projectsEnabled();
        // Turning projects off just hides the project chrome — each window keeps editing its open files (windows no
        // longer share one session that could be "stranded"). On the next launch with projects off, WindowManager opens
        // a single global window. The project toolbar group is also hidden by Simple UI mode (it stays a project even
        // though the selector is hidden); kept here so this later pass doesn't re-show it over applySimpleMode.
        boolean showProjectGroup = on && !mc.chrome.simpleModeActive();
        mc.openFolderButton.setVisible(showProjectGroup);
        mc.openFolderButton.setManaged(showProjectGroup);
        mc.toolbar.toolbarProjectCombo.setVisible(showProjectGroup);
        mc.toolbar.toolbarProjectCombo.setManaged(showProjectGroup);
        mc.toolbar.projectToolbarGap.setVisible(showProjectGroup);
        mc.toolbar.projectToolbarGap.setManaged(showProjectGroup);
        // Project tool window: force-hide when off; reveal once on the off→on transition; otherwise
        // leave it to the user (so it can be hidden/shown normally while projects are enabled).
        if (!on) {
            mc.toolWindows.setVisible(mc.projectToolWindow, false);
        } else if (!projectSupportApplied) {
            mc.toolWindows.setVisible(mc.projectToolWindow, true);
        }
        projectSupportApplied = on;
    }

    /**
     * Opens (or focuses) {@code p}'s window. Each project lives in its own window now, so this no longer
     * swaps the current window's session in place — it brings up that project's window, leaving this one
     * as-is. The "No Project" sentinel (empty id) opens/focuses the global window.
     */
    boolean switchToProject(Project p) {
        if (p == null) {
            return false;
        }
        if (mc.windowManager == null) {
            return false; // multi-window not wired (shouldn't happen in the running app)
        }
        if (p.id().equals(mc.projectKey)) {
            return true; // already this window (e.g. the combo re-selected the current project)
        }
        mc.windowManager.openOrFocus(p.id().isEmpty() ? null : p);
        // This window didn't change; snap its combo back to its own project.
        refreshProjectPanelList();
        return true;
    }

    /** Closes this project window (with confirmation via the save prompts). */
    void closeProject() {
        if (mc.windowProject == null) {
            mc.setStatus(tr("status.noProjectOpen"));
            return;
        }
        // Closing a project loses nothing (its files are saved or asked about, its session kept): not red.
        ButtonType close = Dialogs.action(tr("dialog.close"));
        ButtonType cancel = Dialogs.cancel();
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION, tr("dialog.closeProject.body", mc.windowProject.name()), close, cancel));
        confirm.initOwner(mc.stage);
        confirm.setTitle(tr("dialog.closeProject.title"));
        confirm.setHeaderText(null);
        if (Dialogs.showAndWait(confirm).orElse(cancel) != close) {
            return;
        }
        if (mc.windowManager != null) {
            mc.windowManager.requestClose(mc); // persists + closes this window
        }
    }

    /** Deletes this window's project (with confirmation). */
    void deleteProject() {
        deleteProject(mc.windowProject);
    }

    /**
     * Deletes a project from the shared list (with confirmation). Only the project entry and its saved
     * session are removed — which is all the confirmation says. The folder and its files on disk are left
     * untouched, and so are the project's personal notes, bookmarks, breakpoints and Local History: they
     * stay in their buckets under the project's id, which is derived from its name and folder, so adding the
     * folder again under the same name re-attaches them. (They used to be dropped here, unannounced and with
     * no way back; Local History in particular is the safety net for the project's files, not list
     * bookkeeping.) If the project has an open window, it is closed first (saving its dirty buffers); a
     * cancelled save prompt aborts the deletion.
     */
    void deleteProject(@Nullable Project p) {
        if (p == null || p.id().isEmpty()) {
            mc.setStatus(tr("status.noProjectToDelete"));
            return;
        }
        if (!Confirm.ask(mc.stage, deleteProjectQuestion(p.name()))) {
            return;
        }
        // Close the project's window first (persisting its session + saving dirty buffers); a cancelled
        // save prompt leaves everything intact. No-op when the project has no open window.
        if (mc.windowManager != null && !mc.windowManager.closeWindowForKey(p.id())) {
            return;
        }
        // A write for this project's session may still be sitting in the config-writer queue; it would land
        // after the delete below and re-create the file. Drop it first.
        mc.config.shared().cancelPendingWrite(mc.projects.stateFile(p));
        mc.projects.delete(p.id()); // drops it from the index + open set + deletes its state file
        mc.projects.save();
        // Nothing else: the notes/bookmark/breakpoint/history buckets are user data and stay (see above).
        refreshProjectPanelList();
        mc.setStatus(tr("status.deletedProject", p.name()));
    }

    /**
     * Window title: the active file's name + path, then the project (if any), then "Editora" —
     * e.g. {@code ~/src/app/Main.java — MyProject — Editora}. A {@code •} prefix marks an unsaved
     * buffer; an untitled buffer shows its display name; with no buffer (Welcome) just the app/project.
     */
    void updateWindowTitle() {
        String app = mc.windowProject == null ? "Editora" : mc.windowProject.name() + " — Editora";
        EditorBuffer b = mc.activeBuffer();
        if (b == null) {
            // A non-buffer tab: show an image viewer's file path; else just the app name (Welcome, etc.).
            Path img = MainController.tabPath(mc.editorArea.selectedTab());
            mc.stage.setTitle(
                    img != null ? com.editora.config.PathDisplay.collapseHome(img.toString()) + " — " + app : app);
            return;
        }
        Path p = b.getPath();
        // getDisplayName() is null for a plain untitled buffer (it's only set by --new-file=name /
        // templates), and concatenating that rendered a literal "null — … — Editora" title. Fall back to
        // the buffer's tab title ("untitled"), so the title bar always agrees with the tab.
        String file = p != null
                ? com.editora.config.PathDisplay.collapseHome(p.toString())
                : b.getDisplayName() != null ? b.getDisplayName() : b.getTitle();
        // The OS title bar renders plain text only (no color/italic/weight, so the tab's amber-italic
        // dirty styling can't be mirrored here) — use a prominent leading "●" as the unsaved marker so
        // the modified state is visible even in Zen mode, where the tab strip is hidden.
        mc.stage.setTitle((b.isDirty() ? "● " : "") + file + " — " + app);
    }

    /** Syncs editor/session state after the Project tree renames a file on disk (old → target). */
    void onProjectFileRenamed(Path old, Path target) {
        onProjectFileRenamed(old, target, false);
    }

    void onProjectFileRenamed(Path old, Path target, boolean oldLspAlreadyClosed) {
        if (mc.windowManager != null) {
            mc.windowManager.fileRenamedAcrossWindows(mc, old, target, oldLspAlreadyClosed);
        } else {
            remapProjectFileLocal(old, target, oldLspAlreadyClosed);
        }
        mc.git.filesChangedByEditora(); // a tracked file renamed or moved: the Commit window's rows changed
    }

    void remapProjectFileLocal(Path old, Path target, boolean oldLspAlreadyClosed) {
        mc.fileWorkflows.invalidatePendingWrite(old);
        Tab tab = mc.tabForPath(old); // before the cache goes: `old` no longer exists, so it cannot be re-resolved
        com.editora.config.PathKeys.invalidateCanonicalCache(); // stale resolutions must not survive a move (#680)
        mc.indexCoordinator.onFileRenamed(old, target);
        RestorePlaceholder placeholder = RestorePlaceholder.of(tab);
        if (tab != null && placeholder != null) {
            // A restored tab not built yet: its read, when it comes, must find the file where it now is. The
            // path-keyed stores are remapped by the caller; the tab has no buffer-side state to move.
            placeholder.relocate(target);
            mc.tabHeaders.contentHeader(tab, placeholder);
            TabHeaders.fileTooltip(tab, target);
        } else if (tab != null && MainController.bufferOf(tab) instanceof EditorBuffer buffer) {
            buffer.setPath(target);
            mc.editorSettings.applyEditorConfig(buffer);
            mc.lspCoordinator.documentPathChanged(buffer, old, oldLspAlreadyClosed);
            mc.debugCoordinator.bufferPathChanged(buffer, old);
            mc.updateTabMeta(tab, buffer);
            if (buffer == mc.activeBuffer()) {
                mc.breadcrumb.setActiveFile(target);
                mc.statusBar.refresh();
            }
        } else {
            // A moved/renamed *directory*: remap every open buffer whose file lived under `old` to the
            // corresponding path under `target` (e.g. drag-moving a folder in the Project tree). Nothing lies under
            // a renamed file, so no look at the disk on the FX thread tells the two apart (PERF-01, Pauta 6.2.0).
            Path oldNorm = old.toAbsolutePath().normalize();
            for (Tab t : mc.editorArea.tabs()) {
                EditorBuffer b = MainController.bufferOf(t);
                RestorePlaceholder unbuilt = b == null ? RestorePlaceholder.of(t) : null;
                Path p = b != null ? b.getPath() : unbuilt == null ? null : unbuilt.path();
                if (p == null) {
                    continue;
                }
                Path pn = p.toAbsolutePath().normalize();
                if (unbuilt != null) {
                    if (com.editora.config.PathKeys.isUnder(pn, oldNorm)) {
                        // Not built yet: its read, when it comes, must find the file where it now is.
                        Path moved = target.resolve(oldNorm.relativize(pn));
                        unbuilt.relocate(moved);
                        TabHeaders.fileTooltip(t, moved);
                        migrateFileState(p, moved);
                    }
                    continue;
                }
                if (b != null && com.editora.config.PathKeys.isUnder(pn, oldNorm)) { // never on another file system
                    Path moved = target.resolve(oldNorm.relativize(pn));
                    mc.fileWorkflows.invalidatePendingWrite(p);
                    b.setPath(moved);
                    mc.editorSettings.applyEditorConfig(b);
                    mc.lspCoordinator.documentPathChanged(b, p, oldLspAlreadyClosed);
                    mc.debugCoordinator.bufferPathChanged(b, p);
                    mc.updateTabMeta(t, b);
                    migrateFileState(p, moved);
                    if (b == mc.activeBuffer()) {
                        mc.breadcrumb.setActiveFile(moved);
                        mc.statusBar.refresh();
                    }
                }
            }
        }
        migrateFileState(old, target);
        // The saved HTTP responses follow the file (or every .http file under a moved folder), shown or not: the
        // tree's rename never reached the history, so closing the tab or quitting lost it.
        mc.httpClient.onFileMoved(old, target);
        mc.htmlPreview.renamed(old, target); // nothing served from the editor under the old name (E201); globes
        mc.requestSave();
        mc.setStatus(tr("status.renamedTo", target.getFileName()));
    }

    /** Everything stored under the renamed path (a file, or a folder and the files below it) follows it. */
    private void migrateFileState(Path old, Path target) {
        RenamedFileState.rekeyWorkspace(mc.config.getWorkspaceState(), old, target);
        if (mc.recentFiles != null) {
            mc.recentFiles.remove(old);
        }
        mc.bookmarkCoordinator.pathRenamed(old, target);
        mc.notesCoordinator.pathRenamed(old, target);
        mc.debugCoordinator.pathRenamed(old, target);
        mc.historyCoordinator.pathRenamed(old, target);
    }

    /** Syncs editor/session state after the Project tree deletes a file on disk. */
    void onProjectFileDeleted(Path path) {
        onProjectFileDeleted(path, buffer -> false); // nobody was asked: an unsaved buffer stays open
    }

    /** The Project tree deleted {@code path} after its preflight asked every window's unsaved owners. */
    void onProjectTreeFileDeleted(Path path) {
        onProjectFileDeleted(path, buffer -> mc.projectDeletes != null && mc.projectDeletes.covers(buffer));
    }

    void onProjectFileDeleted(Path path, java.util.function.Predicate<EditorBuffer> discardApproved) {
        if (mc.windowManager != null) {
            mc.windowManager.fileDeletedAcrossWindows(path, discardApproved);
        } else {
            removeProjectFileLocal(path, discardApproved);
        }
        mc.git.filesChangedByEditora(); // a tracked file deleted: the Commit window's rows changed
    }

    /** The window that holds {@code buffer} in a tab; this one when no other does. */
    MainController ownerOf(EditorBuffer buffer) {
        return mc.windowManager == null ? mc : mc.windowManager.ownerOf(buffer, mc);
    }

    boolean holdsBufferLocal(EditorBuffer buffer) {
        return mc.tabFor(buffer) != null;
    }

    /** Brings this window and {@code buffer}'s tab forward, so a prompt about it is asked where it lives. */
    void revealBufferLocal(EditorBuffer buffer) {
        Tab tab = mc.tabFor(buffer);
        if (tab != null) {
            mc.editorArea.select(tab);
        }
        if (mc.stage != null && !mc.stage.isFocused()) {
            mc.stage.toFront();
            mc.stage.requestFocus();
        }
    }

    void removeProjectFileLocal(Path path, java.util.function.Predicate<EditorBuffer> discardApproved) {
        com.editora.config.PathKeys.invalidateCanonicalCache(); // (#680)
        mc.indexCoordinator.onFileDeleted(path);
        for (EditorBuffer buffer : mc.buffersAtOrUnderLocal(path)) {
            Tab tab = mc.tabFor(buffer);
            if (tab == null) {
                continue;
            }
            // Last line of defence, whichever path deleted the file: unsaved text is dropped only when its
            // owner was asked about exactly this state. Otherwise the tab stays, as the only copy left.
            if ((buffer.isDirty() || mc.fileWorkflows.hasPendingSave(buffer)) && !discardApproved.test(buffer)) {
                buffer.markUnsaved();
                mc.updateTabMeta(tab, buffer);
                mc.setStatus(tr("status.fileGoneKeptUnsaved", buffer.getTitle()));
                continue;
            }
            mc.editorArea.remove(tab);
        }
        WorkspaceState ws = mc.config.getWorkspaceState();
        String key = path.toString();
        ws.getFoldedRegions().remove(key);
        ws.getMarkdownViewModes().remove(key);
        ws.getMarkdownPreviewLayouts().remove(key);
        ws.getSpellLanguages().remove(key);
        ws.getFileEncodings().remove(key);
        ws.getReadOnlyFiles().remove(key);
        if (mc.recentFiles != null) {
            mc.recentFiles.remove(path);
        }
        mc.requestSave();
        mc.setStatus(tr("status.deleted", path.getFileName()));
    }

    /**
     * Opens a bookmark/note's file at 0-based {@code line} in the window for {@code projectKey} ({@code ""} =
     * general/no-project). When the entry belongs to <em>this</em> window's project (or Projects are off, or
     * there's no window manager), it opens in place — the original behavior. Otherwise it hands off to
     * {@link WindowManager#openInWindow} so the user lands in that project's window (focused if already open,
     * else built), rather than opening the file out of context in the current window.
     */
    void openInProjectWindow(String projectKey, Path file, int line) {
        String key = projectKey == null ? "" : projectKey;
        if (mc.windowManager == null || !mc.projectsEnabled() || key.equals(mc.config.currentProjectKey())) {
            openAndNavigate(file, line);
            return;
        }
        mc.windowManager.openInWindow(key, file, line);
    }

    /**
     * Cross-window landing for a bookmark/note activated from another project's bucket: opens {@code file} in
     * <em>this</em> (now-focused) window and jumps to 0-based {@code line}. Called by
     * {@link WindowManager#openInWindow} on the target window's controller once it exists and is restored.
     */
    void openAndNavigate(Path file, int line) {
        if (line < 0) {
            if (mc.projectToolWindow != null) {
                mc.toolWindows.open(mc.projectToolWindow, false);
            }
            mc.projectPanel().revealPathInTree(file);
            return;
        }
        mc.fileWorkflows.openThen(file, () -> mc.navigateToLine(mc.navigation.areaLine(mc.activeBuffer(), line)));
    }

    /**
     * A Project Map declaration's Open Narrowed: opens {@code file} and narrows it to {@code block} (0-based
     * document lines), as the Structure window's Narrow to Fold Region does for a picked symbol.
     */
    void openNarrowedFromMap(Path file, com.editora.text.FoldRegions.Region block) {
        mc.fileWorkflows.openThen(file, () -> {
            EditorBuffer buffer = mc.activeBuffer();
            Path open = buffer == null ? null : buffer.getPath();
            if (buffer != null
                    && open != null
                    && com.editora.config.PathKeys.samePath(
                            open.toAbsolutePath().normalize(),
                            file.toAbsolutePath().normalize())) {
                mc.editing.narrowToFoldRegionAt(buffer, block.startLine(), block);
            }
        });
    }

    /** The words of the question before the project named {@code name} is deleted from the project list. */
    static Confirm.Question deleteProjectQuestion(String name) {
        return new Confirm.Question(
                tr("dialog.deleteProject.title"),
                null,
                tr("dialog.deleteProject.body", name),
                tr("confirm.action.delete"));
    }

    /**
     * Opens a file chosen from the recent-files list, project-aware: a file under another project than this
     * window's opens in that project's window (focused, or built), where it was last left. Files that belong
     * to no project, or to this window's, open here.
     *
     * <p>It used to compare with the {@code ProjectManager}'s active project (the window opened last) and,
     * after focusing the other project's window, open the file in this one anyway.
     */
    void openRecent(Path file) {
        Project owner = owningProject(file);
        if (owner != null && mc.windowManager != null && !owner.id().equals(mc.projectKey)) {
            mc.windowManager.openRecentInWindow(owner.id(), file);
            return;
        }
        mc.fileWorkflows.openPath(file);
    }

    /** Opens {@code file} in this window where it was last left; {@link WindowManager#openRecentInWindow}'s landing. */
    void openRecentHere(Path file) {
        mc.fileWorkflows.openPath(file);
    }

    /** The enabled project whose root is the closest ancestor of {@code file}, or {@code null}. */
    @Nullable
    Project owningProject(Path file) {
        if (mc.projects == null || !mc.projectsEnabled()) {
            return null;
        }
        Path abs = file.toAbsolutePath().normalize();
        Project best = null;
        for (Project p : mc.projects.list()) {
            if (p.contains(abs)
                    && (best == null || p.root().length() > best.root().length())) {
                best = p; // prefer the deepest (most specific) matching project root
            }
        }
        return best;
    }

    /**
     * {@code project.editSettings}: opens this project's committed {@code .editora/settings.json}, seeding an
     * explanatory example when it doesn't exist yet.
     *
     * <p>Seeded rather than created empty because the file's whole value is being discoverable and editable
     * by hand — an empty buffer tells nobody which keys exist.
     */
    void editProjectSettings() {
        Path root = mc.windowProjectRoot();
        if (root == null) {
            mc.setError(tr("status.project.settingsNeedProject"));
            return;
        }
        // Converted and seeded off the FX thread (PERF-01, Pauta 6.2.0); opened when that is done.
        Path file = com.editora.config.ProjectSettings.fileFor(root);
        String template = tr("project.settings.template");
        com.editora.config.ProjectSettingsFile.later(
                () -> {
                    com.editora.config.ProjectSettings.migrateLegacyForEditing(root);
                    if (!java.nio.file.Files.exists(file)) {
                        java.nio.file.Files.createDirectories(file.getParent());
                        java.nio.file.Files.writeString(file, template);
                    }
                },
                failure -> {
                    if (failure != null) {
                        mc.setError(tr("status.project.settingsFailed", failure.getMessage()));
                        return;
                    }
                    mc.fileWorkflows.openPath(file);
                    mc.lspCoordinator.reloadProjectSettings(); // an edit here should take effect without a restart
                });
    }
}
