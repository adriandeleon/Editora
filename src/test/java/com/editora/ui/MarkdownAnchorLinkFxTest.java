package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <em>Markdown: Open Link</em> on a link to a heading goes to the heading — in the same document, in another
 * file, and in a buffer that was never saved. These are the links a generated table of contents consists
 * of; they used to be refused with "This link can't be opened".
 */
@Tag("fx")
class MarkdownAnchorLinkFxTest {

    private static final String README = "# Readme\n\n- [Set up](#set-up)\n- [Again](#set-up-1)\n- [Gone](#gone)\n"
            + "- [Guide install](guide.md#install)\n- [Guide nowhere](guide.md#nowhere)\n\n"
            + "## Set up\n\ntext\n\n## Set up\n\nmore\n\n"
            + "Wiki: [[guide]] and [[Guide#Install|how to install]] and [[nowhere]] and [[#Set up]]\n";
    private static final String GUIDE = "# Guide\n\n" + "filler\n".repeat(60) + "\n## Install\n\nsteps\n";

    @TempDir
    static Path work;

    private static FxWindowFixture fx;
    private static MainController c;
    private static FileWorkflowCoordinator workflows;
    private static CommandRegistry registry;
    private static Path readme;
    private static Path guide;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        c = fx.controller;
        workflows = FxTestSupport.field(c, "fileWorkflows");
        registry = FxTestSupport.field(c, "registry");
        Path dir = work.toAbsolutePath().normalize();
        readme = Files.writeString(dir.resolve("README.md"), README);
        guide = Files.writeString(dir.resolve("guide.md"), GUIDE);
        settle();
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void anAnchorLinkMovesTheCaretToItsHeading() throws Exception {
        open(readme);
        openLinkAt(readme, "Set up](");
        assertEquals(8, caretLine(readme), "## Set up");
        openLinkAt(readme, "Again](");
        assertEquals(12, caretLine(readme), "the second heading of that name");
    }

    @Test
    void anAnchorNothingAnswersToIsReportedAndTheCaretStays() throws Exception {
        open(readme);
        int before = openLinkAt(readme, "Gone](");
        assertEquals(before, caretLine(readme));
        assertEquals(tr("status.link.noAnchor", "#gone"), status());
    }

    @Test
    void aLinkToAHeadingOfAnotherFileOpensItThere() throws Exception {
        for (boolean alreadyOpen : new boolean[] {true, false, false}) {
            open(readme);
            if (alreadyOpen) {
                open(guide);
                select(readme);
            }
            openLinkAt(readme, "Guide install](");
            awaitLoaded(guide);
            assertEquals(guide, FxTestSupport.callOnFx(() -> active().getPath()));
            assertEquals(63, caretLine(guide), "## Install, guide already open=" + alreadyOpen);
            closeTab(guide);
        }
    }

    @Test
    void aMissingHeadingInAnotherFileStillOpensTheFile() throws Exception {
        open(readme);
        openLinkAt(readme, "Guide nowhere](");
        awaitLoaded(guide);
        assertEquals(guide, FxTestSupport.callOnFx(() -> active().getPath()));
        assertEquals(tr("status.link.noAnchor", "#nowhere"), status());
        closeTab(guide);
    }

    @Test
    void aWikiLinkOpensItsNoteAndGoesToTheHeadingItNames() throws Exception {
        open(readme);
        openLinkAt(readme, "[[guide");
        awaitLoaded(guide);
        assertEquals(guide, FxTestSupport.callOnFx(() -> active().getPath()), "the note of that name beside it");
        closeTab(guide);

        open(readme);
        openLinkAt(readme, "[[Guide#Inst");
        awaitLoaded(guide);
        assertEquals(guide, FxTestSupport.callOnFx(() -> active().getPath()));
        assertEquals(63, caretLine(guide), "## Install, named by its title");
        closeTab(guide);

        open(readme);
        openLinkAt(readme, "[[#Set");
        assertEquals(8, caretLine(readme), "a heading of the same document");
    }

