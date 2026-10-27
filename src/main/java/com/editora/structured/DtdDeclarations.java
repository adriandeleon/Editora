package com.editora.structured;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The declarations of a DTD — {@code <!ELEMENT>}, {@code <!ATTLIST>}, {@code <!ENTITY>}, {@code <!NOTATION>} — read
 * from its text for the Structure window (XML review 2 C11). A DTD opens as XML (so lemminx and the XML grammar get
 * it), but it has no element tree: the element outline was empty and the XML tree preview said the markup before
 * the root element was not well-formed.
 *
 * <p>Attributes are listed under the element they belong to, wherever its {@code <!ATTLIST>} is; a list for an
 * element the file does not declare gets a row of its own. Comments and processing instructions are skipped, and
 * so are {@code IGNORE} sections; an {@code INCLUDE} section's declarations are read. Pure; any thread.
 */
public final class DtdDeclarations {

    /** What a declaration declares. */
    public enum Kind {
        ELEMENT,
        ATTLIST,
        ATTRIBUTE,
        ENTITY,
        PARAMETER_ENTITY,
        NOTATION
    }

    /**
     * One declaration: its kind, name, the 0-based lines it starts and ends on, and (for an element or an attribute
     * list) its attributes.
     */
    public record Decl(Kind kind, String name, int line, int endLine, List<Decl> attributes) {}

    /** At most this many declarations are read (as many rows as the XML outline lists). */
    public static final int MAX_DECLARATIONS = 5000;

    private static final List<String> DTD_EXTENSIONS = List.of(".dtd", ".ent", ".mod");

    private DtdDeclarations() {}

