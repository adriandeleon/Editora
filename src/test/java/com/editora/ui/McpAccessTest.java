package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.agent.AcpFsGuard;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which files an MCP client may open and which it may write, decided against a real directory tree. */
class McpAccessTest {

    @TempDir
    Path dir;

    private Path project;
    private Path config;

    @BeforeEach
    void layOut() throws IOException {
        project = Files.createDirectories(dir.resolve("project"));
        config = Files.createDirectories(dir.resolve("config"));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Main.java"), "class Main {}\n");
        Files.createDirectories(dir.resolve("project2"));
        Files.writeString(dir.resolve("project2/other.txt"), "another project\n");
        Files.writeString(dir.resolve("secret.txt"), "secret\n");
    }

    // --- open_file ----------------------------------------------------------------------------------

    @Test
    void aFileOfTheProjectMayBeOpenedAlsoOneThatIsStillToBeCreated() {
        assertNull(McpAccess.openRefusal(project, project.resolve("src/Main.java"), false));
        assertNull(McpAccess.openRefusal(project, project.resolve("src/../src/Main.java"), false));
        assertNull(McpAccess.openRefusal(project, project.resolve("not/there/yet.txt"), false));
        assertNull(McpAccess.openRefusal(project, project, false), "the folder itself is not outside");
    }

    @Test
    void aFileOutsideTheProjectMayNotHoweverItsPathIsSpelled() {
        for (Path outside : new Path[] {
            dir.resolve("secret.txt"),
            project.resolve("../secret.txt"),
            project.resolve("src/../../secret.txt"),
            dir.resolve("project2/other.txt"), // a sibling whose name merely starts with the project's
            dir,
            dir.resolve("absent.txt")
        }) {
            String refusal = McpAccess.openRefusal(project, outside, false);
            assertNotNull(refusal, outside.toString());
            assertTrue(refusal.contains("outside the project folder"), refusal);
            assertTrue(refusal.contains(outside.toString()), "the client is told which path: " + refusal);
        }
    }

    @Test
    void aLinkIsJudgedByWhereItLeads() throws IOException {
        Path fileLink = project.resolve("notes.txt");
        Path folderLink = project.resolve("vendor");
        Path projectLink = dir.resolve("shortcut");
        try {
            Files.createSymbolicLink(fileLink, dir.resolve("secret.txt"));
            Files.createSymbolicLink(folderLink, dir.resolve("project2"));
            Files.createSymbolicLink(projectLink, project);
        } catch (IOException | UnsupportedOperationException noLinks) {
            Assumptions.abort("symbolic links cannot be created here");
        }

        assertNotNull(McpAccess.openRefusal(project, fileLink, false), "a link to a file elsewhere");
        assertNotNull(McpAccess.openRefusal(project, folderLink.resolve("other.txt"), false));
        assertNotNull(McpAccess.openRefusal(project, folderLink.resolve("new.txt"), false), "nor a new file there");
        // The other way round: the project reached through a link is still the project.
        assertNull(McpAccess.openRefusal(project, projectLink.resolve("src/Main.java"), false));
        assertNull(McpAccess.openRefusal(projectLink, project.resolve("src/Main.java"), false));
    }

    @Test
    void aWindowWithoutAProjectOnlyReselectsWhatIsOpen() {
        String refusal = McpAccess.openRefusal(null, dir.resolve("secret.txt"), false);
        assertNotNull(refusal);
        assertTrue(refusal.contains("no project open"), refusal);

        assertNull(McpAccess.openRefusal(null, dir.resolve("secret.txt"), true));
        assertNull(McpAccess.openRefusal(project, dir.resolve("secret.txt"), true), "the user opened it themselves");
    }

    // --- edit_buffer / save_buffer ------------------------------------------------------------------

    @Test
    void ordinaryFilesAndUntitledBuffersMayBeWritten() {
        assertNull(McpAccess.writeRefusal(config, null), "an untitled buffer");
        assertNull(McpAccess.writeRefusal(config, project.resolve("src/Main.java")));
        assertNull(McpAccess.writeRefusal(config, dir.resolve("secret.txt")), "a file the user opened elsewhere");
        assertNull(McpAccess.writeRefusal(null, project.resolve("src/Main.java")));
        // Names that only resemble repository metadata.
        assertNull(McpAccess.writeRefusal(config, project.resolve(".gitignore")));
        assertNull(McpAccess.writeRefusal(config, project.resolve(".github/workflows/ci.yml")));
        assertNull(McpAccess.writeRefusal(config, project.resolve("git/notes.txt")));
        assertNull(McpAccess.writeRefusal(config, dir.resolve("config2/settings.json")), "a sibling of the config dir");
    }

    @Test
    void theConfigurationDirectoryIsNeverWritten() {
        for (Path inside : new Path[] {
            config.resolve("settings.json"),
            config.resolve("plugins/evil/plugin.json"),
            config.resolve("../config/keymap.json"),
            config.resolve("new-file.json")
        }) {
            String refusal = McpAccess.writeRefusal(config, inside);
            assertNotNull(refusal, inside.toString());
            assertTrue(refusal.contains("configuration directory"), refusal);
        }
        // Also when it sits inside the project (a project opened on the home folder).
        Path nested = project.resolve(".editora-config");
        assertNotNull(McpAccess.writeRefusal(nested, nested.resolve("settings.json")));
    }

    @Test
    void versionControlMetadataIsNeverWrittenWhereverTheRepositoryIs() throws IOException {
        Files.createDirectories(project.resolve(".git/hooks"));
        for (Path metadata : new Path[] {
            project.resolve(".git/hooks/pre-commit"),
            project.resolve(".git/config"),
            project.resolve(".git"), // a gitlink file
            project.resolve("sub/module/.GIT/config"),
            dir.resolve("elsewhere/.hg/hgrc"),
            dir.resolve("elsewhere/.svn/entries"),
            dir.resolve("elsewhere/.jj/repo/config.toml")
        }) {
            String refusal = McpAccess.writeRefusal(config, metadata);
            assertNotNull(refusal, metadata.toString());
            assertTrue(refusal.contains("version-control metadata"), refusal);
            assertTrue(AcpFsGuard.isVcsMetadata(metadata));
        }
        assertFalse(AcpFsGuard.isVcsMetadata(project.resolve("src/Main.java")));
        assertFalse(AcpFsGuard.isVcsMetadata(null));
    }

    @Test
    void metadataReachedThroughALinkUnderAnotherNameIsStillMetadata() throws IOException {
        Files.createDirectories(project.resolve(".git/hooks"));
        Path alias = project.resolve("scripts");
        try {
            Files.createSymbolicLink(alias, project.resolve(".git/hooks"));
        } catch (IOException | UnsupportedOperationException noLinks) {
            Assumptions.abort("symbolic links cannot be created here");
        }

        assertNotNull(McpAccess.writeRefusal(config, alias.resolve("pre-commit")));
        assertFalse(AcpFsGuard.hasVcsMetadataName(alias.resolve("pre-commit")), "its spelling alone looks innocent");
    }
}
