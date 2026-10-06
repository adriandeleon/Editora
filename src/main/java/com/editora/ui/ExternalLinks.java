package com.editora.ui;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.io.PathContainment;

import static com.editora.i18n.Messages.tr;

/**
 * The single choke point for "open this link": decides where a link string may go before anything is handed
 * to the operating system.
 *
 * <p>Link strings arrive from content the user did not write — a README's {@code [setup](…)} clicked in the
 * Markdown preview, a URL printed by a build, a plugin's {@code openUrl}, a release's page from the update
 * check. The OS opener ({@code HostServices.showDocument} → {@code open} / {@code xdg-open} /
 * {@code rundll32 url.dll}) does whatever the string's scheme is registered for: {@code file:///…/setup.command}
 * runs it, {@code smb://host/share} mounts it (and offers credentials), a custom scheme launches its app, and a
 * scheme-less string is resolved against the process working directory or read as an option. So:
 *
 * <ul>
 *   <li>{@code http}, {@code https} and {@code mailto} go to the OS opener ({@link Browser}).
 *   <li>A relative path or a {@code file:} URL is resolved against the document it was clicked in and opened
 *       <em>inside the editor</em> ({@link InEditor}) — only when it is a regular file canonically under the
 *       project root or the document's own folder.
 *   <li>Everything else is refused with a status message ({@link Refused}); nothing scheme-less ever reaches
 *       the OS.
 * </ul>
 *
 * <p>{@link #classify} is the pure decision (it stats the local filesystem for the in-editor case);
 * {@link #open} applies it. Unit-tested.
 */
final class ExternalLinks {

    /** What to do with a link. */
    sealed interface Target permits Browser, InEditor, Refused {}

    /** Hand {@code url} to the OS opener — an {@code http(s)} or {@code mailto} URL, nothing else. */
    record Browser(String url) implements Target {}

    /** Open {@code file} in an editor tab. */
    record InEditor(Path file) implements Target {}

    /** Do nothing but say why: {@code messageKey} is a catalog key taking {@code detail} as its argument. */
    record Refused(String messageKey, String detail) implements Target {}

    static final String KEY_SCHEME = "status.link.refusedScheme";
    static final String KEY_OUTSIDE = "status.link.outside";
    static final String KEY_MISSING = "status.link.missing";
    static final String KEY_INVALID = "status.link.invalid";

