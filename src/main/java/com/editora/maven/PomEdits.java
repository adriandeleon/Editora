package com.editora.maven;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Targeted, format-preserving edits to a {@code pom.xml}, as text.
 *
 * <p><b>Text, deliberately, and not a DOM round-trip.</b> {@link PomParser} reads a pom into a model, but
 * writing one back through a {@code Transformer} reserialises the whole file — re-indenting it, collapsing
 * or dropping the archetype's comments, and rewriting the XML declaration. A generated pom is something the
 * user is about to read and edit, so an edit here changes the characters it has to change and nothing else.
 *
 * <p>Everything is <b>depth-aware</b>: a {@code <url>} inside {@code <scm>} or {@code <repositories>} is not
 * the project's own, and a {@code <version>} inside a {@code <dependency>} is not a plugin's. A regex over
 * the whole file gets all of these wrong, which is why this walks the element structure instead.
 *
 * <p>Not a general XML editor: it understands exactly the shapes a pom uses.
 */
public final class PomEdits {

    private PomEdits() {}

    /** A blank value means "leave whatever is already there", so an untouched form field changes nothing. */
    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Sets the project's own {@code <url>} — the direct child of {@code <project>}, never a nested one.
     *
     * <p>Inserted after {@code <name>} (else {@code <version>}, else {@code <artifactId>}) when absent,
     * which is where the Maven pom convention puts it.
     */
    public static String setProjectUrl(String pom, String url) {
        if (blank(pom) || blank(url)) {
            return pom;
        }
        int[] root = rootChildren(pom);
        if (root == null) {
            return pom;
        }
        int[] existing = findChild(pom, root[0], root[1], "url");
        if (existing != null) {
            return pom.substring(0, existing[1]) + escape(url) + pom.substring(existing[2]);
        }
        for (String after : new String[] {"name", "version", "artifactId"}) {
            int[] anchor = findChild(pom, root[0], root[1], after);
            if (anchor != null) {
                String indent = indentOf(pom, anchor[0]);
                return pom.substring(0, anchor[3]) + "\n" + indent + "<url>" + escape(url) + "</url>"
                        + pom.substring(anchor[3]);
            }
        }
        return pom;
    }

    /**
     * Sets a {@code <properties>} entry, creating the block or the entry when either is missing.
     *
     * <p>The block is created before {@code <dependencies>} when there is one — again the conventional
     * position, so a pom this touches still looks like a pom somebody wrote.
     */
    public static String setProperty(String pom, String name, String value) {
        if (blank(pom) || blank(name) || blank(value)) {
            return pom;
        }
        int[] root = rootChildren(pom);
        if (root == null) {
            return pom;
        }
        int[] props = findChild(pom, root[0], root[1], "properties");
        if (props == null) {
            int[] anchor = findChild(pom, root[0], root[1], "dependencies");
            int at = anchor != null ? anchor[0] : root[1];
            String indent = anchor != null ? indentOf(pom, anchor[0]) : "  ";
            String block = "<properties>\n" + indent + "  <" + name + ">" + escape(value) + "</" + name + ">\n" + indent
                    + "</properties>\n" + indent;
            return pom.substring(0, at) + block + pom.substring(at);
        }
        int[] entry = findChild(pom, props[1], props[2], name);
        if (entry != null) {
            return pom.substring(0, entry[1]) + escape(value) + pom.substring(entry[2]);
        }
        String indent = innerIndentOf(pom, props);
        String added = "\n" + indent + "<" + name + ">" + escape(value) + "</" + name + ">";
        int at = trimEnd(pom, props[1], props[2]);
        return pom.substring(0, at) + added + pom.substring(at);
    }

    /**
     * Rewrites the {@code <version>} of every {@code <plugin>} whose {@code groupId:artifactId} is in
     * {@code versions}, wherever it sits — {@code <build><plugins>}, {@code <pluginManagement>}, a profile.
     *
     * <p>A plugin with no {@code <groupId>} is {@code org.apache.maven.plugins}, per Maven's own default, so
     * the archetype's abbreviated entries are matched too. A plugin with no {@code <version>} is left alone
     * rather than given one: its version comes from somewhere else (a parent, a bom), and pinning it here
     * would change resolution rather than update it.
     */
    public static String setPluginVersions(String pom, Map<String, String> versions) {
        return setVersions(pom, versions, "plugin", "org.apache.maven.plugins");
    }

    /** Shared by the plugin and dependency rewrites — they differ only in element name and group default. */
    private static String setVersions(String pom, Map<String, String> versions, String element, String groupDefault) {
        if (blank(pom) || versions == null || versions.isEmpty()) {
            return pom;
        }
        StringBuilder out = new StringBuilder(pom);
        // Applied last-to-first so each splice leaves the offsets of the ones before it untouched.
        for (int[] span : reversed(elementsNamed(pom, element))) {
            String ga = coordinateKey(pom, span, groupDefault);
            String want = ga == null ? null : versions.get(ga);
            if (want == null || want.isBlank()) {
                continue;
            }
            int[] version = findChild(pom, span[1], span[2], "version");
            if (version != null) {
                out.replace(version[1], version[2], escape(want));
            }
        }
        return out.toString();
    }

