package com.editora.editops;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import com.editora.text.HtmlElements;
import org.jspecify.annotations.Nullable;

/**
 * Pure: Editora's own markup check of an HTML page (HTML review D5, B11). The HTML language server validates only
 * the CSS and JavaScript embedded in a page, never the markup, so this reports what WebStorm does and VS Code
 * leaves to an extension: an element that is never closed, an end tag that closes nothing, and a local
 * {@code href}/{@code src} whose file does not exist.
 *
 * <p>It is lenient by design: void elements ({@code <br>}), elements whose end tag the HTML syntax lets you leave
 * out ({@code <li>}, {@code <p>}, {@code <td>} …), raw-text content ({@code <script>}, {@code <style>}),
 * comments and self-closing tags ({@code <path/>} in SVG) never count as unclosed. A link built by a template
 * expression, a web address, and an extension-less route ({@code /about}) are not checked.
 *
 * <p>A page that uses template syntax ({@link HtmlElements#hasTemplateSyntax}: Django, Jinja, Jekyll, Hugo,
 * Handlebars, ERB), or that lives in a template folder ({@code templates/}, {@code _layouts/}, {@code partials/} …,
 * where a header and a footer partial each hold one half of an element), is not checked for unclosed elements: its
 * branches and partials are unbalanced as written (HTML review 2 C101). Its links still are.
 */
public final class HtmlMarkupCheck {

    private HtmlMarkupCheck() {}

    /** The most problems one check reports: a page that is not really HTML should not flood the Problems window. */
    public static final int MAX_PROBLEMS = 200;

    /** What is wrong. */
    public enum Kind {
        /** An element whose end tag is required never gets one ({@code <div>} still open at {@code </main>}). */
        UNCLOSED,
        /** An end tag no open element matches. */
        STRAY_END,
        /** A local link whose file does not exist. */
        MISSING_FILE
    }

    /**
     * One problem: its span ({@code start} inclusive, {@code end} exclusive) and what it is about — the element
     * name for the tag problems, the link as written for a missing file.
     */
    public record Problem(int start, int end, Kind kind, String subject) {}

    /**
     * Checks {@code text}. {@code document} is the page's file (null for an untitled buffer: links are then not
     * checked), {@code root} the folder a root-relative link starts from (null: the page's folder), and
     * {@code exists} answers whether a linked file is there (null: links are not checked, as for a remote page).
     */
    public static List<Problem> check(
            String text, @Nullable Path document, @Nullable Path root, @Nullable Predicate<Path> exists) {
        List<Problem> out = new ArrayList<>();
        if (!HtmlElements.hasTemplateSyntax(text) && !inTemplateFolder(document, root)) {
            tags(text, out);
        }
        if (document != null && exists != null) {
            links(text, document, root, exists, out);
        }
        out.sort(java.util.Comparator.comparingInt(Problem::start));
        return out.size() > MAX_PROBLEMS ? List.copyOf(out.subList(0, MAX_PROBLEMS)) : List.copyOf(out);
    }

    /** Folders whose pages are template partials and layouts (Django, Flask, Jekyll, Hugo, Rails …). */
    static final Set<String> TEMPLATE_FOLDERS =
            Set.of("templates", "_includes", "_layouts", "layouts", "partials", "_partials", "includes");

