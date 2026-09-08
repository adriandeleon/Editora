package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Project-tree folder Git comparisons are present and route the selected directory unchanged. */
@Tag("fx")
class ProjectPanelGitFolderMenuFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void folderOffersHeadBranchTagAndRevisionComparisons(@TempDir Path folder) throws Exception {
        Map<String, Path> calls = new ConcurrentHashMap<>();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel created = new ProjectPanel(f -> {}, (a, b) -> {}, f -> {}, f -> false);
            created.setFileActions(new ProjectPanel.FileActions() {
                @Override
                public boolean localHistoryEnabled() {
                    return true;
                }

                @Override
                public void showLocalHistory(Path file) {}

                @Override
                public boolean gitAvailable() {
                    return true;
                }

                @Override
                public void gitShowFileHistory(Path file) {}

                @Override
                public void gitCompareWithHead(Path file) {
                    calls.put("head", file);
                }

                @Override
                public void gitCompareWithBranch(Path file) {
                    calls.put("branch", file);
                }

                @Override
                public void gitCompareWithTag(Path file) {
                    calls.put("tag", file);
                }

                @Override
                public void gitCompareWithRevision(Path file) {
                    calls.put("revision", file);
                }

                @Override
                public void gitAnnotate(Path file) {}

                @Override
                public void gitStage(Path file) {}

                @Override
                public void gitUnstage(Path file) {}

                @Override
                public void gitRevert(Path file) {}

                @Override
                public void gitAddToGitignore(Path file) {}
            });
            return created;
        });

        ContextMenu context = FxTestSupport.callOnFx(() -> (ContextMenu) FxTestSupport.call(
                panel,
                "contextMenuFor",
                new Class<?>[] {TreeItem.class, boolean.class, boolean.class},
                new TreeItem<>(folder),
                true,
                false));
        Menu git = (Menu) context.getItems().stream()
                .filter(item -> item instanceof Menu && tr("project.menu.git").equals(item.getText()))
                .findFirst()
                .orElseThrow();

        fire(git, "project.menu.git.compareHead");
        fire(git, "project.menu.git.compareBranch");
        fire(git, "project.menu.git.compareTag");
        fire(git, "project.menu.git.compareRevision");

        assertEquals(Map.of("head", folder, "branch", folder, "tag", folder, "revision", folder), calls);
    }

    private static void fire(Menu menu, String labelKey) throws Exception {
        MenuItem item = menu.getItems().stream()
                .filter(candidate -> tr(labelKey).equals(candidate.getText()))
                .findFirst()
                .orElseThrow();
        FxTestSupport.runOnFx(item::fire);
    }
}
