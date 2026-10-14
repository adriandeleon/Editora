package com.editora.editor;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wiki links in the live preview: drawn as links, marked when nothing answers, followed through the opener. */
@Tag("fx")
class WikiLinkPreviewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @AfterEach
    void restore() throws Exception {
        EditorFx.onFx(() -> {
            MarkdownRenderer.setWikiLinks(true);
            WikiLinkTargets.setResolver(null);
        });
    }

    private Stage stage;
    private EditorBuffer buffer;
    private MarkdownPreviewPane pane;
    private final List<String> opened = new ArrayList<>();

    private void open(String text, Path file) throws Exception {
        EditorFx.onFx(() -> {
            buffer = new EditorBuffer();
            buffer.setLanguageOverride("markdown");
            buffer.getArea().replaceText(text);
            buffer.setPath(file);
            buffer.setOpenUrlHandler(opened::add);
            pane = new MarkdownPreviewPane((b, l) -> {});
            stage = new Stage();
            stage.setScene(new Scene(new HBox(buffer.getNode(), pane.node()), 1000, 500));
            stage.show();
        });
        render(() -> pane.setSource(buffer));
    }

    private void render(Runnable action) throws Exception {
        EditorFx.awaitChange(() -> pane.preview().showsProperty(), action);
    }

    private void close() throws Exception {
        EditorFx.onFx(() -> {
            pane.dispose();
            stage.close();
            buffer.dispose();
        });
    }

    /** The first link run of the preview that reads {@code text}. */
    private Text run(String text) {
        for (Node n : pane.preview().column().lookupAll(".md-text")) {
            if (n instanceof Text t
                    && text.equals(t.getText())
                    && t.getStyleClass().contains("md-link")) {
                return t;
            }
        }
        return null;
    }

    private static MouseEvent click() {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                2,
                2,
                2,
                2,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null);
    }

    @Test
    void aWikiLinkIsALinkMarkedWhenNothingAnswersAndFollowedThroughTheOpener(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("there.md"), "# There\n\n## Part two\n");
        Path doc = Files.writeString(dir.resolve("doc.md"), "x");
        open(
                "# Top\n\nSee [[there]], [[there#Part two|the second part]], [[nowhere]] and [[#Top]].\n\n"
                        + "Not one: `[[code]]` and \\[[escaped]].\n",
                doc);
        EditorFx.onFx(() -> {
            Text there = run("there");
            assertNotNull(there, "the link reads as its target");
            assertTrue(there.getStyleClass().containsAll(List.of("md-link", "md-wikilink")));
            assertFalse(there.getStyleClass().contains("md-wikilink-missing"));
            assertEquals("wikilink:there", there.getProperties().get(MarkdownRenderer.LINK_KEY));
            assertTrue(there.isFocusTraversable(), "a Tab stop, like every link");

            Text labelled = run("the second part");
            assertNotNull(labelled, "a label is what is shown");
            assertEquals("wikilink:there#Part two", labelled.getProperties().get(MarkdownRenderer.LINK_KEY));

            Text missing = run("nowhere");
            assertNotNull(missing);
            assertTrue(missing.getStyleClass().containsAll(List.of("md-link", "md-wikilink-missing")));

            Text here = run("Top");
            assertNotNull(here, "[[#Top]] reads as the heading");
            assertEquals("#top", here.getProperties().get(MarkdownRenderer.LINK_KEY));

            String all = EditorFx.textOf(pane.preview().column());
            assertTrue(all.contains("[[code]]"), all);
            assertTrue(all.contains("[[escaped]]"), all);
            assertFalse(all.contains("[[there"), "no brackets left around a link: " + all);
            assertFalse(all.contains("]],"), all);

            there.getOnMouseClicked().handle(click());
            missing.getOnMouseClicked().handle(click());
            labelled.getOnMouseClicked().handle(click());
            assertEquals(List.of("wikilink:there", "wikilink:nowhere", "wikilink:there#Part two"), opened);
        });

        // The note is written: told so, the preview draws the link as found on its next render.
        Files.writeString(dir.resolve("nowhere.md"), "now here\n");
        EditorFx.onFx(WikiLinkTargets::changed);
        render(pane::render);
        EditorFx.onFx(() -> assertFalse(run("nowhere").getStyleClass().contains("md-wikilink-missing")));

        // Switched off, the brackets are text again.
        EditorFx.onFx(() -> MarkdownRenderer.setWikiLinks(false));
        render(pane::render);
        EditorFx.onFx(() -> {
            String all = EditorFx.textOf(pane.preview().column());
            assertTrue(all.contains("[[there]]"), all);
            assertTrue(all.contains("[[nowhere]]"), all);
        });
        close();
    }

    @Test
    void anEmbeddedPictureIsShownAndAnEmbeddedNoteIsALink(@TempDir Path dir) throws Exception {
        javax.imageio.ImageIO.write(
                new BufferedImage(30, 10, BufferedImage.TYPE_INT_RGB),
                "png",
                dir.resolve("pic.png").toFile());
        Files.writeString(dir.resolve("note.md"), "x");
        Path doc = Files.writeString(dir.resolve("doc.md"), "x");
        open("![[pic.png]]\n\n![[note]]\n", doc);
        long end = System.nanoTime() + 15_000_000_000L;
        while (!EditorFx.callFx(() -> pane.preview().column().lookupAll(".md-image").stream()
                .anyMatch(n -> ((ImageView) n).getImage() != null))) {
            assertTrue(System.nanoTime() < end, "the embedded picture never loaded");
            Thread.sleep(20);
        }
        EditorFx.onFx(() -> {
            assertEquals(1, pane.preview().column().lookupAll(".md-image").size());
            Text note = run("note");
            assertNotNull(note, "an embedded note is a link to it (it is not transcluded)");
            assertTrue(note.getStyleClass().contains("md-wikilink"));
        });
        close();
    }

    @Test
    void theInstalledResolverDecidesWhatIsFound(@TempDir Path dir) throws Exception {
        Path far = Files.writeString(dir.resolve("far.md"), "x");
        Path doc = Files.writeString(Files.createDirectories(dir.resolve("sub")).resolve("doc.md"), "x");
        EditorFx.onFx(() -> WikiLinkTargets.setResolver((folder, target) -> "far".equals(target) ? far : null));
        open("[[far]] and [[near]]\n", doc);
        EditorFx.onFx(() -> {
            assertFalse(run("far").getStyleClass().contains("md-wikilink-missing"));
            assertTrue(run("near").getStyleClass().contains("md-wikilink-missing"));
        });
        close();
    }
}
