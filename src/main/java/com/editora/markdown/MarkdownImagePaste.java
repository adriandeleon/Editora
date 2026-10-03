package com.editora.markdown;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for inserting a pasted/dropped image into a Markdown buffer: pick a unique file name
 * within the sibling {@code assets/} directory, compute the forward-slashed relative link, and build
 * the {@code ![](…)} snippet. The actual disk write + clipboard access live in the controller; these
 * bits are stateless and unit-tested.
 */
public final class MarkdownImagePaste {

    /** The sibling directory (next to the Markdown file) that pasted images are written into. */
    public static final String ASSETS_DIR = "assets";

    private MarkdownImagePaste() {}

    /** A non-colliding file name {@code base.ext} / {@code base-N.ext}; {@code exists} tests the target dir. */
    public static String uniqueFileName(Predicate<String> exists, String base, String ext) {
        String name = base + "." + ext;
        if (!exists.test(name)) {
            return name;
        }
        for (int i = 1; i < 10_000; i++) {
            String candidate = base + "-" + i + "." + ext;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        return base + "-" + System.identityHashCode(exists) + "." + ext; // pathological fallback
    }

    /** The forward-slashed path of {@code target} relative to {@code baseDir} (for the Markdown link). */
    public static String relativePath(Path baseDir, Path target) {
        Path rel = baseDir.toAbsolutePath()
                .normalize()
                .relativize(target.toAbsolutePath().normalize());
        return rel.toString().replace('\\', '/');
    }

    /**
     * The Markdown image snippet {@code ![alt](relPath)} (alt may be empty), written so that it parses back to
     * exactly that alt text and that path.
     *
     * <p>A bare link destination cannot contain whitespace or unbalanced parentheses, so
     * {@code ![shot](assets/Screenshot 2026-10-01 at 10.15.32.png)} or {@code (assets/image (1).png)} — the
     * default names for screenshots and duplicate downloads — is not an image at all: the preview showed the
     * raw text while the file sat, already copied, in {@code assets/}. Such a path is wrapped in the
     * angle-bracket form {@code ![alt](<assets/image (1).png>)}, which CommonMark reads back as the same
     * path (so the preview, the PDF and the office exports all resolve it unchanged). Markup characters in
     * the alt text are escaped for the same reason.
     */
    public static String snippet(String relPath, String alt) {
        return "![" + escapeAlt(alt == null ? "" : alt) + "](" + destination(relPath) + ")";
    }

    /** {@code path} as a link destination: bare when that is valid, else {@code <…>} with {@code <>\} escaped. */
    static String destination(String path) {
        boolean needsBrackets = path.isEmpty();
        for (int i = 0; i < path.length() && !needsBrackets; i++) {
            char c = path.charAt(i);
            needsBrackets = Character.isWhitespace(c) || c == '(' || c == ')' || c == '<' || c == '>' || c < 0x20;
        }
        if (!needsBrackets) {
            return path;
        }
        StringBuilder sb = new StringBuilder(path.length() + 4).append('<');
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '<' || c == '>' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c == '\n' || c == '\r' ? ' ' : c); // a destination cannot span lines
        }
        return sb.append('>').toString();
    }

    /**
     * Backslash-escapes what would otherwise be read as markup inside the alt text — brackets (a {@code ]}
     * ends it), inline HTML, code, emphasis and entities — so a file called {@code plot [final].png} or
     * {@code a<b>.png} keeps its name as the alt. An underscore inside a word ({@code screen_shot}) is never
     * emphasis and is left alone.
     */
    private static String escapeAlt(String alt) {
        StringBuilder sb = new StringBuilder(alt.length());
        for (int i = 0; i < alt.length(); i++) {
            char c = alt.charAt(i);
            boolean escape =
                    switch (c) {
                        case '[', ']', '\\', '<', '>', '*', '`', '~' -> true;
                        case '&' ->
                            i + 1 < alt.length()
                                    && (Character.isLetterOrDigit(alt.charAt(i + 1)) || alt.charAt(i + 1) == '#');
                        case '_' ->
                            !(i > 0
                                    && i + 1 < alt.length()
                                    && Character.isLetterOrDigit(alt.charAt(i - 1))
                                    && Character.isLetterOrDigit(alt.charAt(i + 1)));
                        default -> false;
                    };
            if (escape) {
                sb.append('\\');
            }
            sb.append(c == '\n' || c == '\r' ? ' ' : c);
        }
        return sb.toString();
    }

    private static final Set<String> IMAGE_EXTS =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "avif", "apng");
    private static final Pattern IMG_SRC =
            Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * The best image URL/data-URI carried by a browser drag: the explicit drag {@code url}, else an
     * {@code <img src>} pulled from the dragged {@code html}, else {@code string} when it is itself an
     * http(s)/data image reference. Returns {@code null} when none looks like an image source.
     */
    public static String imageUrlFromDrag(String url, String html, String string) {
        String u = firstNonBlank(url);
        if (isImageRef(u)) {
            return u.trim();
        }
        if (html != null) {
            Matcher m = IMG_SRC.matcher(html);
            if (m.find()) {
                String src = m.group(1).trim();
                if (isImageRef(src)) {
                    return src;
                }
            }
        }
        String s = firstNonBlank(string);
        return isImageRef(s) ? s.trim() : null;
    }

    /** True if {@code s} is a plausible image reference: a {@code data:image} URI, or an http(s) URL. */
    private static boolean isImageRef(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("data:image/")) {
            return true;
        }
        return (t.startsWith("http://") || t.startsWith("https://")) && !t.contains("\n");
    }

    private static String firstNonBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * A file extension for a downloaded image URL: from a {@code data:image/<ext>} MIME, else the URL
     * path's extension when it is a known image type, else {@code fallback}. Query/fragment are ignored.
     */
    public static String extensionForUrl(String url, String fallback) {
        if (url == null) {
            return fallback;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("data:image/")) {
            String mime = u.substring("data:image/".length());
            int end = mime.indexOf(';');
            if (end < 0) {
                end = mime.indexOf(',');
            }
            String ext = end >= 0 ? mime.substring(0, end) : mime;
            if (ext.equals("svg+xml")) {
                ext = "svg";
            }
            if (ext.equals("jpeg")) {
                ext = "jpg";
            }
            return IMAGE_EXTS.contains(ext) ? ext : fallback;
        }
        String path = u;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash && dot < path.length() - 1) {
            String ext = path.substring(dot + 1);
            if (ext.equals("jpeg")) {
                ext = "jpg";
            }
            if (IMAGE_EXTS.contains(ext)) {
                return ext;
            }
        }
        return fallback;
    }
}
