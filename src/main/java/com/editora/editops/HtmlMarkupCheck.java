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
 *
 * <p>An Angular template ({@link #isAngular}) is compiled by Angular, which honours {@code />} on any element
 * ({@code <app-header />}), and its page-relative links are relative to the app, not to the component's folder: they
 * are also looked up in the app's source folder (the one holding its {@code index.html}) and its {@code public/}
 * folder (HTML review 3 C201). Elsewhere {@code <my-el />} stays open, as in a browser.
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
        boolean angular = isAngular(text, document, root, exists);
        if (!HtmlElements.hasTemplateSyntax(text) && !inTemplateFolder(document, root)) {
            tags(text, angular, out);
        }
        if (document != null && exists != null) {
            links(text, document, root, exists, angular, out);
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

    /** How many folders above a page an {@code angular.json} or an app's {@code index.html} is looked for. */
    private static final int MAX_UP = 8;

    /**
     * Whether {@code document} is an Angular template: a {@code *.component.html}, a page below an
     * {@code angular.json} (up to {@code root}; {@code exists} answers, null: not looked for), or one that uses
     * Angular syntax ({@link HtmlElements#hasAngularSyntax}).
     */
    static boolean isAngular(
            String text, @Nullable Path document, @Nullable Path root, @Nullable Predicate<Path> exists) {
        Path name = document == null ? null : document.getFileName();
        if (name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".component.html")) {
            return true;
        }
        return document != null && exists != null && above(document, root, "angular.json", exists) != null
                || HtmlElements.hasAngularSyntax(text);
    }

    /**
     * The nearest folder holding {@code file}, from the page's own folder up to {@code root} (or {@link #MAX_UP}
     * folders up when the page is not in it), or null.
     */
    private static @Nullable Path above(Path document, @Nullable Path root, String file, Predicate<Path> exists) {
        Path top = root == null ? null : root.toAbsolutePath();
        Path dir = document.toAbsolutePath().getParent();
        boolean inside = top != null && dir != null && dir.startsWith(top);
        for (int k = 0; dir != null && (inside || k <= MAX_UP); k++, dir = dir.getParent()) {
            if (exists.test(dir.resolve(file))) {
                return dir;
            }
            if (inside && dir.equals(top)) {
                return null;
            }
        }
        return null;
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

    private static void tags(String text, boolean angular, List<Problem> out) {
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
                // Angular closes any element with it (HTML review 3 C201).
                boolean foreign = angular || inForeign(stack) || lower.equals("svg") || lower.equals("math");
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
            String text,
            Path document,
            @Nullable Path root,
            Predicate<Path> exists,
            boolean angular,
            List<Problem> out) {
        String base = HtmlLinks.baseHref(text);
        Site site = new Site(
                document, root, exists, angular, base != null && base.trim().startsWith("/"));
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
            if (!exists.test(file) && !site.serves(file, link.value())) {
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
     * SvelteKit's {@code public/}, Hugo's {@code static/} (HTML review 2 C102). Not {@code assets/}: Angular serves
     * {@code src/assets} at {@code /assets/}, and Hugo's {@code assets/} is built, not published (HTML review 3 C208).
     */
    static final List<String> STATIC_FOLDERS = List.of("public", "static");

    /**
     * Files at a project's root that say a build tool serves it, and so its {@link #STATIC_FOLDERS} at the site's
     * root for a page-relative link too; without one, only a root-relative link is looked for there.
     */
    static final List<String> BUILD_MARKERS = List.of(
            "package.json",
            "angular.json",
            "vite.config.js",
            "vite.config.ts",
            "vite.config.mjs",
            "vite.config.mts",
            "svelte.config.js",
            "svelte.config.ts",
            "hugo.toml",
            "hugo.yaml",
            "hugo.json",
            "config.toml");

    /** Stylesheet sources a site generator compiles to a {@code .css} of the same name (Jekyll's Sass). */
    private static final List<String> STYLE_SOURCES = List.of(".scss", ".sass", ".less");

    /**
     * Where a page's links may be served from once the site is built, looked up once per check: the project (the
     * page's folder when there is none), whether it has a build marker, and, for an Angular template, the app's
     * source folder.
     */
    static final class Site {
        private final Path document;
        private final @Nullable Path root;
        private final @Nullable Path top;
        private final Predicate<Path> exists;
        private final boolean angular;
        private final boolean rootBase;
        private @Nullable Boolean built;
        private @Nullable Path app;
        private boolean appLooked;

        /** {@code rootBase}: the page's {@code <base href>} is root-relative, so its links are too. */
        Site(Path document, @Nullable Path root, Predicate<Path> exists, boolean angular, boolean rootBase) {
            this.document = document;
            this.root = root;
            this.top = root != null
                    ? root.toAbsolutePath()
                    : document.toAbsolutePath().getParent();
            this.exists = exists;
            this.angular = angular;
            this.rootBase = rootBase;
        }

        /**
         * Whether a link whose file is not where it points is there all the same once the site is built: as the Sass
         * or Less source of a missing {@code .css}; for an Angular template, in the app's source folder; or under one
         * of the {@link #STATIC_FOLDERS} — for a root-relative link always, for a page-relative one only in a project
         * with a {@link #BUILD_MARKERS build marker}, and then at the page's own path below the folder.
         */
        boolean serves(Path file, String value) {
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            if (name.toLowerCase(Locale.ROOT).endsWith(".css")) {
                String stem = name.substring(0, name.length() - 4);
                for (String ext : STYLE_SOURCES) {
                    if (exists.test(file.resolveSibling(stem + ext))) {
                        return true;
                    }
                }
            }
            Path site = top;
            if (site == null) {
                return false;
            }
            String written = HtmlLinks.pathPart(value).trim();
            String bare = written.replaceFirst("^(\\./|/)+", "");
            boolean rootRelative = written.startsWith("/");
            String path;
            if (angular && !rootRelative) {
                // a component's link is relative to the app (its <base href>), not to the component's folder
                Path src = appFolder();
                if (src != null && lookUp(src, bare)) {
                    return true;
                }
                path = bare;
            } else if (rootRelative || rootBase || built()) {
                path = file.startsWith(site) ? site.relativize(file).toString() : bare;
            } else {
                return false;
            }
            for (String folder : STATIC_FOLDERS) {
                if (lookUp(site.resolve(folder), path)) {
                    return true;
                }
            }
            return false;
        }

        private boolean lookUp(Path dir, String path) {
            if (path.isEmpty()) {
                return false;
            }
            try {
                Path candidate = dir.resolve(path).normalize();
                return candidate.startsWith(dir) && exists.test(candidate);
            } catch (java.nio.file.InvalidPathException e) {
                return false; // not a path on this system: not served from there either
            }
        }

        private boolean built() {
            Boolean b = built;
            if (b == null) {
                Path site = top;
                boolean found = false;
                for (String marker : BUILD_MARKERS) {
                    if (site != null && exists.test(site.resolve(marker))) {
                        found = true;
                        break;
                    }
                }
                b = found;
                built = b;
            }
            return b;
        }

        /** The Angular app's source folder: the nearest one above the page that holds an {@code index.html}. */
        private @Nullable Path appFolder() {
            if (!appLooked) {
                appLooked = true;
                app = above(document, root, "index.html", exists);
            }
            return app;
        }
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
