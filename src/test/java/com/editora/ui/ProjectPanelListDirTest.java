package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Project tree's folder listing ({@code ProjectPanel.listDir}): hidden names only when asked, and the repository's
 * own {@code .git} never, the way Find in Files and the Map's listings already leave it out.
 */
class ProjectPanelListDirTest {

    @TempDir
    Path temp;

    @Test
    void hiddenNamesShowWhenAskedButTheRepositorysGitFolderNever() throws Exception {
        Files.createDirectory(temp.resolve(".git"));
        Files.createDirectory(temp.resolve(".github"));
        Files.createDirectory(temp.resolve("src"));
        Files.writeString(temp.resolve(".editorconfig"), "root = true\n");
        Files.writeString(temp.resolve("README.md"), "# r\n");

        assertEquals(List.of("src", "README.md"), names(ProjectPanel.listDir(temp, false)));
        assertEquals(List.of(".github", "src", ".editorconfig", "README.md"), names(ProjectPanel.listDir(temp, true)));
    }

    @Test
    void aWorktreesGitFileIsLeftOutToo() throws Exception {
        Files.writeString(temp.resolve(".git"), "gitdir: /elsewhere/.git/worktrees/w\n");
        Files.writeString(temp.resolve(".gitignore"), "target/\n");

        assertEquals(List.of(".gitignore"), names(ProjectPanel.listDir(temp, true)));
    }

    private static List<String> names(List<ProjectPanel.PathEntry> entries) {
        return entries.stream().map(e -> e.path().getFileName().toString()).toList();
    }
}