    /**
     * Every plugin the pom pins a version for, as {@code groupId:artifactId → version}.
     *
     * <p>A plugin with no {@code <version>} is omitted rather than mapped to null: it has no version *here*
     * to compare against or replace, and {@link #setPluginVersions} would leave it alone anyway.
     */
    public static Map<String, String> pluginVersions(String pom) {
        Map<String, String> out = new LinkedHashMap<>();
        if (blank(pom)) {
            return out;
        }
        for (int[] span : elementsNamed(pom, "plugin")) {
            String ga = coordinateKey(pom, span, "org.apache.maven.plugins");
            int[] version = ga == null ? null : findChild(pom, span[1], span[2], "version");
            if (version != null) {
                String v = pom.substring(version[1], version[2]).trim();
                if (!v.isEmpty()) {
                    out.put(ga, v);
                }
            }
        }
        return out;
    }

    /**
     * The project's {@code <packaging>}, defaulting to {@code jar} as Maven does; null when there is no
     * {@code <project>} root at all.
     *
     * <p>Read here rather than through {@link PomParser} on purpose: that validates a whole model and
     * throws on an incomplete pom, so a minimal or hand-written aggregator would be misread as unparseable
     * — and a caller asking only "what packaging is this" would then have to guess.
     */
    public static String packaging(String pom) {
        if (blank(pom)) {
            return null;
        }
        int[] root = rootChildren(pom);
        if (root == null) {
            return null;
        }
        int[] element = findChild(pom, root[0], root[1], "packaging");
        if (element == null) {
            return "jar";
        }
        String value = pom.substring(element[1], element[2]).trim();
        return value.isEmpty() ? "jar" : value;
    }

    /**
     * Every dependency the pom pins a version for, as {@code groupId:artifactId → version}.
     *
     * <p>A dependency whose version is a property reference ({@code ${junit.version}}) is omitted: rewriting
     * the reference would replace the indirection the author chose, and rewriting the property is a
     * different edit with different blast radius.
     */
    public static Map<String, String> dependencyVersions(String pom) {
        Map<String, String> out = new LinkedHashMap<>();
        if (blank(pom)) {
            return out;
        }
        for (int[] span : elementsNamed(pom, "dependency")) {
            String ga = coordinateKey(pom, span, null);
            int[] version = ga == null ? null : findChild(pom, span[1], span[2], "version");
            if (version != null) {
                String v = pom.substring(version[1], version[2]).trim();
                if (!v.isEmpty() && !v.startsWith("${")) {
                    out.put(ga, v);
                }
            }
        }
        return out;
    }

    /** Rewrites the {@code <version>} of every {@code <dependency>} named in {@code versions}. */
    public static String setDependencyVersions(String pom, Map<String, String> versions) {
        return setVersions(pom, versions, "dependency", null);
    }

    /** {@code groupId:artifactId} of a plugin element, defaulting the group as Maven does. */
    private static String coordinateKey(String pom, int[] span, String groupDefault) {
        int[] artifact = findChild(pom, span[1], span[2], "artifactId");
        if (artifact == null) {
            return null;
        }
        int[] group = findChild(pom, span[1], span[2], "groupId");
        if (group == null) {
            // A plugin with no groupId is org.apache.maven.plugins; a dependency without one is malformed.
            if (groupDefault == null) {
                return null;
            }
            return groupDefault + ":" + pom.substring(artifact[1], artifact[2]).trim();
        }
        return pom.substring(group[1], group[2]).trim() + ":"
                + pom.substring(artifact[1], artifact[2]).trim();
    }

    /** Every element with this exact name in the document, in document order. */
    private static java.util.List<int[]> elementsNamed(String pom, String element) {
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        int i = 0;
        while (i < pom.length()) {
            int open = pom.indexOf("<" + element, i);
            if (open < 0) {
                break;
            }
            int after = open + element.length() + 1;
            // "<plugins>"/"<pluginManagement>" both start with "<plugin", and "<dependencies>" with
            // "<dependency" — only an element whose name ends right here is the one wanted.
            if (after < pom.length() && (pom.charAt(after) == '>' || Character.isWhitespace(pom.charAt(after)))) {
                int[] span = elementAt(pom, open);
                if (span != null) {
                    spans.add(span);
                    i = span[3];
                    continue;
                }
            }
            i = after;
        }
        return spans;
    }

    private static java.util.List<int[]> reversed(java.util.List<int[]> in) {
        java.util.List<int[]> out = new java.util.ArrayList<>(in);
        java.util.Collections.reverse(out);
        return out;
    }

    // --- element scanning ------------------------------------------------------------------------

