package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.editorconfig.EditorConfigCharset;
import com.editora.lsp.WorkspaceEditHazards;
import com.editora.terminal.RunningProgram;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words of every question before a destructive action that builds a dialog of its own come from a static
 * function that needs no dialog and no JavaFX toolkit (CONF-12, Pauta 6.0.1): each one is called here with sample
 * values. The thing named, the count or the list is in the words, and every button is worded by the catalog with
 * the action's own verb, never OK or Yes.
 */
class ConfirmWordsTest {

    @BeforeAll
    static void catalog() {
        com.editora.i18n.Messages.init("en"); // the words are the catalog's; nothing else is started
    }

    /** The action labels of a question: each is the catalog's word for its key, and none is a stock OK, Yes or Cancel. */
    private static void assertActions(List<String> actual, String... keys) {
        assertEquals(List.of(keys).stream().map(key -> tr(key)).toList(), actual);
        for (String label : actual) {
            assertFalse(label.isBlank());
            for (String stock :
                    List.of("OK", "Yes", "Cancel", tr("dialog.ok"), tr("dialog.yes"), tr("dialog.cancel"))) {
                assertFalse(label.equals(stock), "an action is named by its verb, not " + stock);
            }
        }
    }

    private static void assertAction(Confirm.Question question, String key) {
        assertActions(List.of(question.action()), key);
    }

    private static void assertCatalogCancel(Confirm.Choice choice) {
        assertEquals(tr("dialog.cancel"), choice.cancel(), "Cancel in the catalog's word");
    }

    @Test
    void stoppingARunningBuildNamesTheToolAndTheTask() {
        Confirm.Question q = BuildCoordinator.stopAndRunQuestion("Maven", "verify");
        assertEquals(tr("dialog.buildStopAndRun.title", "Maven"), q.title());
        assertEquals(tr("dialog.buildStopAndRun.header", "Maven"), q.header());
        assertEquals(tr("dialog.buildStopAndRun.body", "verify"), q.message());
        assertTrue(q.title().contains("Maven")
                && q.header().contains("Maven")
                && q.message().contains("verify"));
        assertAction(q, "dialog.buildStopAndRun.accept");
    }

