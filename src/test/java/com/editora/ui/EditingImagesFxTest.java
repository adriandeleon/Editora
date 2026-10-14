package com.editora.ui;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.paint.Color;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pictures put into a Markdown or Typst document: dropped files, a picture dragged from a browser (as
 * pixels or as a {@code data:} address — never fetched from the network here) and a picture on the
 * clipboard. Each lands in {@code assets/} beside the document and is referenced from the text; a document
 * with no file yet gets a message instead.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditingImagesFxTest {

    @TempDir
    Path dir;

    private FxWindowFixture fx;
    private EditingCoordinator editing;
    private CommandRegistry registry;
    private int counter;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        editing = FxTestSupport.field(fx.controller, "editing");
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** A saved document in a folder of its own, open in the window, the caret at its end. */
    private EditorBuffer document(String name, String text) throws Exception {
        Path folder = Files.createDirectory(dir.resolve("doc" + counter++));
        Path file = Files.writeString(folder.resolve(name), text);
        return FxTestSupport.callOnFx(() -> {
            try {
                EditorBuffer b = new EditorBuffer();
                b.setPath(file);
                b.setContent(text);
                b.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
                b.getArea().moveTo(b.getArea().getLength());
                return b;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private EditorBuffer untitledMarkdown() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("");
            b.setLanguageOverride("markdown");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    private static Path assets(EditorBuffer b) {
        return b.getPath().getParent().resolve("assets");
    }

    private static List<String> names(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private String status() throws Exception {
        return SaveDecisionsFxTest.lastMessage(fx);
    }

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    private static WritableImage pixels() {
        WritableImage img = new WritableImage(3, 2);
        img.getPixelWriter().setColor(0, 0, Color.RED);
        img.getPixelWriter().setColor(2, 1, Color.BLUE);
        return img;
    }

    /** Waits for the web-image worker to finish and for what it queued on the FX thread. */
    private void awaitWebImage() throws Exception {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("md-web-image")) {
                t.join(20_000);
                assertFalse(t.isAlive(), "the web-image worker finished");
            }
        }
        FxTestSupport.drainFx();
    }

    // --- names ------------------------------------------------------------------------------------------

    @Test
    void aWebPicturesFileNameComesFromTheLastPathSegmentOfItsAddress() {
        assertEquals("logo", EditingCoordinator.webImageBaseName("https://example.com/img/logo.png?v=2#top"));
        assertEquals("logo", EditingCoordinator.webImageBaseName("https://example.com/img/logo#frag"));
        assertEquals("my-pic_1", EditingCoordinator.webImageBaseName("https://example.com/my-pic_1.jpg"));
        assertEquals(
                "a20b1final",
                EditingCoordinator.webImageBaseName("https://example.com/a%20b(1).final.jpg"),
                "only the last extension goes; characters unsafe in a file name are dropped");
        assertEquals("image", EditingCoordinator.webImageBaseName("https://example.com/"), "no segment to name it by");
        assertEquals("image", EditingCoordinator.webImageBaseName("https://example.com/%%%.png"));
        assertEquals("image", EditingCoordinator.webImageBaseName("data:image/png;base64,AAAA"));
        assertEquals("image", EditingCoordinator.webImageBaseName(null));
        assertEquals("plain", EditingCoordinator.webImageBaseName("plain"));
        assertEquals(
                "hidden",
                EditingCoordinator.webImageBaseName("https://example.com/.hidden"),
                "a leading dot is no extension");
    }

    @Test
    void aDroppedFilesExtensionAndBaseNameAreSplitAtTheLastDot() {
        assertEquals("jpeg", EditingCoordinator.extensionOf("Photo.final.JPEG"));
        assertEquals("png", EditingCoordinator.extensionOf("noextension"), "PNG when there is none");
        assertEquals("png", EditingCoordinator.extensionOf("trailingdot."));
        assertEquals("png", EditingCoordinator.extensionOf(".hidden"));
        assertEquals("Photo.final", EditingCoordinator.stripExtension("Photo.final.JPEG"));
        assertEquals("noextension", EditingCoordinator.stripExtension("noextension"));
        assertEquals(".hidden", EditingCoordinator.stripExtension(".hidden"));
    }

    @Test
    void anFxImageIsWrittenAsAPngWithItsPixels() throws Exception {
        Path target = dir.resolve("pixels.png");
        WritableImage img = FxTestSupport.callOnFx(EditingImagesFxTest::pixels);
        EditingCoordinator.writeFxImageToPng(img, target);
        BufferedImage read = ImageIO.read(target.toFile());
        assertEquals(3, read.getWidth());
        assertEquals(2, read.getHeight());
        assertEquals(0xFFFF0000, read.getRGB(0, 0));
        assertEquals(0xFF0000FF, read.getRGB(2, 1));
        assertEquals(0, read.getRGB(1, 0) >>> 24, "an untouched pixel stays transparent");
    }

    // --- dropped files ----------------------------------------------------------------------------------

    @Test
    void droppedPicturesAreCopiedIntoAssetsUnderUniqueNamesAndLinkedInOrder() throws Exception {
        EditorBuffer b = document("notes.md", "# Notes\n");
        byte[] first = png(2, 2);
        byte[] second = png(4, 4);
        Path a = Files.write(Files.createDirectory(dir.resolve("src-a")).resolve("shot.png"), first);
        Path sameName = Files.write(Files.createDirectory(dir.resolve("src-b")).resolve("shot.png"), second);

        FxTestSupport.runOnFx(() -> editing.insertDroppedImages(b, List.of(a.toFile(), sameName.toFile())));

        assertEquals(List.of("shot-1.png", "shot.png"), names(assets(b)));
        assertArrayEquals(first, Files.readAllBytes(assets(b).resolve("shot.png")));
        assertArrayEquals(second, Files.readAllBytes(assets(b).resolve("shot-1.png")));
        assertEquals("# Notes\n![shot](assets/shot.png)\n![shot](assets/shot-1.png)", text(b));
        assertEquals(tr("status.markdown.imageDropped", 2), status());
        assertTrue(Files.exists(a), "the source file is copied, not moved");
    }

    @Test
    void aPictureAlreadyBesideTheDocumentIsLinkedNotCopiedAgain() throws Exception {
        EditorBuffer b = document("notes.md", "");
        Path own = Files.write(Files.createDirectories(assets(b)).resolve("logo.png"), png(2, 2));
        Path sibling = Files.write(
                Files.createDirectory(b.getPath().getParent().resolve("img")).resolve("a b.png"), png(2, 2));

        FxTestSupport.runOnFx(() -> editing.insertDroppedImages(b, List.of(own.toFile(), sibling.toFile())));

        assertEquals(List.of("logo.png"), names(assets(b)), "no logo-1.png");
        assertEquals("![logo](assets/logo.png)\n![a b](<img/a b.png>)", text(b));
    }

    @Test
    void aDownloadThatIsNotAPictureIsNeverSavedAsOne() throws Exception {
        byte[] page = "<!doctype html><html><body>hello</body></html>".getBytes(StandardCharsets.UTF_8);
        byte[] picture = png(2, 2);
        // The address answered with a web page: a link, not assets/page.png holding HTML.
        EditingCoordinator.WebImage link =
                EditingCoordinator.resolveWebImage("https://example.com/page", null, u -> page);
        assertNull(link.bytes());
        assertEquals("<https://example.com/page>", link.link());
        // The same address with the dragged pixels at hand: the pixels are the picture.
        assertArrayEquals(
                picture,
                EditingCoordinator.resolveWebImage("https://example.com/page", picture, u -> page)
                        .bytes());
        // A picture served from an address without an extension gets its type from its bytes.
        EditingCoordinator.WebImage typed =
                EditingCoordinator.resolveWebImage("https://example.com/render?id=7", null, u -> picture);
        assertEquals("png", typed.ext());
        // Unreachable now: reference it by address, as before.
        assertTrue(EditingCoordinator.resolveWebImage("https://example.com/a.png", null, u -> null)
                .remote());
        EditingCoordinator.WebImage nothing = EditingCoordinator.resolveWebImage(null, null, u -> null);
        assertTrue(nothing.bytes() == null && !nothing.remote() && nothing.link() == null);
    }

    @Test
    void aWebPictureLandsWhereItWasDroppedEvenIfTheCaretMovedOn() throws Exception {
        EditorBuffer b = document("notes.md", "start end");
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(6); // the drop point
            editing.insertWebImage(b, pixels(), null);
            b.getArea().selectRange(0, 5); // the user carries on while the picture is being saved
        });
        awaitWebImage();
        assertEquals("start ![image](assets/image.png)end", text(b), "not over the new selection");
    }

    @Test
    void aClipboardWithTextAndAPictureOfItPastesTheText() throws Exception {
        // What a spreadsheet puts on the clipboard for a copied range: the cells as text and as an image.
        EditorBuffer b = document("notes.md", "");
        FxTestSupport.runOnFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putImage(pixels());
            content.putString("cell A1\tcell B1");
            Clipboard.getSystemClipboard().setContent(content);
            assertFalse(editing.tryMarkdownImagePaste(b), "the text is what was copied");
            registry.run("edit.paste");
        });
        assertEquals("cell A1\tcell B1", text(b));
        assertEquals(List.of(), names(assets(b)), "no screenshot of the cells was written");
        clearClipboard();
    }

    @Test
    void picturesGoIntoTheConfiguredAssetsFolder() throws Exception {
        EditorBuffer b = document("notes.md", "");
        Path source = Files.write(Files.createDirectory(dir.resolve("src-cfg")).resolve("shot.png"), png(2, 2));
        fx.shared.getSettings().setMarkdownAssetsFolder("img/shots");
        try {
            FxTestSupport.runOnFx(() -> editing.insertDroppedImages(b, List.of(source.toFile())));
            assertEquals("![shot](img/shots/shot.png)", text(b));
            assertTrue(Files.exists(b.getPath().getParent().resolve("img/shots/shot.png")));
            fx.shared.getSettings().setMarkdownAssetsFolder("../outside");
            FxTestSupport.runOnFx(() -> {
                b.getArea().replaceText("");
                editing.insertDroppedImages(b, List.of(source.toFile()));
            });
            assertEquals("![shot](assets/shot.png)", text(b), "a folder outside the document's falls back");
        } finally {
            fx.shared.getSettings().setMarkdownAssetsFolder("assets");
        }
    }

    @Test
    void aPastedPictureIsNamedByThePatternAndItsAltTextCanBeAskedFor() throws Exception {
        EditorBuffer b = document("notes.md", "");
        fx.shared.getSettings().setMarkdownImageNamePattern("{name}-shot");
        try {
            clipboardImage();
            FxTestSupport.runOnFx(() -> registry.run("edit.paste"));
            assertEquals("![notes-shot](assets/notes-shot.png)", text(b));
            assertEquals(List.of("notes-shot.png"), names(assets(b)));

            // With the prompt on, nothing is written until the alt text is given.
            fx.shared.getSettings().setMarkdownImageAltPrompt(true);
            FxTestSupport.runOnFx(() -> {
                b.getArea().replaceText("");
                registry.run("edit.paste");
            });
            FxTestSupport.drainFx();
            assertEquals("", text(b));
            assertEquals(List.of("notes-shot.png"), names(assets(b)), "not yet");
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.TextField field =
                        (javafx.scene.control.TextField) b.getArea().getScene().getFocusOwner();
                field.setText("A red pixel");
                javafx.event.Event.fireEvent(
                        field,
                        new javafx.scene.input.KeyEvent(
                                javafx.scene.input.KeyEvent.KEY_PRESSED,
                                "",
                                "",
                                javafx.scene.input.KeyCode.ENTER,
                                false,
                                false,
                                false,
                                false));
            });
            FxTestSupport.drainFx();
            assertEquals("![A red pixel](assets/notes-shot-1.png)", text(b));
        } finally {
            fx.shared.getSettings().setMarkdownImageNamePattern("pasted-image");
            fx.shared.getSettings().setMarkdownImageAltPrompt(false);
            clearClipboard();
        }
    }

    @Test
    void savingTrashesAPastedPictureTheDocumentNoLongerUses() throws Exception {
        EditorBuffer b = document("notes.md", "");
        Path theirs = Files.write(Files.createDirectories(assets(b)).resolve("theirs.png"), png(1, 1));
        Path trashDir = Files.createDirectory(dir.resolve("trash" + counter++));
        Object workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        java.lang.reflect.Field seam = workflows.getClass().getDeclaredField("pastedImageBin");
        seam.setAccessible(true);
        seam.set(workflows, com.editora.io.Trash.directory(trashDir));
        try {
            clipboardImage();
            FxTestSupport.runOnFx(() -> registry.run("edit.paste"));
            Path pasted = assets(b).resolve("pasted-image.png");
            assertTrue(Files.exists(pasted));

            // Saved while still used: it stays.
            FxTestSupport.runOnFx(() -> registry.run("file.save"));
            awaitSaved(b);
            assertTrue(Files.exists(pasted));

            // The link is deleted, then the document saved: the picture goes to the trash, and only it.
            FxTestSupport.runOnFx(() -> {
                b.getArea().replaceText("no picture any more\n");
                registry.run("file.save");
            });
            awaitSaved(b);
            awaitGone(pasted); // moved out of assets/ once the background clean-up has run
            awaitCleanup();
            assertEquals(
                    List.of("pasted-image.png"),
                    names(trashDir).stream().limit(1).toList());
            assertTrue(Files.exists(theirs), "a file the session did not create is never touched");
            assertTrue(status().contains(tr("status.markdown.imagesTrashed", 1)), status());

            fx.shared.getSettings().setMarkdownTrashUnusedPastedImages(false);
            clipboardImage();
            FxTestSupport.runOnFx(() -> {
                registry.run("edit.paste");
                b.getArea().replaceText("again without\n");
                registry.run("file.save");
            });
            awaitSaved(b);
            assertTrue(Files.exists(pasted), "switched off: nothing is trashed");
        } finally {
            fx.shared.getSettings().setMarkdownTrashUnusedPastedImages(true);
            seam.set(workflows, null);
            clearClipboard();
        }
    }

    /** A save is written on a background executor: wait until the buffer says it reached the disk. */
    private void awaitSaved(EditorBuffer b) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (FxTestSupport.callOnFx(b::isDirty)) {
            assertTrue(System.nanoTime() < deadline, "the save was never acknowledged");
            Thread.sleep(20);
            FxTestSupport.drainFx();
        }
        FxTestSupport.drainFx();
        awaitCleanup();
    }

    /** Waits for the clean-up to have moved {@code file} away; fails when it never does. */
    private void awaitGone(Path file) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (Files.exists(file)) {
            assertTrue(System.nanoTime() < deadline, file + " was never moved to the trash");
            awaitCleanup();
            Thread.sleep(20);
        }
    }

    /** The pasted-image clean-up runs after the save, off the FX thread: wait until it has settled. */
    private void awaitCleanup() throws Exception {
        Object workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        java.util.concurrent.CompletableFuture<?> cleanup = FxTestSupport.field(workflows, "pastedImageCleanup");
        cleanup.get(20, java.util.concurrent.TimeUnit.SECONDS);
        FxTestSupport.drainFx();
    }

    @Test
    void aLinkPutBackWhileTheCleanUpIsRunningKeepsItsPicture() throws Exception {
        EditorBuffer b = document("notes.md", "");
        Path trashDir = Files.createDirectory(dir.resolve("trash" + counter++));
        Object workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        java.lang.reflect.Field bin = workflows.getClass().getDeclaredField("pastedImageBin");
        java.lang.reflect.Field hook = workflows.getClass().getDeclaredField("beforePastedImageRecheck");
        bin.setAccessible(true);
        hook.setAccessible(true);
        bin.set(workflows, com.editora.io.Trash.directory(trashDir));
        java.util.concurrent.CountDownLatch scanned = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch restored = new java.util.concurrent.CountDownLatch(1);
        hook.set(workflows, (Runnable) () -> {
            scanned.countDown();
            try {
                restored.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            clipboardImage();
            FxTestSupport.runOnFx(() -> registry.run("edit.paste"));
            Path pasted = assets(b).resolve("pasted-image.png");
            String withLink = text(b);
            FxTestSupport.runOnFx(() -> {
                b.getArea().replaceText("link deleted\n");
                registry.run("file.save");
            });
            // The background scan has found the picture unused; before it is trashed, the user undoes.
            assertTrue(scanned.await(20, java.util.concurrent.TimeUnit.SECONDS), "the clean-up never started");
            FxTestSupport.runOnFx(() -> b.getArea().replaceText(withLink));
            restored.countDown();
            awaitCleanup();
            assertTrue(Files.exists(pasted), "mentioned again by the time it would be trashed: it stays");
            assertEquals(List.of(), names(trashDir));
        } finally {
            restored.countDown();
            hook.set(workflows, null);
            bin.set(workflows, null);
            clearClipboard();
        }
    }

    @Test
    void aTypstDocumentGetsAnImageCallInsteadOfAMarkdownLink() throws Exception {
        EditorBuffer b = document("paper.typ", "= Paper\n");
        Path picture = Files.write(Files.createDirectory(dir.resolve("src-typ")).resolve("fig.png"), png(2, 2));
        FxTestSupport.runOnFx(() -> editing.insertDroppedImages(b, List.of(picture.toFile())));
        assertEquals("= Paper\n#image(\"assets/fig.png\")", text(b));
    }

    @Test
    void aDropOnADocumentWithNoFileIsRefusedWithTheReason() throws Exception {
        EditorBuffer b = untitledMarkdown();
        Path picture = Files.write(dir.resolve("loose.png"), png(2, 2));
        FxTestSupport.runOnFx(() -> {
            editing.insertDroppedImages(b, List.of(picture.toFile()));
            editing.insertWebImage(b, pixels(), null);
        });
        assertEquals("", text(b));
        assertEquals(tr("status.markdown.imageNeedsSave"), status());
    }

    @Test
    void aDroppedFileThatCannotBeCopiedIsReportedAndNothingIsInserted() throws Exception {
        EditorBuffer b = document("notes.md", "text");
        File missing = dir.resolve("not-there.png").toFile();
        FxTestSupport.runOnFx(() -> editing.insertDroppedImages(b, List.of(missing)));
        assertEquals("text", text(b));
        assertTrue(status().startsWith(tr("status.markdown.imageFailed", "").strip()), status());
        assertEquals(List.of(), names(assets(b)));
    }

    // --- dragged from a browser -------------------------------------------------------------------------

    @Test
    void aDraggedPictureWithNoAddressIsSavedFromItsPixels() throws Exception {
        EditorBuffer b = document("notes.md", "");
        FxTestSupport.runOnFx(() -> editing.insertWebImage(b, pixels(), null));
        awaitWebImage();

        assertEquals(List.of("image.png"), names(assets(b)));
        BufferedImage saved = ImageIO.read(assets(b).resolve("image.png").toFile());
        assertEquals(0xFFFF0000, saved.getRGB(0, 0));
        assertEquals("![image](assets/image.png)", text(b));
        assertEquals(tr("status.markdown.imageDropped", 1), status());
    }

    @Test
    void aDataAddressIsDecodedAndSavedUnderItsOwnType() throws Exception {
        EditorBuffer b = document("notes.md", "");
        byte[] bytes = png(5, 5);
        String url = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        FxTestSupport.runOnFx(() -> editing.insertWebImage(b, null, url));
        awaitWebImage();

        assertEquals(List.of("image.png"), names(assets(b)));
        assertArrayEquals(
                bytes,
                Files.readAllBytes(assets(b).resolve("image.png")),
                "the address's own bytes, not a re-encoding");
        assertEquals("![image](assets/image.png)", text(b));
    }

    @Test
    void anAddressThatYieldsNoBytesFallsBackToThePixelsAndThenToTheAddressItself() throws Exception {
        EditorBuffer withPixels = document("notes.md", "");
        FxTestSupport.runOnFx(() -> editing.insertWebImage(withPixels, pixels(), "data:image/png;base64,"));
        awaitWebImage();
        assertEquals(List.of("image.png"), names(assets(withPixels)), "the dragged pixels were saved instead");
        assertEquals("![image](assets/image.png)", text(withPixels));

        EditorBuffer addressOnly = document("notes.md", "");
        FxTestSupport.runOnFx(() -> editing.insertWebImage(addressOnly, null, "data:image/png;base64,"));
        awaitWebImage();
        assertEquals(List.of(), names(assets(addressOnly)));
        assertEquals("![](data:image/png;base64,)", text(addressOnly), "the address is referenced as it is");

        EditorBuffer neither = document("notes.md", "");
        FxTestSupport.runOnFx(() -> editing.insertWebImage(neither, null, null));
        awaitWebImage();
        assertEquals("", text(neither));
        assertEquals(tr("status.markdown.imageFailed", ""), status());
    }

    @Test
    void aBlankAddressWithNoPixelsInsertsNothing() throws Exception {
        EditorBuffer b = document("notes.md", "");
        FxTestSupport.runOnFx(() -> editing.insertWebImage(b, null, " "));
        awaitWebImage();
        assertEquals("", text(b), "not a link to nowhere");
        assertEquals(tr("status.markdown.imageFailed", ""), status());
    }

    @Test
    void aPictureThatCannotBeWrittenIsReported() throws Exception {
        EditorBuffer b = document("notes.md", "");
        Files.writeString(b.getPath().getParent().resolve("assets"), "a file where the folder should be");
        FxTestSupport.runOnFx(() -> editing.insertWebImage(b, pixels(), null));
        awaitWebImage();
        assertEquals("", text(b));
        assertTrue(status().startsWith(tr("status.markdown.imageFailed", "").strip()), status());
    }

    // --- pasted -----------------------------------------------------------------------------------------

    private void clipboardImage() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putImage(pixels());
            Clipboard.getSystemClipboard().setContent(content);
        });
    }

    private void clearClipboard() throws Exception {
        FxTestSupport.runOnFx(() -> Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, "")));
    }

    @Test
    void pastingAClipboardPictureIntoMarkdownSavesItAndLinksIt() throws Exception {
        EditorBuffer b = document("notes.md", "see: ");
        clipboardImage();
        boolean supported =
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().hasImage());
        org.junit.jupiter.api.Assumptions.assumeTrue(supported, "this toolkit's clipboard does not carry images");
        try {
            FxTestSupport.runOnFx(() -> registry.run("edit.paste"));
            assertEquals(List.of("pasted-image.png"), names(assets(b)));
            assertEquals("see: ![pasted-image](assets/pasted-image.png)", text(b));
            assertEquals(tr("status.markdown.imagePasted", "assets/pasted-image.png"), status());

            FxTestSupport.runOnFx(() -> registry.run("edit.paste"));
            assertEquals(
                    List.of("pasted-image-1.png", "pasted-image.png"),
                    names(assets(b)),
                    "a second paste gets its own file");

            EditorBuffer unsaved = untitledMarkdown();
            FxTestSupport.runOnFx(() -> registry.run("edit.paste"));
            assertEquals("", text(unsaved), "a picture cannot be pasted as text");
            assertEquals(tr("status.markdown.imageNeedsSave"), status());

            EditorBuffer plain = document("plain.txt", "");
            FxTestSupport.runOnFx(() -> registry.run("edit.paste"));
            assertEquals(List.of(), names(assets(plain)), "only Markdown and Typst documents take pictures");
        } finally {
            clearClipboard();
        }
    }
}
