package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.Project;
import com.editora.vfs.RemoteConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Welcome page's row text: folders, recent-project order, and saved-site details. Expected folders are
 *  built with {@link Path} so the test reads the same with Windows separators. */
class WelcomeTextTest {

    private static final Path HOME = Path.of("/home/ana");

    @Test
    void aFileInAProjectShowsItsFolderRelativeToTheProjectRoot() {
        Path root = Path.of("/home/ana/src/site");
        assertEquals(
                Path.of("src", "pages").toString(),
                WelcomeText.folderText(root.resolve("src/pages/index.astro"), root, HOME));
        // At the root the chip already says where it is.
        assertEquals("", WelcomeText.folderText(root.resolve("README.md"), root, HOME));
    }

    @Test
    void aFileUnderHomeStartsWithATilde() {
        assertEquals(
                "~" + java.io.File.separator + "notes",
                WelcomeText.folderText(Path.of("/home/ana/notes/todo.md"), null, HOME));
        assertEquals("~", WelcomeText.folderText(Path.of("/home/ana/todo.md"), null, HOME));
        // A sibling whose name merely starts with the home path is not under it.
        assertEquals(
                Path.of("/home/anabel").toString(), WelcomeText.folderText(Path.of("/home/anabel/x.txt"), null, HOME));
    }

    @Test
    void aFileElsewhereShowsItsFullFolder() {
        String nginx = Path.of("/etc/nginx").toString(); // the platform's own separators
        assertEquals(nginx, WelcomeText.folderText(Path.of("/etc/nginx/nginx.conf"), null, HOME));
        assertEquals(nginx, WelcomeText.folderText(Path.of("/etc/nginx/nginx.conf"), null, null));
    }

    @Test
    void projectsComeInTheOrderOfTheirMostRecentFile() {
        Project a = new Project("a", "a", "/w/a");
        Project b = new Project("b", "b", "/w/b");
        Project c = new Project("c", "c", "/w/c");
        Map<Path, Project> owners = Map.of(Path.of("/w/b/1"), b, Path.of("/w/a/2"), a, Path.of("/w/b/3"), b);
        List<Path> recent = List.of(Path.of("/w/b/1"), Path.of("/x/loose"), Path.of("/w/a/2"), Path.of("/w/b/3"));

        // b (newest file), then a; c has no recent file and keeps its saved place after them.
        assertEquals(List.of(b, a, c), WelcomeText.recentProjects(List.of(a, b, c), recent, owners::get));
    }

    @Test
    void theDefaultSshPortIsNotPrinted() {
        assertEquals("deploy@prod", WelcomeText.withoutDefaultPort("deploy@prod:22"));
        assertEquals("deploy@prod:2222", WelcomeText.withoutDefaultPort("deploy@prod:2222"));
        assertEquals("", WelcomeText.withoutDefaultPort(null));
    }

    @Test
    void aSiteShowsOnlyWhatItsNameDoesNotAlreadySay() {
        RemoteConnection.AuthMethod auth = RemoteConnection.AuthMethod.values()[0];
        // Unlabelled: the name is user@host, so only a non-default port is added.
        assertEquals("", WelcomeText.siteDetail(new RemoteConnection("box", 22, "adl", auth, null, null, null)));
        assertEquals(":2222", WelcomeText.siteDetail(new RemoteConnection("box", 2222, "adl", auth, null, null, null)));
        // Labelled: say where the label points.
        assertEquals(
                "deploy@prod.example.com",
                WelcomeText.siteDetail(
                        new RemoteConnection("prod.example.com", 22, "deploy", auth, null, "Prod", null)));
        assertEquals(
                "prod:2200", WelcomeText.siteDetail(new RemoteConnection("prod", 2200, "", auth, null, "Prod", null)));
    }
}