    /**
     * Whether {@code document} is in a {@link #TEMPLATE_FOLDERS} folder: one between {@code root} and the page, or
     * the page's own folder when there is no root (or the page is outside it).
     */
    static boolean inTemplateFolder(@Nullable Path document, @Nullable Path root) {
        Path dir = document == null ? null : document.toAbsolutePath().getParent();
        if (dir == null) {
            return false;
        }
        Path top = root == null ? null : root.toAbsolutePath();
        if (top == null || !dir.startsWith(top) || dir.equals(top)) {
            Path name = dir.getFileName();
            return name != null && TEMPLATE_FOLDERS.contains(name.toString().toLowerCase(Locale.ROOT));
        }
        for (Path part : top.relativize(dir)) {
            if (TEMPLATE_FOLDERS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** An open element: its name and the span of the name in its start tag. */
    private record Open(String name, int nameStart, int nameEnd) {}

    /** Whether an element of {@link #stack} is SVG or MathML, whose elements may close themselves with {@code />}. */
    private static boolean inForeign(List<Open> stack) {
        for (Open o : stack) {
            if (o.name().equals("svg") || o.name().equals("math")) {
                return true;
            }
        }
        return false;
    }

    private static void tags(String text, List<Problem> out) {
        List<Open> stack = new ArrayList<>();
        HtmlTags.walk(text, new HtmlTags.Visitor() {
            @Override
            public void start(String name, int start, int nameEnd, int end, boolean selfClosing, boolean closed) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!closed) {
                    return;
                }
                // "/>" closes only a void or a foreign (SVG, MathML) element; on <a href=/about/> it ends the
                // unquoted value, and on <div/> HTML ignores it (HTML review 2 C103). <image> is <img> in HTML.
                boolean foreign = inForeign(stack) || lower.equals("svg") || lower.equals("math");
                if (foreign ? selfClosing || HtmlElements.isVoid(lower) : HtmlElements.isVoidInHtml(lower)) {
                    return;
                }
                stack.add(new Open(lower, start + 1, nameEnd));
            }

            @Override
            public void end(String name, int start, int end) {
                String lower = name.toLowerCase(Locale.ROOT);
                int k = stack.size() - 1;
                while (k >= 0 && !stack.get(k).name().equals(lower)) {
                    k--;
                }
                if (k < 0) {
                    if (out.size() < MAX_PROBLEMS) {
                        out.add(new Problem(start, end, Kind.STRAY_END, name));
                    }
                    return;
                }
                for (int j = stack.size() - 1; j > k; j--) {
                    unclosed(stack.get(j), out);
                }
                stack.subList(k, stack.size()).clear();
            }
        });
        for (int j = stack.size() - 1; j >= 0; j--) {
            unclosed(stack.get(j), out);
        }
    }

    private static void unclosed(Open open, List<Problem> out) {
        if (!HtmlElements.hasOptionalEnd(open.name()) && out.size() < MAX_PROBLEMS) {
            out.add(new Problem(open.nameStart(), open.nameEnd(), Kind.UNCLOSED, open.name()));
        }
    }

    private static void links(
            String text, Path document, @Nullable Path root, Predicate<Path> exists, List<Problem> out) {
        String base = HtmlLinks.baseHref(text);
        for (HtmlLinks.Link link : HtmlLinks.all(text)) {
            if (out.size() >= MAX_PROBLEMS) {
                return;
            }
            String attribute = link.attribute().toLowerCase(Locale.ROOT);
            if (attribute.equals("action")
                    || attribute.equals("formaction")
                    || link.element().equalsIgnoreCase("base")) {
                continue; // a form posts to a server route, not to a file
            }
            HtmlLinks.Target target = HtmlLinks.resolve(document, root, link.value(), base);
            Path file = target.file();
            if (target.kind() != HtmlLinks.Kind.FILE || file == null || !namesAFile(link.value())) {
                continue;
            }
            if (!exists.test(file) && !servedElsewhere(file, link.value(), document, root, exists)) {
                out.add(new Problem(
                        link.start(),
                        link.end(),
                        Kind.MISSING_FILE,
                        link.value().trim()));
            }
        }
    }

    /**
     * Folders a build tool or site generator serves at the site's root: Vite, Create React App, Angular and
     * SvelteKit's {@code public/}, Hugo's {@code static/}, and {@code assets/} (HTML review 2 C102).
     */
    static final List<String> STATIC_FOLDERS = List.of("public", "static", "assets");

    /** Stylesheet sources a site generator compiles to a {@code .css} of the same name (Jekyll's Sass). */
    private static final List<String> STYLE_SOURCES = List.of(".scss", ".sass", ".less");

    /**
     * Whether a link whose file is not where it points is there all the same once the site is built: under one of
     * the {@link #STATIC_FOLDERS} of the project (the page's folder when there is no project), or as the Sass or
     * Less source of a missing {@code .css}.
     */
    static boolean servedElsewhere(
            Path file, String value, Path document, @Nullable Path root, Predicate<Path> exists) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".css")) {
            String stem = name.substring(0, name.length() - 4);
            for (String ext : STYLE_SOURCES) {
                if (exists.test(file.resolveSibling(stem + ext))) {
                    return true;
                }
            }
        }
        Path top =
                root != null ? root.toAbsolutePath() : document.toAbsolutePath().getParent();
        if (top == null) {
            return false;
        }
        List<String> paths = new ArrayList<>(2);
        if (file.startsWith(top)) {
            paths.add(top.relativize(file).toString());
        }
        paths.add(HtmlLinks.pathPart(value).replaceFirst("^(\\./|/)+", ""));
        for (String folder : STATIC_FOLDERS) {
            Path dir = top.resolve(folder);
            for (String path : paths) {
                try {
                    Path candidate = dir.resolve(path).normalize();
                    if (!path.isEmpty() && candidate.startsWith(dir) && exists.test(candidate)) {
                        return true;
                    }
                } catch (java.nio.file.InvalidPathException e) {
                    // not a path on this system: not served from there either
                }
            }
        }
        return false;
    }

    /** Whether a link's last path segment has an extension: {@code /about} or {@code docs/} may be a server route. */
    static boolean namesAFile(String value) {
        String v = value.trim();
        int cut = v.length();
        for (char c : new char[] {'#', '?'}) {
            int at = v.indexOf(c);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        String path = v.substring(0, cut);
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.lastIndexOf('.') > 0;
    }
}
