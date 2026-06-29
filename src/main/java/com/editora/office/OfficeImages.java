package com.editora.office;

import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import com.editora.editor.MathImages;
import com.editora.editor.PreviewImageLoader;
import com.editora.mermaid.Mermaid;
import org.apache.poi.xwpf.usermodel.Document;

/**
 * Shared, pure image helpers for the office exporters: resolve a Markdown image URL to bytes (a local file
 * relative to the document, or a {@code data:} URI) and sniff its raster type. Remote ({@code http(s)}) and
 * SVG images are deliberately not fetched/rasterized here (they degrade to alt text), keeping the writers
 * offline and dependency-light.
 */
public final class OfficeImages {

    private OfficeImages() {}

    /**
     * Raster image bytes for {@code src} — a {@code data:} URI, an {@code http(s)} URL, or a local path
     * (resolved against {@code baseDir}) — or null. SVG (e.g. shields.io / GitHub badges) is rasterized to
     * PNG via the same JSVG path the on-screen preview uses, since Word/ODT can't embed SVG. Mirrors the PDF
     * writer's image resolution; runs on the export thread (off the FX thread), never the network on FX.
     */
    public static byte[] load(String src, Path baseDir) {
        byte[] raw = rawBytes(src, baseDir);
        if (raw == null) {
            return null;
        }
        if (PreviewImageLoader.looksLikeSvg(raw)) {
            byte[] png = PreviewImageLoader.svgToPng(raw);
            return png; // null if rasterization failed → caller degrades to alt text
        }
        return raw;
    }

    private static byte[] rawBytes(String src, Path baseDir) {
        if (src == null || src.isBlank()) {
            return null;
        }
        String s = src.trim();
        try {
            if (s.startsWith("data:")) {
                int comma = s.indexOf(',');
                return comma < 0 ? null : Base64.getMimeDecoder().decode(s.substring(comma + 1));
            }
            if (s.startsWith("http://") || s.startsWith("https://")) {
                URLConnection con = URI.create(s).toURL().openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestProperty("User-Agent", "Editora");
                try (InputStream in = con.getInputStream()) {
                    return in.readAllBytes();
                }
            }
            Path p = s.startsWith("file:") ? Path.of(URI.create(s)) : Path.of(s);
            if (!p.isAbsolute() && baseDir != null) {
                p = baseDir.resolve(p);
            }
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Renders a Mermaid diagram source to PNG bytes via the {@code mmdc} CLI, or null (no command / failure). */
    public static byte[] renderMermaid(List<String> mmdc, String source) {
        if (mmdc == null || mmdc.isEmpty()) {
            return null;
        }
        Mermaid.Render r = Mermaid.renderPng(mmdc, source, false); // export is always light
        return r.ok() ? r.image() : null;
    }

    /** Renders LaTeX to PNG bytes via JLaTeXMath (light), or null when math support is off / it fails. */
    public static byte[] renderMath(String latex, boolean display) {
        if (!MathImages.isEnabled()) {
            return null;
        }
        return MathImages.renderPng(latex, display, display ? 30f : 16f, false);
    }

    /** The POI {@code Document.PICTURE_TYPE_*} for these bytes (PNG/JPEG/GIF/BMP; default PNG). */
    public static int poiPictureType(byte[] b) {
        return switch (kind(b)) {
            case "jpeg" -> Document.PICTURE_TYPE_JPEG;
            case "gif" -> Document.PICTURE_TYPE_GIF;
            case "bmp" -> Document.PICTURE_TYPE_BMP;
            default -> Document.PICTURE_TYPE_PNG;
        };
    }

    /** The ODT media type (MIME) for these bytes. */
    public static String mediaType(byte[] b) {
        return switch (kind(b)) {
            case "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    /** A file extension (no dot) for these bytes, for the ODT {@code Pictures/} entry name. */
    public static String extension(byte[] b) {
        return switch (kind(b)) {
            case "jpeg" -> "jpg";
            case "gif" -> "gif";
            case "bmp" -> "bmp";
            default -> "png";
        };
    }

    private static String kind(byte[] b) {
        if (b == null || b.length < 4) {
            return "png";
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "jpeg";
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "gif";
        }
        if (b[0] == 'B' && b[1] == 'M') {
            return "bmp";
        }
        return "png";
    }
}
