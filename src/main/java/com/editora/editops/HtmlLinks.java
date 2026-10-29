package com.editora.editops;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Pure: the links an HTML page makes with its {@code href}/{@code src} attributes, and where each one points
 * (HTML review B6, C8, D4, D5, C16). Go to Definition and Ctrl-click follow the one at the caret, the markup
 * check reports the local ones whose file is missing, and Related File offers a page's stylesheets and scripts.
 *
 * <p>The language server's own document links are preferred where a server runs (it knows the workspace folder
 * a root-relative {@code /css/site.css} starts from); this is what answers without one, and what both share for
 * the fragment: the server hands {@code about.html#team} back as {@code about.html%23team}.
 */
public final class HtmlLinks {

    private HtmlLinks() {}

    /** The attributes whose value names a file or a page. */
    static final Set<String> LINK_ATTRIBUTES =
            Set.of("href", "src", "poster", "action", "formaction", "data", "cite", "background", "manifest");

    /** The most links {@link #all} reports: a generated page with tens of thousands is not worth checking. */
    public static final int MAX_LINKS = 2_000;

    /** One attribute value naming a link: the element, the attribute, the value as written and its span. */
    public record Link(String element, String attribute, String value, int start, int end) {
        /** A stylesheet ({@code <link rel=stylesheet href>}) or a script ({@code <script src>}) of the page. */
        public boolean asset() {
            return element.equalsIgnoreCase("script") && attribute.equalsIgnoreCase("src")
                    || element.equalsIgnoreCase("link") && attribute.equalsIgnoreCase("href");
        }
    }

    /** Whether {@code language} is HTML proper (not XHTML, which is XML, nor a template language). */
    public static boolean isHtml(@Nullable String language) {
        return "html".equals(language);
    }

    /** Every link value of {@code text}, in document order, at most {@link #MAX_LINKS}. */
    public static List<Link> all(String text) {
        List<Link> out = new ArrayList<>();
        HtmlTags.walk(text, new HtmlTags.Visitor() {
            @Override
            public void start(String name, int start, int nameEnd, int end, boolean selfClosing, boolean closed) {
                if (out.size() >= MAX_LINKS) {
                    return;
                }
                for (HtmlTags.Attribute a : HtmlTags.attributes(text, nameEnd, end)) {
                    if (a.valueStart() >= 0 && LINK_ATTRIBUTES.contains(a.name().toLowerCase(Locale.ROOT))) {
                        out.add(new Link(name, a.name(), a.value(), a.valueStart(), a.valueEnd()));
                    }
                }
            }

            @Override
            public void end(String name, int start, int end) {}
        });
        return out;
    }

    /** The link whose value {@code offset} is in (either edge counts), or null. */
    public static @Nullable Link at(String text, int offset) {
        for (Link l : all(text)) {
            if (l.start() <= offset && offset <= l.end()) {
                return l;
            }
            if (l.start() > offset) {
                return null;
            }
        }
        return null;
    }

    /** The {@code href} of the page's {@code <base>} element, or null when it has none. */
    public static @Nullable String baseHref(String text) {
        for (Link l : all(text)) {
            if (l.element().equalsIgnoreCase("base") && l.attribute().equalsIgnoreCase("href")) {
                return l.value().trim();
            }
            if (l.element().equalsIgnoreCase("body")) {
                break;
            }
        }
        return null;
    }

    /** What a link value names. */
    public enum Kind {
        /** A file of this machine (or of the remote project the page is in). */
        FILE,
        /** An element of the page itself ({@code #team}). */
        FRAGMENT,
        /** A web page, to hand to the browser. */
        WEB,
        /** Nothing to follow: {@code mailto:}, {@code javascript:}, a template expression, an empty value. */
        NONE
    }

    /**
     * Where a link points: a file with the fragment after its {@code #} (empty for none), the page's own element,
     * or a web address.
     */
    public record Target(Kind kind, @Nullable Path file, String fragment, String url) {
        static final Target NONE = new Target(Kind.NONE, null, "", "");

        static Target file(Path file, String fragment) {
            return new Target(Kind.FILE, file, fragment, "");
        }
    }

    /**
     * Resolves {@code value}, written in {@code document}, as a browser would: against the page's {@code <base>}
     * ({@code base}, null for none), a root-relative path against {@code root} (the project; the page's own folder
     * when null), anything else against the page's folder. Percent-escapes are decoded and the query is dropped.
     */
    public static Target resolve(Path document, @Nullable Path root, String value, @Nullable String base) {
        String v = value.trim();
        if (v.isEmpty() || templated(v)) {
            return Target.NONE;
        }
        if (v.startsWith("#")) {
            return new Target(Kind.FRAGMENT, null, decode(v.substring(1)), "");
        }
        if (v.startsWith("//")) {
            return new Target(Kind.WEB, null, "", "https:" + v);
        }
        String scheme = scheme(v);
        if (scheme != null) {
            if (scheme.equals("http") || scheme.equals("https")) {
                return new Target(Kind.WEB, null, "", v);
            }
            if (scheme.equals("file")) {
                return fromUri(v);
            }
            return Target.NONE;
        }
        Path dir = document.toAbsolutePath().getParent();
        if (dir == null) {
            return Target.NONE;
        }
        if (base != null && !base.isEmpty() && !templated(base)) {
            String baseScheme = scheme(base);
            if (baseScheme != null || base.startsWith("//")) {
                return baseScheme == null || baseScheme.equals("http") || baseScheme.equals("https")
                        ? new Target(Kind.WEB, null, "", webResolve(base, v))
                        : Target.NONE;
            }
            Target b = resolvePath(dir, root, base.endsWith("/") ? base + "x" : base);
            Path baseFile = b.file();
            Path baseDir = baseFile == null ? null : baseFile.getParent();
            if (baseDir != null) {
                dir = baseDir;
            }
        }
        return resolvePath(dir, root, v);
    }

    private static Target resolvePath(Path dir, @Nullable Path root, String v) {
        int hash = v.indexOf('#');
        String fragment = hash < 0 ? "" : decode(v.substring(hash + 1));
        String path = hash < 0 ? v : v.substring(0, hash);
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        path = decode(path);
        try {
            Path from = dir;
            if (path.startsWith("/")) {
                from = root != null ? root : dir;
                path = path.replaceFirst("^/+", "");
            }
            Path to = path.isEmpty() ? from : from.resolve(path).normalize();
            return Target.file(to, fragment);
        } catch (InvalidPathException e) {
            return Target.NONE;
        }
    }

    /**
     * A server's document-link target, {@code file:///…/about.html%23team}, as a target: the language server
     * percent-encodes the {@code #} of a fragment, so the last {@code %23} is taken for one when there is no
     * {@code #}.
     */
    public static Target fromUri(String uri) {
        String scheme = scheme(uri);
        if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
            return new Target(Kind.WEB, null, "", uri);
        }
        return fileUri(withoutFragment(uri), fragmentOf(uri));
    }

    private static Target fileUri(String uri, String fragment) {
        try {
            return Target.file(Path.of(java.net.URI.create(uri)), fragment);
        } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return Target.NONE;
        }
    }

    /** The part of a server URI before its fragment ({@code #} or the encoded {@code %23}), for mapping it. */
    public static String withoutFragment(String uri) {
        int hash = uri.indexOf('#');
        if (hash < 0) {
            hash = uri.toLowerCase(Locale.ROOT).lastIndexOf("%23");
        }
        return hash < 0 ? uri : uri.substring(0, hash);
    }

    /** The fragment of a server URI ({@code #} or the encoded {@code %23}), decoded; empty when it has none. */
    public static String fragmentOf(String uri) {
        int hash = uri.indexOf('#');
        if (hash >= 0) {
            return decode(uri.substring(hash + 1));
        }
        int encoded = uri.toLowerCase(Locale.ROOT).lastIndexOf("%23");
        return encoded < 0 ? "" : decode(uri.substring(encoded + 3));
    }

    /** {@code value} resolved against the web address {@code base}; {@code value} itself when either is malformed. */
    private static String webResolve(String base, String value) {
        try {
            return java.net
                    .URI
                    .create(base.startsWith("//") ? "https:" + base : base)
                    .resolve(value)
                    .toString();
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /**
     * Where {@code id="fragment"} (or an {@code <a name="fragment">}) starts in {@code text}: the offset of the
     * element's {@code <}, or -1 when no element has it.
     */
    public static int anchorOffset(String text, String fragment) {
        if (fragment.isEmpty()) {
            return -1;
        }
        int[] found = {-1};
        HtmlTags.walk(text, new HtmlTags.Visitor() {
            @Override
            public void start(String name, int start, int nameEnd, int end, boolean selfClosing, boolean closed) {
                if (found[0] >= 0) {
                    return;
                }
                for (HtmlTags.Attribute a : HtmlTags.attributes(text, nameEnd, end)) {
                    String attr = a.name().toLowerCase(Locale.ROOT);
                    if ((attr.equals("id") || attr.equals("name") && name.equalsIgnoreCase("a"))
                            && a.value().equals(fragment)) {
                        found[0] = start;
                        return;
                    }
                }
            }

            @Override
            public void end(String name, int start, int end) {}
        });
        return found[0];
    }

    /** A value built by a template ({@code {{ url }}}, {@code <%= path %>}, {@code ${base}}): nothing to resolve. */
    static boolean templated(String v) {
        return v.contains("{{") || v.contains("{%") || v.contains("<%") || v.contains("${") || v.contains("<?");
    }

    /** The URI scheme of {@code v} in lower case ({@code mailto}, {@code http}), or null; a drive letter is none. */
    static @Nullable String scheme(String v) {
        int colon = v.indexOf(':');
        if (colon <= 1) {
            return null; // none, or a Windows drive letter (C:\…)
        }
        for (int i = 0; i < colon; i++) {
            char c = v.charAt(i);
            boolean ok = i == 0
                    ? (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')
                    : (Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.');
            if (!ok) {
                return null;
            }
        }
        return v.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /** Decodes {@code %XX} escapes as UTF-8; a malformed escape is kept as written. {@code +} stays a plus. */
    static String decode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int hi = c == '%' && i + 2 < s.length() ? Character.digit(s.charAt(i + 1), 16) : -1;
            int lo = hi >= 0 ? Character.digit(s.charAt(i + 2), 16) : -1;
            if (hi >= 0 && lo >= 0) {
                bytes.write(hi * 16 + lo);
                i += 2;
                continue;
            }
            if (bytes.size() > 0) {
                out.append(bytes.toString(StandardCharsets.UTF_8));
                bytes.reset();
            }
            out.append(c);
        }
        if (bytes.size() > 0) {
            out.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return out.toString();
    }
}
