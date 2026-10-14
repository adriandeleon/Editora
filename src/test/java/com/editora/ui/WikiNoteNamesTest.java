package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.markdown.MarkdownLint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the linter finds the file a wiki link names (LINK003). */
class WikiNoteNamesTest {

    @TempDir
    Path root;

    private Path file(String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "# " + relative + "\n\n## Install\n");
    }

    @Test
    void aNoteIsFoundByNameWhereverItIsUnlessTwoAnswer() throws Exception {
        Path here = file("notes/today.md");
        Path beside = file("notes/Guide.md");
        Path elsewhere = file("docs/deep/setup.markdown");
        file("a/twin.md");
        file("b/twin.md");
        Path localTwin = file("notes/twin.md");
        Path image = Files.writeString(root.resolve("docs/logo.png"), "png");
        Files.writeString(root.resolve(".gitignore"), "build/\n");
        file("build/generated.md");

        WikiNoteNames names = WikiNoteNames.of(root, true);
        Path folder = here.getParent();
        assertEquals(beside, names.resolve(folder, "guide"), "extension optional, case ignored");
        assertEquals(beside, names.resolve(folder, "Guide.md"));
        assertEquals(elsewhere, names.resolve(folder, "setup"), "the one file of that name in the project");
        assertEquals(image, names.resolve(folder, "logo.png"));
        assertEquals(localTwin, names.resolve(folder, "twin"), "the note beside the document wins");
        assertNull(names.resolve(root.resolve("docs"), "twin"), "three namesakes elsewhere: no answer");
        assertNull(names.resolve(folder, "nothing"));
        assertNull(names.resolve(folder, "generated"), "ignored files are not notes");
        assertEquals(elsewhere, names.resolve(folder, "docs/deep/setup"), "a path from the project root");
        assertEquals(beside, names.resolve(root, "notes/Guide"));
        assertNull(names.resolve(folder, "docs/nowhere"));
        assertFalse(names.stale(root));
        assertTrue(names.stale(root.resolve("notes")), "another project's listing");
    }

    @Test
    void theLinterIsToldWhatAWikiLinkNames() throws Exception {
        Path here = file("notes/today.md");
        file("notes/guide.md");
        file("docs/setup.md");
        Path folder = here.getParent();
        MarkdownLint.LinkTargets inProject =
                MarkdownLintActions.linkTargets(folder, () -> WikiNoteNames.of(root, true));
        assertEquals("guide.md", inProject.wikiFile("guide"));
        assertEquals(Path.of("..", "docs", "setup.md").toString(), inProject.wikiFile("setup"));
        assertNull(inProject.wikiFile("missing"));
        assertTrue(inProject.anchors(inProject.wikiFile("setup")).contains("install"));

        // Without a project only the document's own folder can be vouched for; elsewhere is unknown.
        MarkdownLint.LinkTargets alone = MarkdownLintActions.linkTargets(folder);
        assertEquals("guide.md", alone.wikiFile("guide"));
        assertEquals("", alone.wikiFile("setup"));
    }
}