    /** Whether {@code fileName} names a DTD or one of its entity or module files. */
    public static boolean isDtdFileName(@Nullable String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        return DTD_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /** The declarations of {@code text}, in file order (attribute lists merged into their elements). */
    public static List<Decl> parse(String text) {
        int[] newlines = newlines(text);
        List<Decl> out = new ArrayList<>();
        Map<String, Integer> elementAt = new LinkedHashMap<>(); // element name → index in out
        int n = text.length();
        int count = 0;
        int i = 0;
        while (i < n && count < MAX_DECLARATIONS) {
            if (text.charAt(i) != '<') {
                i++;
            } else if (text.startsWith("<!--", i)) {
                i = after(text, i + 4, "-->");
            } else if (text.startsWith("<?", i)) {
                i = after(text, i + 2, "?>");
            } else if (text.startsWith("<![", i)) {
                int open = text.indexOf('[', i + 3);
                String keyword = open < 0 ? "" : text.substring(i + 3, open).trim();
                i = "IGNORE".equals(keyword) ? after(text, i + 3, "]]>") : (open < 0 ? n : open + 1);
            } else if (text.startsWith("<!", i)) {
                int end = declarationEnd(text, i);
                Decl d = declaration(text, i, end, newlines);
                if (d != null) {
                    count++;
                    add(out, elementAt, d);
                }
                i = end;
            } else {
                i++;
            }
        }
        return out;
    }

    /** Adds {@code d}, folding an attribute list into its element's row (whichever comes first). */
    private static void add(List<Decl> out, Map<String, Integer> elementAt, Decl d) {
        Integer at = d.kind() == Kind.ELEMENT || d.kind() == Kind.ATTLIST ? elementAt.get(d.name()) : null;
        if (at == null) {
            if (d.kind() == Kind.ELEMENT || d.kind() == Kind.ATTLIST) {
                elementAt.put(d.name(), out.size());
            }
            out.add(d);
            return;
        }
        Decl was = out.get(at);
        List<Decl> attributes = new ArrayList<>(was.attributes());
        attributes.addAll(d.attributes());
        // An element's own declaration gives the row its kind and line; an attribute list only adds attributes.
        Decl row = d.kind() == Kind.ELEMENT
                ? new Decl(Kind.ELEMENT, d.name(), d.line(), d.endLine(), attributes)
                : new Decl(was.kind(), was.name(), was.line(), was.endLine(), attributes);
        out.set(at, row);
    }

    /** The declaration in {@code text[start, end)} (a {@code <!…>}), or null when it is none of the four. */
    private static @Nullable Decl declaration(String text, int start, int end, int[] newlines) {
        int bodyEnd = end > start && text.charAt(end - 1) == '>' ? end - 1 : end;
        List<int[]> tokens = tokens(text, start + 2, bodyEnd);
        if (tokens.size() < 2) {
            return null;
        }
        String keyword = token(text, tokens.get(0));
        int line = lineOf(newlines, start);
        int endLine = lineOf(newlines, Math.max(start, end - 1));
        switch (keyword) {
            case "ELEMENT" -> {
                return new Decl(Kind.ELEMENT, token(text, tokens.get(1)), line, endLine, List.of());
            }
            case "NOTATION" -> {
                return new Decl(Kind.NOTATION, token(text, tokens.get(1)), line, endLine, List.of());
            }
            case "ENTITY" -> {
                boolean parameter = "%".equals(token(text, tokens.get(1)));
                if (parameter && tokens.size() < 3) {
                    return null;
                }
                String name = token(text, tokens.get(parameter ? 2 : 1));
                return new Decl(parameter ? Kind.PARAMETER_ENTITY : Kind.ENTITY, name, line, endLine, List.of());
            }
            case "ATTLIST" -> {
                return new Decl(
                        Kind.ATTLIST, token(text, tokens.get(1)), line, endLine, attributes(text, tokens, newlines));
            }
            default -> {
                return null;
            }
        }
    }

    /** The attribute definitions after an {@code <!ATTLIST name}: name, type (a NOTATION's group too), default. */
    private static List<Decl> attributes(String text, List<int[]> tokens, int[] newlines) {
        List<Decl> out = new ArrayList<>();
        int k = 2;
        while (k < tokens.size()) {
            String name = token(text, tokens.get(k));
            if (name.startsWith("%")) {
                k++; // a parameter entity standing for definitions: nothing to name here
                continue;
            }
            int line = lineOf(newlines, tokens.get(k)[0]);
            out.add(new Decl(Kind.ATTRIBUTE, name, line, line, List.of()));
            k += 2; // the name and its type
            if (k - 1 < tokens.size() && "NOTATION".equals(token(text, tokens.get(k - 1)))) {
                k++; // NOTATION (a|b): the group is part of the type
            }
            if (k < tokens.size() && "#FIXED".equals(token(text, tokens.get(k)))) {
                k++; // #FIXED "value"
            }
            k++; // the default
        }
        return out;
    }

    /**
     * The tokens of {@code text[from, to)} as {start, end} pairs: a quoted string, a parenthesised group (nested
     * groups included), or a run of other non-space characters.
     */
    private static List<int[]> tokens(String text, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            if (c == '"' || c == '\'') {
                int close = text.indexOf(c, i + 1);
                i = close < 0 || close >= to ? to : close + 1;
            } else if (c == '(') {
                int depth = 0;
                while (i < to) {
                    char g = text.charAt(i++);
                    if (g == '(') {
                        depth++;
                    } else if (g == ')' && --depth == 0) {
                        break;
                    }
                }
                while (i < to && "?*+".indexOf(text.charAt(i)) >= 0) {
                    i++;
                }
            } else {
                while (i < to && !Character.isWhitespace(text.charAt(i)) && "(\"'".indexOf(text.charAt(i)) < 0) {
                    i++;
                }
            }
            out.add(new int[] {start, i});
        }
        return out;
    }

    private static String token(String text, int[] span) {
        return text.substring(span[0], span[1]);
    }

    /** The end of the {@code <!…>} at {@code start}: just past its {@code >}, quoted strings skipped. */
    private static int declarationEnd(String text, int start) {
        int n = text.length();
        int i = start + 2;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int close = text.indexOf(c, i + 1);
                i = close < 0 ? n : close + 1;
            } else if (c == '>') {
                return i + 1;
            } else {
                i++;
            }
        }
        return n;
    }

    private static int after(String text, int from, String marker) {
        int at = text.indexOf(marker, from);
        return at < 0 ? text.length() : at + marker.length();
    }

    private static int[] newlines(String text) {
        int[] out = new int[16];
        int count = 0;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            if (count == out.length) {
                out = Arrays.copyOf(out, count * 2);
            }
            out[count++] = i;
        }
        return Arrays.copyOf(out, count);
    }

    /** The 0-based line of {@code offset}: how many newlines come before it. */
    private static int lineOf(int[] newlines, int offset) {
        int at = Arrays.binarySearch(newlines, offset);
        return at >= 0 ? at : -at - 1;
    }
}