    @Test
    void deployConflictsListTheFilesAndCountThem() {
        Confirm.Choice q = DeploymentCoordinator.conflictsQuestion(List.of("index.html", "css/site.css"));
        assertEquals(tr("dialog.deploy.conflicts.title"), q.title());
        assertEquals(tr("dialog.deploy.conflicts.header", 2), q.header());
        assertTrue(q.header().contains("2"));
        assertTrue(q.message().contains("index.html\ncss/site.css"), q.message());
        assertActions(q.answers(), "dialog.deploy.overwrite", "dialog.deploy.keepHost");
        assertCatalogCancel(q);

        List<String> many = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> "f" + i + ".txt")
                .toList();
        String listed = DeploymentCoordinator.conflictsQuestion(many).message();
        assertTrue(listed.contains("f11.txt") && !listed.contains("f12.txt"), "a dozen are listed: " + listed);
        assertTrue(listed.contains(tr("dialog.deploy.more", 3)), "and how many more: " + listed);
    }

    @Test
    void unsavedChangesNameTheTabAndItsOwnSaveAction() {
        Confirm.Choice q = CloseCoordinator.unsavedQuestion("notes.md", "dialog.save");
        assertEquals(tr("dialog.unsaved.title"), q.title());
        assertEquals(tr("dialog.unsaved.header", "notes.md"), q.header());
        assertTrue(q.header().contains("notes.md"));
        assertNull(q.message(), "the header is the whole question");
        assertActions(q.answers(), "dialog.save", "dialog.discard");
        assertCatalogCancel(q);

        // A tab that is not a file names its own save action.
        assertEquals(
                List.of(tr("dialog.close"), tr("dialog.discard")),
                CloseCoordinator.unsavedQuestion("Request 1", "dialog.close").answers());
    }

    @Test
    void quittingNamesTheTerminalsItEnds() {
        Confirm.Choice plain = CloseCoordinator.quitQuestion(List.of());
        assertEquals(tr("dialog.quit.title"), plain.title());
        assertEquals(tr("dialog.quit.header"), plain.header());
        assertNull(plain.message(), "nothing running: nothing more to say");
        assertActions(plain.answers(), "dialog.quit.button");
        assertCatalogCancel(plain);

        Confirm.Choice busy = CloseCoordinator.quitQuestion(List.of("Terminal 1 (vim)", "Terminal 2 (top)"));
        assertEquals(tr("terminal.closeWindow.running", "• Terminal 1 (vim)\n• Terminal 2 (top)"), busy.message());
        assertTrue(busy.message().contains("Terminal 1 (vim)") && busy.message().contains("Terminal 2 (top)"));
    }

    @Test
    void aRejectedPushNamesTheBranchAndItsUpstream() {
        Confirm.Choice q = GitBranchCoordinator.rejectedPushQuestion("feature/x", "origin/feature/x");
        assertEquals(tr("dialog.pushRejected.title"), q.title());
        assertNull(q.header());
        assertEquals(tr("dialog.pushRejected.content", "feature/x", "origin/feature/x"), q.message());
        assertTrue(q.message().contains("feature/x") && q.message().contains("origin/feature/x"));
        assertActions(q.answers(), "dialog.pushRejected.pull", "dialog.pushRejected.force");
        assertCatalogCancel(q);

        // With no upstream known, the sentence says "The remote branch".
        assertEquals(
                tr("dialog.pushRejected.content", "main", tr("dialog.pushRejected.remote")),
                GitBranchCoordinator.rejectedPushQuestion("main", " ").message());
    }

    @Test
    void debuggingAnotherFileNamesTheLiveLaunchAndTheFile() {
        String stopOnly = DebugRetargetPrompt.question(false, "dialog.debug.retargetPaused", "Server", "Client.java");
        assertEquals(tr("dialog.debug.retargetPaused", "Server", "Client.java"), stopOnly);
        assertTrue(stopOnly.contains("Server") && stopOnly.contains("Client.java"));
        Confirm.Choice stop = DebugRetargetPrompt.words(stopOnly, false);
        assertEquals(tr("dialog.debug.retargetTitle"), stop.title());
        assertNull(stop.header());
        assertEquals(stopOnly, stop.message());
        assertActions(stop.answers(), "dialog.debug.retargetStop");
        assertCatalogCancel(stop);

        String withBoth =
                DebugRetargetPrompt.question(true, "dialog.debug.retargetPaused", "Server", Path.of("Client.java"));
        assertEquals(tr("dialog.debug.retargetBothQuestion", "Server", "Client.java"), withBoth);
        Confirm.Choice both = DebugRetargetPrompt.words(withBoth, true);
        assertEquals(tr("dialog.debug.retargetBothTitle"), both.title());
        assertEquals(withBoth, both.message());
        assertActions(both.answers(), "dialog.debug.retargetBoth", "dialog.debug.retargetStop");
    }

    @Test
    void aFileChangedOnDiskNamesTheFileAndSaysWhatReloadDiscards() {
        Confirm.Choice dirty = ExternalChangePrompt.question("notes.md", true);
        assertEquals(tr("dialog.externalChange.title"), dirty.title());
        assertEquals(tr("dialog.externalChange.header", "notes.md"), dirty.header());
        assertTrue(dirty.header().contains("notes.md"));
        assertEquals(tr("dialog.externalChange.dirty"), dirty.message());
        assertActions(dirty.answers(), "dialog.externalChange.reload");
        assertEquals(tr("dialog.externalChange.keepMine"), dirty.cancel(), "its Cancel keeps the edits");

        Confirm.Choice clean = ExternalChangePrompt.question("notes.md", false);
        assertEquals(tr("dialog.externalChange.clean"), clean.message());
        assertActions(clean.answers(), "dialog.externalChange.reload");
        assertEquals(tr("dialog.externalChange.keep"), clean.cancel());

        Confirm.Choice save = ExternalChangePrompt.saveConflictQuestion("remote.conf");
        assertEquals(tr("dialog.externalChange.title"), save.title());
        assertEquals(tr("dialog.externalChange.header", "remote.conf"), save.header());
        assertEquals(tr("dialog.externalChange.dirty"), save.message());
        assertActions(save.answers(), "dialog.externalChange.reload", "dialog.externalChange.keepMine");
        assertCatalogCancel(save);
    }

    @Test
    void savingOverBinaryDataOrAReadOnlyFileNamesTheFile() {
        Confirm.Question mixed = FileWorkflowCoordinator.mixedBinaryQuestion("blob.dat", "LF");
        assertEquals(tr("dialog.saveMixedBinary.title"), mixed.title());
        assertEquals(tr("dialog.saveMixedBinary.header", "blob.dat"), mixed.header());
        assertEquals(tr("dialog.saveMixedBinary.content", "LF"), mixed.message());
        assertTrue(mixed.header().contains("blob.dat") && mixed.message().contains("LF"));
        assertAction(mixed, "dialog.saveMixedBinary.save");

        Confirm.Question readOnly = FileWorkflowCoordinator.replaceReadOnlyQuestion("hosts");
        assertEquals(tr("dialog.saveReadOnly.title"), readOnly.title());
        assertEquals(tr("dialog.saveReadOnly.header", "hosts"), readOnly.header());
        assertTrue(readOnly.header().contains("hosts"));
        assertEquals(tr("dialog.saveReadOnly.content"), readOnly.message());
        assertAction(readOnly, "dialog.saveReadOnly.overwrite");
    }

    /** This file system's root ({@code /}, or the drive on Windows), so the sample paths are absolute everywhere. */
    private static Path root() {
        return Path.of("").toAbsolutePath().getRoot();
    }

    @Test
    void anEditThatDestroysFilesListsEachPathAndWhatHappensToIt() {
        Path home = root().resolve("home").resolve("me");
        Path app = home.resolve("app");
        String sep = app.getFileSystem().getSeparator();
        Confirm.Question q = LspWorkspaceEdits.destructiveEditQuestion(
                List.of(
                        new WorkspaceEditHazards.Hazard(
                                app.resolve("old"), WorkspaceEditHazards.Kind.DELETE_DIRECTORY, null),
                        new WorkspaceEditHazards.Hazard(
                                app.resolve("Main.java"), WorkspaceEditHazards.Kind.OVERWRITE, null)),
                path -> com.editora.config.PathDisplay.collapseHome(path, home.toString()));
        assertEquals(tr("dialog.lsp.destructiveEdit.title"), q.title());
        assertEquals(tr("dialog.lsp.destructiveEdit.header"), q.header());
        assertEquals(
                tr("dialog.lsp.destructiveEdit.deleteFolder", "~" + sep + "app" + sep + "old") + "\n"
                        + tr("dialog.lsp.destructiveEdit.overwrite", "~" + sep + "app" + sep + "Main.java"),
                q.message());
        assertAction(q, "dialog.lsp.destructiveEdit.apply");
    }

    @Test
    void anInterruptedRefactoringListsTenFilesAndCountsTheRest() {
        Path p = root().resolve("p");
        List<Path> two = List.of(p.resolve("A.java"), p.resolve("B.java"));
        Confirm.Choice restore = LspWorkspaceEdits.interruptedEditQuestion(two, false, path -> path);
        assertEquals(tr("dialog.lsp.interruptedEdit.title"), restore.title());
        assertEquals(tr("dialog.lsp.interruptedEdit.header"), restore.header());
        assertEquals(two.get(0) + "\n" + two.get(1), restore.message());
        assertActions(restore.answers(), "dialog.lsp.interruptedEdit.restore", "dialog.lsp.interruptedEdit.keep");
        assertEquals(tr("dialog.lsp.interruptedEdit.later"), restore.cancel(), "its Cancel is Not Now");

        Confirm.Choice remove = LspWorkspaceEdits.interruptedEditQuestion(two, true, path -> path);
        assertEquals(tr("dialog.lsp.interruptedEdit.headerCommitted"), remove.header());
        assertActions(remove.answers(), "dialog.lsp.interruptedEdit.remove", "dialog.lsp.interruptedEdit.keep");

        List<Path> many = java.util.stream.IntStream.range(0, 13)
                .mapToObj(i -> p.resolve("F" + i + ".java"))
                .toList();
        String listed = LspWorkspaceEdits.interruptedEditQuestion(many, false, path -> path)
                .message();
        assertEquals(11, listed.lines().count(), listed);
        assertTrue(
                listed.contains(p.resolve("F9.java").toString())
                        && !listed.contains(p.resolve("F10.java").toString()),
                listed);
        assertTrue(listed.endsWith("\n+3"), "and how many more: " + listed);
    }

    @Test
    void deletingARemoteSiteNamesItAndTheHostToForget() {
        Confirm.Question q = RemoteSitesPane.deleteQuestion("me@box.example");
        assertEquals(tr("settings.remote.remove"), q.title());
        assertNull(q.header());
        assertEquals(tr("settings.remote.deleteConfirm", "me@box.example"), q.message());
        assertTrue(q.message().contains("me@box.example"));
        assertAction(q, "settings.remote.delete");

        String forget = RemoteSitesPane.forgetHostOption("box.example");
        assertEquals(tr("settings.remote.deleteForgetHost", "box.example"), forget);
        assertTrue(forget.contains("box.example"));
    }

    @Test
    void replaceInFilesCountsTheFiles() {
        Confirm.Choice q = SearchCoordinator.replaceInFilesQuestion(7);
        assertNull(q.title(), "it has no title of its own");
        assertNull(q.header());
        assertEquals(tr("search.replaceConfirm", 7), q.message());
        assertTrue(q.message().contains("7 files"), q.message());
        assertTrue(SearchCoordinator.replaceInFilesQuestion(1).message().contains("1 file?"));
        assertActions(q.answers(), "search.replaceAll");
        assertCatalogCancel(q);
    }

    @Test
    void formattingALargeFileNamesItAndBothLineCounts() {
        Confirm.Question q = XmlFormatCoordinator.growthQuestion("pom.xml", 1200, 48000);
        assertEquals(tr("noUndo.op.format"), q.title());
        assertEquals(tr("dialog.xml.formatGrowth.header", "pom.xml"), q.header());
        assertTrue(q.header().contains("pom.xml"));
        assertEquals(
                tr("dialog.xml.formatGrowth.body", String.format("%,d", 1200L), String.format("%,d", 48000L)),
                q.message());
        assertTrue(q.message().contains(String.format("%,d", 48000L)));
        assertAction(q, "dialog.xml.formatGrowth.accept");
    }

    @Test
    void shortcutQuestionsNameTheKeymapTheChordAndTheBindingsLost() {
        Confirm.Question reset = SettingsKeymapsPage.resetAllQuestion("Emacs");
        assertEquals(tr("dialog.shortcut.resetAll.title"), reset.title());
        assertNull(reset.header());
        assertEquals(tr("dialog.shortcut.resetAll.body", "Emacs"), reset.message());
        assertTrue(reset.message().contains("Emacs"));
        assertAction(reset, "confirm.action.reset");

        String line = SettingsKeymapsPage.conflictLine("Ctrl+K", "Kill Line");
        assertEquals("Ctrl+K  —  Kill Line", line);
        Confirm.Question bind =
                SettingsKeymapsPage.bindConflictQuestion("Ctrl+K", List.of(line, "Ctrl+K Ctrl+S  —  Save All"));
        assertEquals(tr("dialog.shortcut.conflict.title"), bind.title());
        assertNull(bind.header());
        assertEquals(
                tr(
                        "dialog.shortcut.conflict.body",
                        "Ctrl+K",
                        "\n   Ctrl+K  —  Kill Line\n   Ctrl+K Ctrl+S  —  Save All"),
                bind.message());
        assertTrue(bind.message().contains("Kill Line") && bind.message().contains("Save All"));
        assertAction(bind, "confirm.action.bind");
    }

    @Test
    void settingsListQuestionsNameTheEntry() {
        String lost = SnippetsEditor.commentsLostMessage("for-each");
        assertEquals(tr("settings.snippet.commentsLost", "for-each"), lost);
        assertTrue(lost.contains("for-each"));
        Confirm.Choice save = SettingsSnippetPages.snippetSaveQuestion(lost);
        assertNull(save.title(), "it has no title of its own");
        assertNull(save.header());
        assertEquals(lost, save.message());
        assertActions(save.answers(), "confirm.action.save");
        assertCatalogCancel(save);

        Confirm.Choice delete = SettingsWindow.deleteExternalToolQuestion("Lint");
        assertNull(delete.title(), "it has no title of its own");
        assertNull(delete.header());
        assertEquals(tr("settings.externalTool.confirmDelete", "Lint"), delete.message());
        assertTrue(delete.message().contains("Lint"));
        assertActions(delete.answers(), "confirm.action.delete");
        assertCatalogCancel(delete);
    }

    @Test
    void recentFilesQuestionsNameTheFile() {
        Path path = Path.of("/home/me/app/notes.md");
        Confirm.Choice remove = MainController.removeRecentQuestion(path);
        assertEquals(tr("dialog.removeRecent.title"), remove.title());
        assertEquals(tr("dialog.removeRecent.header", "notes.md"), remove.header());
        assertTrue(remove.header().contains("notes.md"));
        assertEquals(path.toString(), remove.message(), "the whole path tells same-named files apart");
        assertActions(remove.answers(), "dialog.removeRecent.remove");
        assertCatalogCancel(remove);

        Confirm.Choice clear = MainController.clearRecentQuestion();
        assertEquals(tr("dialog.clearRecent.title"), clear.title());
        assertEquals(tr("dialog.clearRecent.header"), clear.header());
        assertNull(clear.message());
        assertActions(clear.answers(), "dialog.clearRecent.clear");
        assertCatalogCancel(clear);
    }

    @Test
    void removingAPluginNamesItItsFolderAndItsData() {
        PluginCoordinator.Question plain = PluginCoordinator.uninstallQuestion("Word Count", "/cfg/plugins/wc", false);
        assertEquals(PluginCoordinator.Question.Kind.UNINSTALL, plain.kind());
        assertEquals(tr("dialog.plugins.uninstallHeader", "Word Count"), plain.header());
        assertTrue(plain.header().contains("Word Count"));
        assertEquals(tr("dialog.plugins.uninstallBody", "/cfg/plugins/wc"), plain.body());
        assertTrue(plain.body().contains("/cfg/plugins/wc"));
        assertEquals("", plain.capabilities());
        assertActions(List.of(plain.acceptLabel()), "dialog.plugins.removeButton");

        PluginCoordinator.Question withData =
                PluginCoordinator.uninstallQuestion("Word Count", "/cfg/plugins/wc", true);
        assertEquals(
                tr("dialog.plugins.uninstallBody", "/cfg/plugins/wc") + "\n\n" + tr("dialog.plugins.uninstallData"),
                withData.body());
    }

    // --- the questions asked through Confirm: each one's words come from a builder too -----------------------

    /** A plain question: its title, header and sentence as given, and its action the catalog's word for {@code actionKey}. */
    private static void assertQuestion(
            Confirm.Question q, String title, String header, String message, String actionKey) {
        assertEquals(title, q.title());
        assertEquals(header, q.header());
        assertEquals(message, q.message());
        assertAction(q, actionKey);
    }

    @Test
    void bookmarkAndNoteQuestionsNameTheLineOrTheFile() {
        Confirm.Question remove = BookmarkCoordinator.removeQuestion(41);
        assertQuestion(
                remove,
                tr("dialog.removeBookmark.title"),
                null,
                tr("dialog.removeBookmark.body", 42),
                "confirm.action.remove");
        assertTrue(remove.message().contains("42"), "the line as the gutter numbers it: " + remove.message());

        Confirm.Question all = BookmarkCoordinator.deleteFileQuestion("notes.md");
        assertQuestion(
                all,
                tr("bookmarks.deleteFileTitle"),
                null,
                tr("bookmarks.deleteFileBody", "notes.md"),
                "confirm.action.delete");
        assertTrue(all.message().contains("notes.md"));

        assertQuestion(
                NotesCoordinator.deleteQuestion(),
                tr("dialog.note.deleteConfirm.title"),
                null,
                tr("dialog.note.deleteConfirm.content"),
                "confirm.action.delete");
        Confirm.Question notes = NotesCoordinator.deleteAllQuestion("notes.md");
        assertQuestion(
                notes,
                tr("dialog.note.deleteConfirm.title"),
                null,
                tr("dialog.note.deleteAllConfirm.content", "notes.md"),
                "confirm.action.delete");
        assertTrue(notes.message().contains("notes.md"));
    }

    @Test
    void fileQuestionsNameTheFile() {
        Confirm.Question revert = FileWorkflowCoordinator.revertQuestion("draft.txt");
        assertQuestion(
                revert,
                tr("command.file.revert"),
                tr("dialog.revert.header", "draft.txt"),
                tr("dialog.revert.content"),
                "confirm.action.revert");
        assertTrue(revert.header().contains("draft.txt"));

        Confirm.Question overwrite = FileWorkflowCoordinator.overwriteQuestion("report.pdf");
        assertQuestion(
                overwrite,
                tr("dialog.saveAs.overwrite.title"),
                tr("dialog.saveAs.overwrite.header", "report.pdf"),
                tr("dialog.saveAs.overwrite.content"),
                "confirm.action.overwrite");
        assertTrue(overwrite.header().contains("report.pdf"));

        Confirm.Question reopen = EncodingCommands.reopenQuestion("legacy.txt", "latin1");
        assertQuestion(
                reopen,
                tr("command." + EncodingCommands.REOPEN),
                tr("dialog.reopenEncoding.header", "legacy.txt", EditorConfigCharset.displayName("latin1")),
                tr("dialog.reopenEncoding.content"),
                "confirm.action.reopen");
        assertTrue(reopen.header().contains("legacy.txt"));

        assertQuestion(
                DiffViewerPane.applyAllQuestion(),
                tr("diff.applyAll.confirmTitle"),
                null,
                tr("diff.applyAll.confirm"),
                "confirm.action.replace");

        Confirm.Question typst =
                TypstCoordinator.replaceQuestion(List.of(Path.of("/out/report-1.png"), Path.of("/out/report-2.png")));
        assertQuestion(
                typst,
                tr("dialog.typstExport.title"),
                null,
                tr("dialog.typstExport.replaceBody", "report-1.png\nreport-2.png"),
                "confirm.action.replace");
        assertTrue(typst.message().contains("report-2.png"));

        Confirm.Question deploy = DeploymentCoordinator.existingFilesQuestion(List.of("a.txt", "b.txt", "c.txt"));
        assertQuestion(
                deploy,
                tr("dialog.deploy.existing.title"),
                tr("dialog.deploy.existing.header", 3),
                tr("dialog.deploy.existing.content", "a.txt\nb.txt\nc.txt"),
                "dialog.deploy.replace");
        assertTrue(deploy.header().contains("3") && deploy.message().contains("b.txt"));
    }

    @Test
    void projectTreeDeletesSayWhereTheFilesGo() {
        List<Path> one = List.of(Path.of("/p/a.txt"));
        List<Path> two = List.of(Path.of("/p/a.txt"), Path.of("/p/b.txt"));
        assertQuestion(
                ProjectPanel.deleteFilesQuestion(one, 1),
                tr("project.deleteFileTitle"),
                null,
                tr("project.trashFileBody", "a.txt"),
                "confirm.action.delete");
        assertEquals(
                tr("project.deleteMultiBody", 2),
                ProjectPanel.deleteFilesQuestion(two, 0).message());
        assertEquals(
                tr("project.deleteMixedBody", 2, 1, 1),
                ProjectPanel.deleteFilesQuestion(two, 1).message());

        Confirm.Question refused = ProjectPanel.permanentDeleteQuestion(one, "no trash on this volume");
        assertQuestion(
                refused,
                tr("project.fileOperationTitle"),
                null,
                tr("project.trashFailedBody", "a.txt", "no trash on this volume"),
                "confirm.action.delete");
        assertTrue(refused.message().contains("a.txt") && refused.message().contains("no trash on this volume"));
        assertEquals(
                tr("project.trashFailedMultiBody", 2, "denied"),
                ProjectPanel.permanentDeleteQuestion(two, "denied").message());
        assertEquals(refused, ProjectPanel.permanentDeleteQuestion(refused.message()));

        Confirm.Question project = MainController.deleteProjectQuestion("Website");
        assertQuestion(
                project,
                tr("dialog.deleteProject.title"),
                null,
                tr("dialog.deleteProject.body", "Website"),
                "confirm.action.delete");
        assertTrue(project.message().contains("Website"));
    }

    @Test
    void localHistoryAndRecoveryQuestionsCountWhatIsDeleted() {
        Confirm.Question limits = HistoryCoordinator.limitsQuestion(120, 7, false);
        assertQuestion(
                limits,
                tr("dialog.history.limits.title"),
                null,
                tr("dialog.history.limits.confirm", 120, 7),
                "dialog.history.purge.button");
        assertTrue(limits.message().contains("120") && limits.message().contains("7"));
        assertEquals(
                tr("dialog.history.limits.pending") + "\n\n" + tr("dialog.history.limits.confirm", 120, 7),
                HistoryCoordinator.limitsQuestion(120, 7, true).message(),
                "limits that were never confirmed say so first");

        Confirm.Question file = HistoryCoordinator.purgeFileQuestion(9, "notes.md", 0);
        assertQuestion(
                file,
                tr("dialog.history.purge.title"),
                null,
                tr("dialog.history.purgeFile.confirm", 9, "notes.md"),
                "dialog.history.purge.button");
        assertTrue(file.message().contains("notes.md") && file.message().contains("9"));
        assertEquals(
                tr("dialog.history.purgeFile.confirm", 9, "notes.md") + "\n\n"
                        + tr("dialog.history.purgeFile.shared", 2),
                HistoryCoordinator.purgeFileQuestion(9, "notes.md", 2).message());

        assertQuestion(
                HistoryCoordinator.purgeProjectQuestion(300, 12),
                tr("dialog.history.purge.title"),
                null,
                tr("dialog.history.purgeProject.confirm", 300, 12),
                "dialog.history.purge.button");

        Confirm.Question restore = HistoryCoordinator.restoreOverwriteQuestion("notes.md");
        assertQuestion(
                restore,
                tr("history.menu.restore"),
                null,
                tr("history.restoreOverwrite", "notes.md"),
                "confirm.action.overwrite");
        assertTrue(restore.message().contains("notes.md"));

        Confirm.Question discard = RecoveryCoordinator.discardQuestion(3);
        assertQuestion(
                discard,
                tr("recovery.discard.confirmTitle"),
                null,
                tr("recovery.discard.confirm", 3),
                "recovery.discard.button");
        assertTrue(discard.message().contains("3"));
    }

    @Test
    void moreSettingsQuestionsNameTheEntry() {
        Confirm.Question macro = MacroSettingsPane.deleteQuestion("Wrap in div");
        assertQuestion(
                macro,
                tr("settings.macro.deleteConfirmTitle"),
                null,
                tr("settings.macro.deleteConfirm", "Wrap in div"),
                "confirm.action.delete");
        assertTrue(macro.message().contains("Wrap in div"));

        Confirm.Question entry = SettingsWindow.deleteEntryQuestion("sout");
        assertQuestion(
                entry,
                tr("settings.lists.deleteTitle"),
                null,
                tr("settings.lists.deleteConfirm", "sout"),
                "confirm.action.delete");
        assertTrue(entry.message().contains("sout"));

        Confirm.Question template = SettingsSnippetPages.removeTemplateQuestion("Article", "article");
        assertQuestion(
                template,
                tr("settings.template.remove"),
                null,
                tr("settings.template.removeConfirm", "Article", "article.json"),
                "confirm.action.remove");
        assertEquals(
                tr("settings.template.removeConfirm", "article", "article.json"),
                SettingsSnippetPages.removeTemplateQuestion(" ", "article").message(),
                "a template without a name is named by its id");

        Confirm.Question todo = TodoSettingsPane.deleteQuestion("FIXME");
        assertQuestion(
                todo,
                tr("settings.todo.deleteTitle"),
                null,
                tr("settings.todo.deleteConfirm", "FIXME"),
                "settings.todo.delete");
        assertTrue(todo.message().contains("FIXME"));

        Confirm.Question reset = SettingsResetFlow.resetQuestion("settings.json.before-reset-20261115");
        assertQuestion(
                reset,
                tr("settings.reset.title"),
                tr("settings.reset.header"),
                tr("settings.reset.confirm", "settings.json.before-reset-20261115"),
                "settings.reset.button");
        assertTrue(reset.message().contains("settings.json.before-reset-20261115"), "the backup is named");

        Confirm.Question reclaim = SettingsKeymapsPage.reclaimQuestion(
                "Save", List.of(SettingsKeymapsPage.conflictLine("Ctrl+S", "Search")));
        assertQuestion(
                reclaim,
                tr("dialog.shortcut.conflict.title"),
                null,
                tr("dialog.shortcut.reclaim.body", "Save", "\n   Ctrl+S  —  Search"),
                "confirm.action.reset");
        assertTrue(reclaim.message().contains("Save") && reclaim.message().contains("Search"));
    }

    @Test
    void terminalQuestionsNameWhatIsRunning() {
        Confirm.Question close = TerminalCoordinator.closeQuestion("Terminal 1", List.of("vim", "make"));
        assertQuestion(
                close,
                tr("terminal.close.title"),
                null,
                tr("terminal.close.running", "Terminal 1", "vim, make"),
                "terminal.close.confirm");
        assertTrue(close.message().contains("Terminal 1") && close.message().contains("vim, make"));
        List<String> many = java.util.stream.IntStream.range(0, RunningProgram.MAX_NAMED + 2)
                .mapToObj(i -> "p" + i)
                .toList();
        String capped = TerminalCoordinator.closeQuestion("T", many).message();
        assertTrue(capped.contains("p" + (RunningProgram.MAX_NAMED - 1) + ", …"), capped);
        assertFalse(capped.contains("p" + RunningProgram.MAX_NAMED), "the rest are an ellipsis: " + capped);

        Confirm.Question window = TerminalCoordinator.endTerminalsQuestion(
                List.of("Terminal 1 (vim)", "Terminal 2 (top)"), tr("terminal.closeWindow.confirm"));
        assertQuestion(
                window,
                tr("terminal.close.title"),
                tr("terminal.closeWindow.header"),
                "• Terminal 1 (vim)\n• Terminal 2 (top)",
                "terminal.closeWindow.confirm");
    }

    @Test
    void gitQuestionsNameTheBranchTheFilesOrTheCommitLost() {
        assertQuestion(
                GitCoordinator.stageConflictQuestion(List.of("a.txt")),
                tr("dialog.stageConflict.title"),
                null,
                tr("dialog.stageConflict.message", "a.txt"),
                "dialog.stageConflict.action");
        assertEquals(
                tr("dialog.stageConflict.messageMany", 2, "a.txt"),
                GitCoordinator.stageConflictQuestion(List.of("a.txt", "b.txt")).message());

        assertQuestion(
                GitCoordinator.discardQuestion("Discard the changes to a.txt?"),
                tr("dialog.discard.title"),
                null,
                "Discard the changes to a.txt?",
                "dialog.discard");
        Confirm.Question all = GitCoordinator.discardAllQuestion(5, 2);
        assertQuestion(
                all,
                tr("dialog.discardAll.title"),
                null,
                tr("dialog.discardAll.message", 5, 2),
                "dialog.discardAll.action");
        assertTrue(all.message().contains("5") && all.message().contains("2"));

        String rebase = GitCoordinator.operationName(com.editora.git.GitOperation.Kind.REBASE);
        assertQuestion(
                GitCoordinator.skipOperationQuestion(rebase),
                tr("dialog.skipOperation.title", rebase),
                null,
                tr("dialog.skipOperation.message", rebase),
                "dialog.skipOperation.action");
        Confirm.Question abort = GitCoordinator.abortOperationQuestion(com.editora.git.GitOperation.Kind.REBASE);
        assertEquals(tr("dialog.abortOperation.title", rebase), abort.title());
        assertEquals(tr("dialog.abortOperation.rebase"), abort.message(), "what a rebase loses");
        assertEquals(tr("dialog.abortOperation.action", rebase), abort.action());
        assertEquals(
                tr("dialog.abortOperation.merge"),
                GitCoordinator.abortOperationQuestion(com.editora.git.GitOperation.Kind.MERGE)
                        .message());
        assertEquals(
                tr("dialog.abortOperation.cherryPick"),
                GitCoordinator.abortOperationQuestion(com.editora.git.GitOperation.Kind.CHERRY_PICK)
                        .message());
        assertEquals(
                tr("dialog.abortOperation.revert"),
                GitCoordinator.abortOperationQuestion(com.editora.git.GitOperation.Kind.REVERT)
                        .message());

        Confirm.Question side = GitCoordinator.acceptSideQuestion("Accept Theirs", List.of("a.txt"));
        assertEquals(tr("dialog.acceptSide.title"), side.title());
        assertEquals(tr("dialog.acceptSide.message", "Accept Theirs", "a.txt"), side.message());
        assertEquals("Accept Theirs", side.action(), "the button says which side");
        assertEquals(
                tr("dialog.acceptSide.messageMany", "Accept Theirs", 2),
                GitCoordinator.acceptSideQuestion("Accept Theirs", List.of("a.txt", "b.txt"))
                        .message());

        Confirm.Question drop = GitStashCoordinator.dropQuestion("stash@{0}", "WIP on main");
        assertQuestion(
                drop,
                tr("stash.picker.dropTitle"),
                null,
                tr("dialog.stashDrop.confirm", "stash@{0} — WIP on main"),
                "dialog.stashDrop");
        assertEquals(
                tr("dialog.stashDrop.confirm", "stash@{1}"),
                GitStashCoordinator.dropQuestion("stash@{1}", " ").message());

        Confirm.Question undo = GitCommitCoordinator.undoCommitQuestion(false, "abc1234", "Fix typo", "");
        assertQuestion(
                undo,
                tr("dialog.undoCommit.title"),
                null,
                tr("dialog.undoCommit.message", "abc1234", "Fix typo"),
                "dialog.undoCommit.action");
        assertTrue(undo.message().contains("abc1234") && undo.message().contains("Fix typo"));
        assertEquals(
                tr("dialog.undoCommit.pushed", "abc1234", "Fix typo", "origin/main"),
                GitCommitCoordinator.undoCommitQuestion(true, "abc1234", "Fix typo", "origin/main")
                        .message(),
                "a pushed commit is asked about in stronger words");

        Confirm.Question remoteBranch = GitBranchCoordinator.deleteRemoteBranchQuestion("feature/x", "origin");
        assertQuestion(
                remoteBranch,
                tr("dialog.deleteRemoteBranch.title"),
                null,
                tr("dialog.deleteRemoteBranch.confirm", "feature/x", "origin"),
                "dialog.deleteRemoteBranch");
        assertTrue(remoteBranch.message().contains("feature/x")
                && remoteBranch.message().contains("origin"));
        assertQuestion(
                GitBranchCoordinator.removeRemoteQuestion("upstream"),
                tr("remotes.remove"),
                null,
                tr("dialog.remoteRemove.confirm", "upstream"),
                "dialog.remoteRemove");
        assertQuestion(
                GitBranchCoordinator.removeWorktreeQuestion("/w/hotfix", false),
                tr("worktrees.remove"),
                null,
                tr("dialog.worktreeRemove.confirm", "/w/hotfix"),
                "dialog.worktreeRemove");
        assertQuestion(
                GitBranchCoordinator.removeWorktreeQuestion("/w/hotfix", true),
                tr("worktrees.remove"),
                null,
                tr("dialog.worktreeRemove.dirty", "/w/hotfix"),
                "dialog.worktreeRemove.force");

        Confirm.Question tag = GitWindowCoordinator.deleteTagQuestion("v1.0", "/repo");
        assertQuestion(
                tag,
                tr("dialog.deleteTag.title"),
                null,
                tr("dialog.deleteTag.content", "v1.0", "/repo"),
                "dialog.deleteTag.action");
        assertTrue(tag.message().contains("v1.0"));

        com.editora.git.GitService.LeftBehind left = new com.editora.git.GitService.LeftBehind(true, 3, true, 2, 1);
        Confirm.Question hard = GitWindowCoordinator.resetQuestion("hard", "abc1234", "main", Path.of("/repo"), left);
        assertQuestion(
                hard,
                tr("dialog.gitReset.title"),
                null,
                GitHeadMoveWarning.resetPrompt("hard", "abc1234", "main", Path.of("/repo"), left),
                "dialog.gitReset.hard");
        assertTrue(hard.message().contains("abc1234") && hard.message().contains("main"));
        Confirm.Question soft = GitWindowCoordinator.resetQuestion("soft", "abc1234", "main", Path.of("/repo"), left);
        assertEquals(GitHeadMoveWarning.resetPrompt("soft", "abc1234", "main", Path.of("/repo"), left), soft.message());
        assertEquals(tr("dialog.gitReset.title"), soft.action(), "a soft or mixed reset's button is Reset");
        assertQuestion(
                GitWindowCoordinator.checkoutCommitQuestion("abc1234", left),
                tr("dialog.gitCheckout.title"),
                null,
                GitHeadMoveWarning.checkoutPrompt("abc1234", left),
                "dialog.gitCheckout.action");
    }

    @Test
    void aLeftoverSaveBackupNamesTheFileTheBackupAndWhenItWasWritten() {
        Path backup = Path.of("/cfg/save-backups/notes.md.1.bak");
        Confirm.Choice differs = SaveBackupRecovery.question(
                com.editora.io.SaveBackups.State.DIFFERENT, "/home/me/notes.md", backup, "Nov 13, 2026");
        assertEquals(tr("dialog.saveBackup.title"), differs.title());
        assertEquals(tr("dialog.saveBackup.header", "/home/me/notes.md"), differs.header());
        assertEquals(tr("dialog.saveBackup.content", backup, "Nov 13, 2026"), differs.message());
        assertTrue(differs.message().contains(backup.toString())
                && differs.message().contains("Nov 13, 2026"));
        assertActions(differs.answers(), "dialog.saveBackup.restore", "dialog.saveBackup.keep");
        assertEquals(tr("dialog.saveBackup.later"), differs.cancel(), "its Cancel is Decide Later");

        // The file is gone: the backup is the last copy, and its button says it deletes that.
        Confirm.Choice missing = SaveBackupRecovery.question(
                com.editora.io.SaveBackups.State.TARGET_MISSING, "/home/me/notes.md", backup, "Nov 13, 2026");
        assertEquals(tr("dialog.saveBackup.contentMissing", backup, "Nov 13, 2026"), missing.message());
        assertActions(missing.answers(), "dialog.saveBackup.restore", "dialog.saveBackup.delete");

        // A file out of reach, or not known: there is nothing to restore over, so only the delete is offered.
        Confirm.Choice unknown = SaveBackupRecovery.question(
                com.editora.io.SaveBackups.State.UNKNOWN_TARGET, null, backup, "Nov 13, 2026");
        assertEquals(tr("dialog.saveBackup.headerUnknown"), unknown.header());
        assertEquals(
                tr("dialog.saveBackup.contentUnknown", backup.getFileName(), backup, "Nov 13, 2026"),
                unknown.message());
        assertActions(unknown.answers(), "dialog.saveBackup.delete");
        assertEquals(
                tr("dialog.saveBackup.contentUnknown", "sftp://box/notes.md", backup, "Nov 13, 2026"),
                SaveBackupRecovery.question(
                                com.editora.io.SaveBackups.State.REMOTE, "sftp://box/notes.md", backup, "Nov 13, 2026")
                        .message());
    }

    @Test
    void aChoiceGivesItsAnswersByPlaceAndItsCancelFromTheCatalog() {
        Confirm.Choice q = Confirm.choice("Title", null, "Question?", "Overwrite", "Keep");
        assertEquals(List.of("Overwrite", "Keep"), q.answers());
        assertEquals("Overwrite", q.answer(0));
        assertEquals("Keep", q.answer(1));
        assertEquals(tr("dialog.cancel"), q.cancel());
    }
}