    @Test
    void aWikiLinkThatNamesNothingIsReportedAndSwitchedOffItIsNotALink() throws Exception {
        open(readme);
        int before = openLinkAt(readme, "[[nowh");
        assertEquals(before, caretLine(readme));
        assertEquals(tr("status.wikilink.unresolved", "nowhere"), status());
        assertEquals(readme, FxTestSupport.callOnFx(() -> active().getPath()));

        FxTestSupport.runOnFx(() -> WikiLinks.enabled = false);
        try {
            openLinkAt(readme, "[[guide");
            assertEquals(readme, FxTestSupport.callOnFx(() -> active().getPath()), "nothing was opened");
            assertEquals(tr("status.wikilink.unresolved", "guide"), status());
        } finally {
            FxTestSupport.runOnFx(() -> WikiLinks.enabled = true);
        }
    }

    @Test
    void anUnsavedMarkdownBufferFollowsItsOwnAnchors() throws Exception {
        EditorBuffer scratch = FxTestSupport.callOnFx(() -> {
            registry.run("file.new");
            EditorBuffer b = active();
            b.setContent("[to b](#b)\n\n# A\n\ntext\n\n# B\n");
            b.setLanguageOverride("markdown");
            b.getArea().moveTo(3);
            return b;
        });
        assertNotNull(scratch);
        assertTrue(FxTestSupport.callOnFx(scratch::isMarkdown));
        FxTestSupport.runOnFx(() -> registry.run("markdown.openLink"));
        settle();
        assertEquals(6, (int) FxTestSupport.callOnFx(() -> scratch.getArea().getCurrentParagraph()));
        FxTestSupport.runOnFx(scratch::markClean);
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Puts the caret on {@code needle} in {@code file}, runs Open Link, and returns the caret's line before. */
    private static int openLinkAt(Path file, String needle) throws Exception {
        int before = FxTestSupport.callOnFx(() -> {
            select0(file);
            EditorBuffer b = bufferFor(file);
            int at = b.getArea().getText().indexOf(needle);
            assertTrue(at >= 0, needle);
            b.getArea().moveTo(at + 1);
            int line = b.getArea().getCurrentParagraph();
            registry.run("markdown.openLink");
            return line;
        });
        settle();
        return before;
    }

    private static String status() throws Exception {
        return FxTestSupport.callOnFx(
                () -> FxTestSupport.<Label>field(FxTestSupport.<StatusBar>field(c, "statusBar"), "echo")
                        .getText());
    }

    private static Tab tabFor(Path file) {
        return (Tab) FxTestSupport.call(c, "tabForPath", new Class<?>[] {Path.class}, file);
    }

    private static EditorBuffer bufferFor(Path file) {
        Tab t = tabFor(file);
        return t != null && t.getUserData() instanceof EditorBuffer b ? b : null;
    }

    private static EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(c, "activeBuffer", new Class<?>[] {});
    }

    private static int caretLine(Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = bufferFor(file);
            return b == null ? -1 : b.getArea().getCurrentParagraph();
        });
    }

    private static void select0(Path file) {
        EditorArea area = FxTestSupport.field(c, "editorArea");
        area.select(tabFor(file));
    }

    private static void select(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> select0(file));
        settle();
    }

    private static void open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        awaitLoaded(file);
    }

    private static void closeTab(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Tab t = tabFor(file);
            if (t != null) {
                FxTestSupport.call(c, "closeTab", new Class<?>[] {Tab.class}, t);
            }
        });
        settle();
    }

    private static void awaitLoaded(Path file) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (true) {
            boolean done = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = bufferFor(file);
                return b != null && workflows.loadingBuffers.isEmpty() && !b.isLoading();
            });
            if (done) {
                break;
            }
            assertTrue(System.nanoTime() < deadline, "load never finished: " + file);
            Thread.sleep(5);
        }
        settle();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            FxTestSupport.drainFx();
        }
        Thread.sleep(150);
        for (int i = 0; i < 6; i++) {
            FxTestSupport.drainFx();
        }
    }
}
