package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageFormatsTest {

    @Test
    void recognizesRasterImageExtensionsCaseInsensitively() {
        assertTrue(ImageFormats.isSupported("logo.png"));
        assertTrue(ImageFormats.isSupported("photo.JPG"));
        assertTrue(ImageFormats.isSupported("a.jpeg"));
        assertTrue(ImageFormats.isSupported("anim.gif"));
        assertTrue(ImageFormats.isSupported("old.BMP"));
        assertTrue(ImageFormats.isSupported("/abs/path/to/editora.png"));
    }

    @Test
    void rejectsNonImagesAndEditableTextFormats() {
        assertFalse(ImageFormats.isSupported("Main.java"));
        assertFalse(ImageFormats.isSupported("notes.txt"));
        assertFalse(ImageFormats.isSupported("icon.svg"), "SVG is editable XML text, not an opaque image");
        assertFalse(ImageFormats.isSupported("favicon.ico"), "ICO isn't JavaFX-decodable");
        assertFalse(ImageFormats.isSupported("README"), "no extension");
        assertFalse(ImageFormats.isSupported(""));
        assertFalse(ImageFormats.isSupported(null));
        assertFalse(ImageFormats.isSupported(".png"), "a dotfile named .png has no extension");
    }

    @Test
    void extractsExtension() {
        assertEquals("png", ImageFormats.extension("a.png"));
        assertEquals("gz", ImageFormats.extension("archive.tar.gz"));
        assertEquals("", ImageFormats.extension("Makefile"));
        assertEquals("png", ImageFormats.extension("/dir.d/file.PNG".replace(".PNG", ".png")));
    }
}
