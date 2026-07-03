package com.editora.ui;

import java.util.Locale;
import java.util.Set;

/**
 * Pure helper: which files are raster images Editora opens in the read-only image viewer (instead of showing
 * their binary bytes as text). Limited to the formats JavaFX's {@code Image} decoder handles natively — PNG,
 * JPEG, GIF, BMP. SVG is deliberately excluded: it's editable XML text (highlighted + editable), not an opaque
 * blob. Toolkit-free and unit-tested.
 */
public final class ImageFormats {

    /** Extensions (lowercase, no dot) shown in the image viewer. */
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "jpe", "jfif", "gif", "bmp");

    private ImageFormats() {}

    /** True when {@code fileName}'s extension is a JavaFX-decodable raster image (so it renders, not shows bytes). */
    public static boolean isSupported(String fileName) {
        return EXTENSIONS.contains(extension(fileName));
    }

    /** The lowercase extension of {@code fileName} (without the dot), or {@code ""} if it has none. */
    static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String name = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
}