    /** The inner range of the root {@code <project>} element: {@code {innerStart, innerEnd}}. */
    private static int[] rootChildren(String pom) {
        int i = 0;
        while (i < pom.length()) {
            int lt = pom.indexOf('<', i);
            if (lt < 0) {
                return null;
            }
            if (skippable(pom, lt)) {
                i = skipTo(pom, lt);
                continue;
            }
            int[] span = elementAt(pom, lt);
            return span == null ? null : new int[] {span[1], span[2]};
        }
        return null;
    }

    /**
     * The first <em>direct child</em> named {@code name} within {@code [from, to)}.
     *
     * @return {@code {tagStart, innerStart, innerEnd, tagEnd}}, or null
     */
    private static int[] findChild(String pom, int from, int to, String name) {
        int i = from;
        while (i < to) {
            int lt = pom.indexOf('<', i);
            if (lt < 0 || lt >= to) {
                return null;
            }
            if (skippable(pom, lt)) {
                i = skipTo(pom, lt);
                continue;
            }
            if (pom.startsWith("</", lt)) {
                return null; // the parent's own close tag — no such child
            }
            int[] span = elementAt(pom, lt);
            if (span == null) {
                return null;
            }
            if (nameAt(pom, lt).equals(name)) {
                return span;
            }
            i = span[3]; // skip the whole sibling, so its descendants are never mistaken for children
        }
        return null;
    }

    /**
     * The element starting at {@code lt}.
     *
     * @return {@code {tagStart, innerStart, innerEnd, tagEnd}}; for a self-closing tag the inner range is
     *     empty and sits at the end of the tag
     */
    private static int[] elementAt(String pom, int lt) {
        int gt = tagEnd(pom, lt);
        if (gt < 0) {
            return null;
        }
        String name = nameAt(pom, lt);
        if (name.isEmpty()) {
            return null;
        }
        if (pom.charAt(gt - 1) == '/') {
            return new int[] {lt, gt + 1, gt + 1, gt + 1};
        }
        int depth = 1;
        int i = gt + 1;
        while (i < pom.length()) {
            int next = pom.indexOf('<', i);
            if (next < 0) {
                return null;
            }
            if (skippable(pom, next)) {
                i = skipTo(pom, next);
                continue;
            }
            int end = tagEnd(pom, next);
            if (end < 0) {
                return null;
            }
            if (pom.startsWith("</", next)) {
                if (nameAt(pom, next).equals(name) && --depth == 0) {
                    return new int[] {lt, gt + 1, next, end + 1};
                }
            } else if (nameAt(pom, next).equals(name) && pom.charAt(end - 1) != '/') {
                depth++;
            }
            i = end + 1;
        }
        return null;
    }

    /** The element name at a {@code <} — for both open and close tags. */
    private static String nameAt(String pom, int lt) {
        int i = lt + 1;
        if (i < pom.length() && pom.charAt(i) == '/') {
            i++;
        }
        int start = i;
        while (i < pom.length()
                && !Character.isWhitespace(pom.charAt(i))
                && pom.charAt(i) != '>'
                && pom.charAt(i) != '/') {
            i++;
        }
        return pom.substring(start, i);
    }

    /** Index of the {@code >} closing the tag at {@code lt}, honouring quoted attribute values. */
    private static int tagEnd(String pom, int lt) {
        char quote = 0;
        for (int i = lt + 1; i < pom.length(); i++) {
            char c = pom.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /** Comments, CDATA, processing instructions and doctypes — content, never elements. */
    private static boolean skippable(String pom, int lt) {
        return pom.startsWith("<!--", lt)
                || pom.startsWith("<![CDATA[", lt)
                || pom.startsWith("<?", lt)
                || pom.startsWith("<!", lt);
    }

    private static int skipTo(String pom, int lt) {
        String close = pom.startsWith("<!--", lt) ? "-->" : pom.startsWith("<![CDATA[", lt) ? "]]>" : ">";
        int end = pom.indexOf(close, lt + 2);
        return end < 0 ? pom.length() : end + close.length();
    }

    // --- formatting ------------------------------------------------------------------------------

    /** The whitespace before {@code at} on its own line, so an inserted sibling lines up with it. */
    private static String indentOf(String pom, int at) {
        int lineStart = pom.lastIndexOf('\n', at - 1) + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = lineStart; i < at && Character.isWhitespace(pom.charAt(i)); i++) {
            sb.append(pom.charAt(i));
        }
        return sb.toString();
    }

    /** The indent an entry inside {@code element} should carry: its first child's, else the parent's + 2. */
    private static String innerIndentOf(String pom, int[] element) {
        int firstChild = pom.indexOf('<', element[1]);
        if (firstChild > 0 && firstChild < element[2]) {
            return indentOf(pom, firstChild);
        }
        return indentOf(pom, element[0]) + "  ";
    }

    /** {@code to}, walked back over trailing whitespace, so an insert lands before the closing tag's line. */
    private static int trimEnd(String pom, int from, int to) {
        int i = to;
        while (i > from && Character.isWhitespace(pom.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /** Only the three characters that could end the element early; a pom value needs nothing more. */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