    /** RFC 3986 scheme. A one-letter "scheme" is a Windows drive ({@code C:\docs\a.md}), i.e. a path. */
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]+):");

    private static final Pattern WEB = Pattern.compile("(?i)^https?://[^\\s/?#]+.*");
    private static final Pattern MAILTO = Pattern.compile("(?i)^mailto:[^\\s]+");

    /** Longest link echoed back in a status message. */
    private static final int DETAIL_MAX = 120;

    private ExternalLinks() {}

    /**
     * Decides what {@code link} may do. {@code documentFile} is the file the link was clicked in (null: no
     * document — a URL from a tool, a plugin, the update check; or an unsaved/remote buffer), {@code
     * projectRoot} the window's project root (null: none).
     */
    static Target classify(String link, Path documentFile, Path projectRoot) {
        if (link == null || link.isBlank()) {
            return new Refused(KEY_INVALID, "");
        }
        String s = link.strip();
        if (s.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            return new Refused(KEY_INVALID, detail(s.replaceAll("\\p{Cntrl}", "?")));
        }
        Matcher m = SCHEME.matcher(s);
        if (!m.find()) {
            return local(s, s, false, documentFile, projectRoot);
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "http", "https" -> {
                return WEB.matcher(s).matches()
                        ? new Browser(s.replace(" ", "%20").replace("\"", "%22"))
                        : new Refused(KEY_INVALID, detail(s));
            }
            case "mailto" -> {
                return MAILTO.matcher(s).matches() ? new Browser(s) : new Refused(KEY_INVALID, detail(s));
            }
            case "file" -> {
                String path = filePath(s);
                return path == null
                        ? new Refused(KEY_INVALID, detail(s))
                        : local(s, path, true, documentFile, projectRoot);
            }
            default -> {
                return new Refused(KEY_SCHEME, scheme);
            }
        }
    }

    /**
     * The local path a {@code file:} URL names (percent-decoded), or null when it is not a plain local file
     * reference: an authority ({@code file://host/share} — a UNC/SMB path on Windows) or an unparseable URL.
     */
    private static String filePath(String url) {
        try {
            URI uri = new URI(url);
            if (uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty()) {
                return null;
            }
            String path = uri.isOpaque() ? uri.getSchemeSpecificPart() : uri.getPath();
            if (path == null || path.isEmpty()) {
                return null;
            }
            // file:///C:/docs/a.md → "/C:/docs/a.md": drop the slash in front of a drive letter.
            return path.matches("^/[A-Za-z]:[/\\\\].*") ? path.substring(1) : path;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /**
     * A scheme-less link ({@code decoded} false: still carrying its {@code #fragment}/{@code ?query} and
     * {@code %20} escapes) or the path of a {@code file:} URL ({@code decoded} true): a file to open in the
     * editor, if it is ours to open.
     */
    private static Target local(String original, String rawPath, boolean decoded, Path documentFile, Path projectRoot) {
        String path = rawPath;
        if (!decoded) {
            int cut = indexOfAny(path, '#', '?');
            path = percentDecode(cut < 0 ? path : path.substring(0, cut));
        }
        if (path.isBlank()) {
            return new Refused(KEY_INVALID, detail(original)); // "#section": an in-document anchor
        }
        if (isUncLike(path)) {
            return new Refused(KEY_OUTSIDE, detail(original)); // \\host\share — never even stat it
        }
        Path docDir = documentFile == null || documentFile.getFileSystem() != FileSystems.getDefault()
                ? null
                : documentFile.toAbsolutePath().getParent();
        Path target;
        try {
            Path p = Path.of(path);
            if (!p.isAbsolute() && docDir == null) {
                return new Refused(KEY_INVALID, detail(original)); // nothing to resolve against; never the cwd
            }
            target = (p.isAbsolute() ? p : docDir.resolve(p)).normalize();
            // "/docs/guide.md" in a repository's README means "from the repository root" (forge convention).
            if (!allowed(target, docDir, projectRoot) && projectRoot != null && path.startsWith("/")) {
                Path fromRoot = projectRoot.resolve(path.substring(1)).normalize();
                if (allowed(fromRoot, docDir, projectRoot) && Files.isRegularFile(fromRoot)) {
                    target = fromRoot; // only stats a path inside the project
                }
            }
        } catch (RuntimeException e) {
            return new Refused(KEY_INVALID, detail(original));
        }
        if (!allowed(target, docDir, projectRoot)) {
            return new Refused(KEY_OUTSIDE, detail(original)); // decided before existence: no probing outside
        }
        return Files.isRegularFile(target) ? new InEditor(target) : new Refused(KEY_MISSING, detail(original));
    }

    /** Canonically under the project root or the document's own folder (symlinks resolved). */
    private static boolean allowed(Path target, Path docDir, Path projectRoot) {
        return PathContainment.isWithin(projectRoot, target) || PathContainment.isWithin(docDir, target);
    }

    private static boolean isUncLike(String path) {
        return path.length() >= 2
                && (path.charAt(0) == '/' || path.charAt(0) == '\\')
                && (path.charAt(1) == '/' || path.charAt(1) == '\\');
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    /** Decodes {@code %XX} escapes as UTF-8 ({@code +} stays a plus); a malformed escape is kept verbatim. */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int hi = i + 2 < bytes.length ? Character.digit(bytes[i + 1], 16) : -1;
            int lo = i + 2 < bytes.length ? Character.digit(bytes[i + 2], 16) : -1;
            if (bytes[i] == '%' && hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo);
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String detail(String link) {
        return link.length() > DETAIL_MAX ? link.substring(0, DETAIL_MAX) + "…" : link;
    }

    /**
     * Applies {@link #classify}: {@code browser} receives an allowed URL (null: no OS opener available — the
     * link is dropped), {@code editor} a file to open in a tab, {@code status} the message for a refusal.
     */
    static void open(
            String link,
            Path documentFile,
            Path projectRoot,
            Consumer<String> browser,
            Consumer<Path> editor,
            Consumer<String> status) {
        if (link == null) {
            return; // nothing was asked for (an item with no URL) — not a refusal worth reporting
        }
        switch (classify(link, documentFile, projectRoot)) {
            case Browser b -> {
                if (browser != null) {
                    browser.accept(b.url());
                }
            }
            case InEditor e -> editor.accept(e.file());
            case Refused r -> status.accept(tr(r.messageKey(), r.detail()));
        }
    }

    /** {@link #open} with the JavaFX {@code HostServices} as the OS opener (it may be null before startup). */
    static void open(
            String link,
            Path documentFile,
            Path projectRoot,
            javafx.application.HostServices hostServices,
            Consumer<Path> editor,
            Consumer<String> status) {
        open(link, documentFile, projectRoot, hostServices == null ? null : hostServices::showDocument, editor, status);
    }
}
