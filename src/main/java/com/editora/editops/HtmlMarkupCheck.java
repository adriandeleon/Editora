package com.editora.editops;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        tags(text, out);
        if (document != null && exists != null) {
            links(text, document, root, exists, out);
        }
        out.sort(java.util.Comparator.comparingInt(Problem::start));
        return out.size() > MAX_PROBLEMS ? List.copyOf(out.subList(0, MAX_PROBLEMS)) : List.copyOf(out);
    }

    /** An open element: its name and the span of the name in its start tag. */
    private record Open(String name, int nameStart, int nameEnd) {}

    private static void tags(String text, List<Problem> out) {
        List<Open> stack = new ArrayList<>();
        HtmlTags.walk(text, new HtmlTags.Visitor() {
            @Override
            public void start(String name, int start, int nameEnd, int end, boolean selfClosing, boolean closed) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!closed || selfClosing || HtmlElements.isVoid(lower)) {
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
            if (!exists.test(file)) {
                out.add(new Problem(
                        link.start(),
                        link.end(),
                        Kind.MISSING_FILE,
                        link.value().trim()));
            }
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
