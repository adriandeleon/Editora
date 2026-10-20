package com.editora.editor;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import javafx.scene.input.DragEvent;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Accepts dropped images on a Markdown or Typst buffer (copy/download into {@code assets/} + insert
 * {@code ![](…)}): image <em>files</em> from the OS, and a raw image / image URL dragged from a web browser.
 * The buffer owns the eligibility rule and where the caret goes; the controller injects the handlers.
 */
final class ImageDrop {

    private final BooleanSupplier eligible;
    private final BooleanSupplier markdownLinks;
    private final BiConsumer<CodeArea, DragEvent> caretToDrop;
    private java.util.function.@Nullable Consumer<java.util.List<java.io.File>> imageDropHandler;
    private java.util.function.@Nullable BiConsumer<javafx.scene.image.Image, String> webImageDropHandler;

    /** @param eligible whether a drop is accepted right now (a Markdown/Typst buffer that is editable)
     *  @param markdownLinks whether a dropped hyperlink becomes a Markdown link (a Markdown buffer)
     *  @param caretToDrop moves the caret under the pointer in the view the drop landed on */
    ImageDrop(BooleanSupplier eligible, BooleanSupplier markdownLinks, BiConsumer<CodeArea, DragEvent> caretToDrop) {
        this.eligible = eligible;
        this.markdownLinks = markdownLinks;
        this.caretToDrop = caretToDrop;
    }

    /** The handler for image files dropped onto the buffer; null disables the drop affordance. */
    void setImageDropHandler(java.util.function.Consumer<java.util.List<java.io.File>> handler) {
        this.imageDropHandler = handler;
    }

    /** The handler for a raw image (or image URL) dragged from a web browser; null disables it. */
    void setWebImageDropHandler(java.util.function.BiConsumer<javafx.scene.image.Image, String> handler) {
        this.webImageDropHandler = handler;
    }

    /** The Markdown link for a dragged hyperlink that is not an image, or null. Typst has its own link form. */
    private @Nullable String link(javafx.scene.input.Dragboard db) {
        return markdownLinks.getAsBoolean()
                ? com.editora.markdown.MarkdownImagePaste.linkFromDrag(
                        db.hasUrl() ? db.getUrl() : null, db.hasHtml() ? db.getHtml() : null)
                : null;
    }

    /** The image URL a browser drag carries (its URL, an {@code <img src>} in the HTML, or an image string). */
    private static @Nullable String webImageUrl(javafx.scene.input.Dragboard db) {
        return com.editora.markdown.MarkdownImagePaste.imageUrlFromDrag(
                db.hasUrl() ? db.getUrl() : null,
                db.hasHtml() ? db.getHtml() : null,
                db.hasString() ? db.getString() : null);
    }

    /**
     * Accepts dropped images on a Markdown buffer (copy/download into {@code assets/} + insert {@code ![](…)}):
     * image <em>files</em> from the OS, and a raw image / image URL dragged from a web browser.
     */
    void install(CodeArea a) {
        a.addEventHandler(javafx.scene.input.DragEvent.DRAG_OVER, e -> {
            if (!eligible.getAsBoolean()) {
                return;
            }
            javafx.scene.input.Dragboard db = e.getDragboard();
            boolean file = imageDropHandler != null && db.hasFiles() && hasImageFile(db.getFiles());
            boolean web = webImageDropHandler != null && (db.hasImage() || webImageUrl(db) != null);
            if (file || web || link(db) != null) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                e.consume();
            }
        });
        a.addEventHandler(javafx.scene.input.DragEvent.DRAG_DROPPED, e -> {
            if (!eligible.getAsBoolean()) {
                return;
            }
            javafx.scene.input.Dragboard db = e.getDragboard();
            // Prefer real image files (self-contained copy); fall back to a browser image (download/encode).
            if (imageDropHandler != null && db.hasFiles()) {
                java.util.List<java.io.File> images =
                        db.getFiles().stream().filter(ImageDrop::isImageFile).toList();
                if (!images.isEmpty()) {
                    caretToDrop.accept(a, e);
                    imageDropHandler.accept(images);
                    e.setDropCompleted(true);
                    e.consume();
                    return;
                }
            }
            if (webImageDropHandler != null) {
                String url = webImageUrl(db);
                javafx.scene.image.Image img = db.hasImage() ? db.getImage() : null;
                if (url != null || img != null) {
                    caretToDrop.accept(a, e);
                    webImageDropHandler.accept(img, url);
                    e.setDropCompleted(true);
                    e.consume();
                    return;
                }
            }
            // A hyperlink that is no image: a link where it was dropped, not a download of the page.
            String link = link(db);
            if (link != null) {
                caretToDrop.accept(a, e);
                a.insertText(a.getCaretPosition(), link);
                a.requestFocus();
                e.setDropCompleted(true);
                e.consume();
            }
        });
    }

    private static boolean hasImageFile(java.util.List<java.io.File> files) {
        return files.stream().anyMatch(ImageDrop::isImageFile);
    }

    private static boolean isImageFile(java.io.File f) {
        return com.editora.markdown.MarkdownImagePaste.isImageFileName(f.getName());
    }
}
