package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ListView;

import com.editora.completion.Completion;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Completion in a Markdown buffer, in a real window: a link target lists the folder's files, an anchor the
 * headings, and accepting a row replaces exactly what was typed of the target — not the "word" before the
 * caret, which for {@code docs/gu} would leave {@code docs/docs/guide.md}.
 */
@Tag("fx")
class MarkdownCompletionFxTest {

    private static FxWindowFixture fx;
    private static EditorBuffer buf;
    private static Object actions;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Path dir = Files.createTempDirectory("md-completion").toAbsolutePath().normalize();
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs/guide.md"), "# Guide\n\n## Install it\n");
        Files.writeString(dir.resolve("docs/my notes.md"), "# Notes\n");
        Path file = dir.resolve("README.md");
        Files.writeString(file, "# Readme\n\n## Set up\n\n");
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            javafx.stage.Stage stage = FxTestSupport.field(fx.controller, "stage");
            stage.setWidth(900);
            stage.setHeight(600);
            stage.show();
            fx.controller.openAndNavigate(file, 0);
        });
        settle(1000);
        buf = (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
        assertEquals(file, buf.getPath());
        actions = FxTestSupport.field(buf, "completionActions");
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(buf::markClean);
            fx.dispose();
        }
    }

    private static void settle(int ms) throws Exception {
        Thread.sleep(ms);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    /** Ends the document with {@code tail}, caret after it, and asks for completion as the typing pause does. */
    private static List<Completion> offered(String tail) throws Exception {
        FxTestSupport.runOnFx(() -> {
            buf.cancelCompletion();
            CodeArea a = buf.getArea();
            a.replaceText("# Readme\n\n## Set up\n\n" + tail);
            a.moveTo(a.getLength());
            a.requestFocus();
            FxTestSupport.call(actions, "updateCompletion", new Class<?>[] {CodeArea.class, boolean.class}, a, true);
        });
        settle(100);
        return FxTestSupport.callOnFx(() -> {
            if (!buf.completionShowing()) {
                return List.of();
            }
            Object popup = FxTestSupport.field(actions, "completionPopup");
            ListView<Completion> list = FxTestSupport.field(popup, "list");
            return List.copyOf(list.getItems());
        });
    }

    private static String accept(Completion row) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea a = buf.getArea();
            assertTrue((Boolean) FxTestSupport.call(
                    actions, "acceptCompletion", new Class<?>[] {CodeArea.class, Completion.class}, a, row));
            return a.getParagraph(a.getParagraphs().size() - 1).getText();
        });
    }

    @Test
    void aLinkTargetListsTheFolderAndAcceptingARowReplacesTheWholeTarget() throws Exception {
        List<Completion> top = offered("See [the guide](");
        assertEquals(List.of("docs/"), top.stream().map(Completion::label).toList());
        assertEquals("See [the guide](docs/", accept(top.getFirst()));

        List<Completion> inFolder = offered("See [the guide](docs/gu");
        assertEquals(
                List.of("guide.md"), inFolder.stream().map(Completion::label).toList());
        assertEquals("See [the guide](docs/guide.md", accept(inFolder.getFirst()), "not docs/docs/guide.md");

        List<Completion> spaced = offered("See [n](docs/my");
        assertEquals("See [n](docs/my%20notes.md", accept(spaced.getFirst()));
    }

    @Test
    void anAnchorListsHeadingsOfThisFileAndOfTheLinkedOne() throws Exception {
        List<Completion> here = offered("[up](#");
        assertEquals(
                List.of("readme", "set-up"),
                here.stream().map(Completion::insert).toList());
        assertEquals("[up](#set-up", accept(here.get(1)));

        List<Completion> there = offered("[g](docs/guide.md#in");
        assertEquals(
                List.of("install-it"), there.stream().map(Completion::insert).toList());
        assertEquals("[g](docs/guide.md#install-it", accept(there.getFirst()));
    }

    @Test
    void aFenceOffersLanguagesAndProseOffersNoList() throws Exception {
        List<Completion> langs = offered("```pyth");
        assertEquals(List.of("python"), langs.stream().map(Completion::insert).toList());
        assertEquals("```python", accept(langs.getFirst()));
        assertEquals(List.of(), offered("ordinary prose he"), "prose keeps its ghost text, no list");
        assertFalse(FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(actions, "markdownShown")));
    }
}
