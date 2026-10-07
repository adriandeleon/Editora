package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;

import com.editora.git.GitFileStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which status colour class a tab gets for its file. */
class TabGitStatusTest {

    private static final Path ROOT = Path.of("repo").toAbsolutePath().normalize();

    private static final Map<Path, GitFileStatus> STATUS = Map.of(
            ROOT.resolve("src/Changed.java"), GitFileStatus.MODIFIED,
            ROOT.resolve("New.java"), GitFileStatus.UNTRACKED,
            ROOT.resolve("Both.java"), GitFileStatus.CONFLICT);

    @Test
    void aChangedFileTakesTheClassTheProjectTreeUses() {
        assertEquals("git-status-modified", TabGitStatus.classFor(ROOT.resolve("src/Changed.java"), STATUS));
        assertEquals("git-status-untracked", TabGitStatus.classFor(ROOT.resolve("New.java"), STATUS));
        assertEquals("git-status-conflict", TabGitStatus.classFor(ROOT.resolve("Both.java"), STATUS));
        assertEquals(
                "git-status-modified",
                TabGitStatus.classFor(ROOT.resolve("src/../src/Changed.java"), STATUS),
                "compared by normalized path, like the tree");
    }

    @Test
    void aCleanOrPathlessFileAndAnEmptyStatusGetNone() {
        assertNull(TabGitStatus.classFor(ROOT.resolve("Clean.java"), STATUS));
        assertNull(TabGitStatus.classFor(null, STATUS), "an untitled buffer");
        assertNull(TabGitStatus.classFor(ROOT.resolve("src/Changed.java"), Map.of()), "Git off / Simple UI mode");
        assertNull(TabGitStatus.classFor(ROOT.resolve("src/Changed.java"), null));
    }
}
