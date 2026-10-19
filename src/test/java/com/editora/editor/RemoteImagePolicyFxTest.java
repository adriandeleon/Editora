package com.editora.editor;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import com.editora.editor.PreviewImageLoader.RemotePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remote-image policy in the live preview: "ask" fetches nothing until the reader says so for the document,
 * "never" shows alt text, "always" loads. The remote address used is a loopback one the loader refuses before
 * it opens a socket, so nothing here touches the network: what is asserted is whether the loader was asked.
 */
@Tag("fx")
class RemoteImagePolicyFxTest {

    private static final String REMOTE = "http://127.0.0.1:9/pic.png";
    private static final String DOC = "# Title\n\nText.\n\n![a picture](" + REMOTE + ")\n\nMore text.\n";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @AfterEach
    void restore() throws Exception {
        EditorFx.onFx(() -> PreviewImageLoader.setRemotePolicy(RemotePolicy.ALWAYS));
    }

    private Stage stage;
    private EditorBuffer buffer;
    private MarkdownPreviewPane pane;

    private void open(String text) throws Exception {
        EditorFx.onFx(() -> {
            buffer = new EditorBuffer();
            buffer.setLanguageOverride("markdown");
            buffer.getArea().replaceText(text);
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

    /** Whether the loader was given the picture (a slot for it exists), as opposed to a "blocked" placeholder. */
    private boolean asked() {
        return !pane.preview().column().lookupAll(".md-image-slot").isEmpty();
    }

    private String shown() {
        return EditorFx.textOf(pane.preview().column());
    }

    @Test
    void askLoadsNothingUntilTheReaderSaysSoForThisDocument() throws Exception {
        EditorFx.onFx(() -> PreviewImageLoader.setRemotePolicy(RemotePolicy.ASK));
        open(DOC);
        EditorFx.onFx(() -> {
            assertFalse(asked(), "nothing remote is fetched by opening a document");
            assertTrue(shown().contains(tr("markdown.preview.imageAlt", "a picture")), shown());
            assertTrue(shown().contains(REMOTE), "the reader sees what was asked for");
            HBox bar = pane.preview().remoteBar();
            assertNotNull(bar, "a bar says the document has remote images");
            assertTrue(EditorFx.textOf(bar).contains(tr("markdown.remoteImages.blocked")));
            assertFalse(pane.preview().remoteGranted());
        });
        // An edit does not load them either, and the bar stays.
        render(() -> buffer.getArea().appendText("\nTyped.\n"));
        EditorFx.onFx(() -> {
            assertFalse(asked());
            assertNotNull(pane.preview().remoteBar());
        });
        render(() -> {
            Button load = (Button) pane.preview().remoteBar().lookup(".button");
            assertEquals(tr("markdown.remoteImages.load"), load.getText());
            load.fire();
        });
        EditorFx.onFx(() -> {
            assertTrue(pane.preview().remoteGranted());
            assertTrue(asked(), "Load: the picture is fetched now");
            assertNull(pane.preview().remoteBar(), "and the bar is gone");
        });
        // For this document, for the session: later edits keep them.
        render(() -> buffer.getArea().appendText("\nTyped again.\n"));
        EditorFx.onFx(() -> {
            assertTrue(asked());
            assertNull(pane.preview().remoteBar());
        });
        // Another document in the same preview tab is asked about afresh.
        EditorBuffer[] other = new EditorBuffer[1];
        EditorFx.onFx(() -> {
            other[0] = new EditorBuffer();
            other[0].setLanguageOverride("markdown");
            other[0].getArea().replaceText("<img src=\"" + REMOTE + "\" alt=\"html\">\n\nText.\n");
        });
        render(() -> pane.setSource(other[0]));
        EditorFx.onFx(() -> {
            assertFalse(pane.preview().remoteGranted());
            assertFalse(asked());
            assertNotNull(pane.preview().remoteBar(), "an <img> of the HTML subset counts as well");
            other[0].dispose();
        });
        close();
    }

    @Test
    void aDocumentWithoutRemoteImagesHasNoBar() throws Exception {
        EditorFx.onFx(() -> PreviewImageLoader.setRemotePolicy(RemotePolicy.ASK));
        open("# Title\n\n![local](beside.png) and a [link](https://example.com/pic.png).\n");
        EditorFx.onFx(() -> assertNull(pane.preview().remoteBar()));
        close();
    }

    @Test
    void neverShowsAltTextAlwaysLoadsAndAChangeOfPolicyReachesAnOpenPreview() throws Exception {
        EditorFx.onFx(() -> PreviewImageLoader.setRemotePolicy(RemotePolicy.NEVER));
        open(DOC);
        EditorFx.onFx(() -> {
            assertFalse(asked());
            assertTrue(shown().contains(REMOTE));
            assertNull(pane.preview().remoteBar(), "never: nothing to offer");
            PreviewImageLoader.setRemotePolicy(RemotePolicy.ALWAYS);
        });
        render(pane::render); // what a change of the setting does to every open preview
        EditorFx.onFx(() -> {
            assertTrue(asked());
            assertNull(pane.preview().remoteBar());
            PreviewImageLoader.setRemotePolicy(RemotePolicy.ASK);
        });
        render(pane::render);
        EditorFx.onFx(() -> {
            assertFalse(asked(), "back to asking: the built picture is replaced by its placeholder");
            assertNotNull(pane.preview().remoteBar());
        });
        close();
    }

    @Test
    void thePolicyASettingNamesAndWhatItMeansForTheOnOffQuestion() {
        assertEquals(RemotePolicy.ALWAYS, RemotePolicy.of("always"));
        assertEquals(RemotePolicy.ASK, RemotePolicy.of(" Ask "));
        assertEquals(RemotePolicy.NEVER, RemotePolicy.of("NEVER"));
        assertEquals(RemotePolicy.ALWAYS, RemotePolicy.of("sometimes"));
        assertEquals(RemotePolicy.ALWAYS, RemotePolicy.of(null));
        try {
            PreviewImageLoader.setRemotePolicy(RemotePolicy.ASK);
            assertFalse(
                    PreviewImageLoader.remoteImages(), "print and the exports have nobody to ask: they do not fetch");
            assertNull(PreviewImageLoader.fetchForExport(REMOTE, null));
            assertFalse(MarkdownRenderer.ImagePolicy.DOCUMENT.allows(REMOTE));
            assertTrue(new MarkdownRenderer.Live(null, null, false, true, true).loadsRemote(REMOTE));
            assertFalse(new MarkdownRenderer.Live(null, null, false, false, true).loadsRemote(REMOTE));
            assertFalse(new MarkdownRenderer.Live(null, null, false, true, true).loadsRemote("file:///x.png"));
            PreviewImageLoader.setRemotePolicy(RemotePolicy.NEVER);
            assertFalse(new MarkdownRenderer.Live(null, null, false, true, true).loadsRemote(REMOTE), "never is never");
            PreviewImageLoader.setRemoteImages(true);
            assertEquals(RemotePolicy.ALWAYS, PreviewImageLoader.remotePolicy());
            PreviewImageLoader.setRemoteImages(false);
            assertEquals(RemotePolicy.NEVER, PreviewImageLoader.remotePolicy());
        } finally {
            PreviewImageLoader.setRemotePolicy(RemotePolicy.ALWAYS);
        }
    }

    @Test
    void whichDocumentsNameARemoteImage() {
        assertTrue(names("![x](https://example.com/a.png)"));
        assertTrue(names("Text ![x](HTTP://example.com/a.png) text"));
        assertTrue(names("<img src=\"https://example.com/a.png\">"));
        assertTrue(names("para <img alt=x src='http://example.com/a.png'> para"));
        assertTrue(names("[![badge](https://example.com/b.svg)](https://example.com)"));
        assertFalse(names("![x](a.png) ![y](file:///a.png) ![z](data:image/png;base64,AAAA)"));
        assertFalse(names("[a link](https://example.com/a.png)"));
        assertFalse(names("`![x](https://example.com/a.png)`"));
        assertFalse(names("```\n<img src=\"https://example.com/a.png\">\n```\n"));
        assertFalse(names(""));
    }

    private static boolean names(String md) {
        return MarkdownPreview.namesRemoteImage(MarkdownRenderer.parseForRender(md, false));
    }
}
