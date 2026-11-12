package com.editora.ui;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

/**
 * Pure: IntelliJ's Jump to Type Source from a debugger value — the class a value's type names, read out of what the
 * adapter calls the type ({@code java.util.ArrayList<String>}, {@code *main.Circle}, {@code app::Point}, {@code const
 * Shape &}), and the language server's workspace symbol that declares it.
 */
final class DebugTypeSource {

    private DebugTypeSource() {}

    /** A type's own name ({@code ArrayList}) and what qualifies it ({@code java.util}; {@code ""}: nothing). */
    record TypeName(String simple, String qualifier) {}

    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");

    /** js-debug's type of every object is {@code object}: its class leads the value ({@code Point {x: 1}}). */
    private static final Pattern JS_CLASS = Pattern.compile("^([\\p{Lu}_$][\\p{L}\\p{N}_$]*) [{(\\[]");

    /** Built-in types no project declares: Java's primitives, C's, Go's, Rust's, Python's and JavaScript's. */
    private static final Set<String> BUILT_IN = Set.of(
            "int",
            "long",
            "short",
            "byte",
            "char",
            "boolean",
            "float",
            "double",
            "void",
            "unsigned",
            "signed",
            "size_t",
            "bool",
            "str",
            "string",
            "number",
            "bigint",
            "symbol",
            "undefined",
            "null",
            "object",
            "function",
            "nonetype",
            "none",
            "i8",
            "i16",
            "i32",
            "i64",
            "i128",
            "isize",
            "u8",
            "u16",
            "u32",
            "u64",
            "u128",
            "usize",
            "f32",
            "f64",
            "int8",
            "int16",
            "int32",
            "int64",
            "uint",
            "uint8",
            "uint16",
            "uint32",
            "uint64",
            "uintptr",
            "float32",
            "float64",
            "complex64",
            "complex128",
            "rune",
            "error",
            "any",
            "interface",
            "nil",
            "decimal",
            "sbyte",
            "ushort",
            "ulong",
            "dynamic",
            "tuple",
            "list",
            "dict",
            "set",
            "frozenset",
            "bytes",
            "bytearray",
            "complex",
            "unit",
            "never");

    /**
     * The type {@code type} names, with {@code value} for an adapter that types every object {@code object}
     * (js-debug); {@code null} for a built-in type, an array or map of something, or nothing nameable.
     */
    static @Nullable TypeName parse(@Nullable String type, @Nullable String value) {
        String t = type == null ? "" : type.strip();
        if ((t.isEmpty() || t.equals("object")) && value != null) {
            Matcher js = JS_CLASS.matcher(value.strip());
            if (js.find()) {
                t = js.group(1);
            }
        }
        if (t.isEmpty()) {
            return null;
        }
        int id = t.indexOf(" (id=");
        if (id > 0) {
            t = t.substring(0, id); // an older java-debug's "Foo (id=12)"
        }
        // Declarators around the name: const, struct, *, &, &mut, Go's []…, C's trailing [], a pointer's *.
        boolean changed = true;
        while (changed && !t.isEmpty()) {
            String before = t;
            for (String lead :
                    List.of("const ", "volatile ", "struct ", "class ", "enum ", "union ", "&mut ", "dyn ")) {
                if (t.startsWith(lead)) {
                    t = t.substring(lead.length()).strip();
                }
            }
            while (t.startsWith("*") || t.startsWith("&")) {
                t = t.substring(1).strip();
            }
            if (t.startsWith("[]")) {
                return null; // a Go slice: of what is another type
            }
            while (t.endsWith("*") || t.endsWith("&") || t.endsWith(" const")) {
                t = t.endsWith(" const") ? t.substring(0, t.length() - 6) : t.substring(0, t.length() - 1);
                t = t.strip();
            }
            changed = !t.equals(before);
        }
        if (t.startsWith("[") || t.startsWith("(") || t.startsWith("map[") || isArray(t)) {
            return null; // an array, a slice, a tuple, a map
        }
        int generic = indexOfGeneric(t);
        if (generic == 0) {
            return null;
        }
        if (generic > 0) {
            t = t.substring(0, generic).strip();
        }
        // The qualifier: Rust's and C++'s ::, the dots of Java, C#, Go, Python; a Java nested class's $.
        String qualifier = "";
        String simple = t;
        int cut = t.lastIndexOf("::");
        if (cut >= 0) {
            qualifier = t.substring(0, cut);
            simple = t.substring(cut + 2);
        } else {
            int dot = Math.max(t.lastIndexOf('.'), t.lastIndexOf('$'));
            if (dot >= 0) {
                qualifier = t.substring(0, dot).replace('$', '.');
                simple = t.substring(dot + 1);
            }
        }
        if (!IDENTIFIER.matcher(simple).matches() || BUILT_IN.contains(simple.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (simple.chars().allMatch(Character::isDigit)) {
            return null; // Foo$1: an anonymous class has no name to look up
        }
        return new TypeName(simple, qualifier);
    }

    /** Whether {@code t} is an array ({@code String[]}, {@code int[3]}), not a Go generic ({@code Stack[int]}). */
    private static boolean isArray(String t) {
        if (!t.endsWith("]")) {
            return false;
        }
        int open = t.lastIndexOf('[');
        return open < 0 || t.substring(open + 1, t.length() - 1).strip().chars().allMatch(Character::isDigit);
    }

    /** Where a type's arguments start ({@code <} or Go's {@code [}), or -1. */
    private static int indexOfGeneric(String t) {
        int angle = t.indexOf('<');
        int square = t.indexOf('[');
        if (angle < 0) {
            return square;
        }
        return square < 0 ? angle : Math.min(angle, square);
    }

    /**
     * The match that declares {@code type}: a class, interface, enum or struct of that name — the one whose container
     * agrees with the qualifier first, then a project file before a library's, then a library's own top-level class
     * before a nested or shaded one ({@code java.util.ArrayList}, not {@code Arrays.ArrayList}), then Search
     * Everywhere's order (the JDK's, the shorter container). {@code null}: none.
     */
    static LspManager.@Nullable WorkspaceSymbolMatch best(
            TypeName type, List<LspManager.WorkspaceSymbolMatch> matches) {
        LspManager.WorkspaceSymbolMatch best = null;
        int bestScore = -1;
        for (LspManager.WorkspaceSymbolMatch m : matches) {
            if (!typeKind(m.kind()) || !bareName(m.name()).equals(type.simple())) {
                continue;
            }
            if (m.file() == null && m.libraryUri() == null) {
                continue;
            }
            int score = (sameQualifier(type.qualifier(), m.container()) ? 4 : 0)
                    + (m.file() != null ? 2 : 0)
                    + (SymbolRanking.nestedLibraryType(m) || SymbolRanking.shadedLibraryType(m) ? 0 : 1);
            if (score > bestScore || (score == bestScore && SymbolRanking.TIE_BREAK.compare(m, best) < 0)) {
                best = m;
                bestScore = score;
            }
        }
        return best;
    }

    /** A symbol's name without its type parameters or signature ({@code List<E>}: {@code List}). */
    private static String bareName(String name) {
        int cut = name.length();
        for (char c : new char[] {'<', '(', '[', ' '}) {
            int at = name.indexOf(c);
            if (at > 0) {
                cut = Math.min(cut, at);
            }
        }
        return name.substring(0, cut);
    }

    /**
     * Whether a symbol's container agrees with the qualifier: the same, or one ends with the other ({@code java.util}
     * and {@code util}; a nested class's {@code Outer}; Rust's {@code crate::shapes} and {@code shapes}).
     */
    static boolean sameQualifier(String qualifier, @Nullable String container) {
        if (qualifier.isEmpty() || container == null || container.isEmpty()) {
            return false;
        }
        String q = qualifier.replace("::", ".");
        String c = container.replace("::", ".");
        return q.equals(c) || q.endsWith("." + c) || c.endsWith("." + q);
    }

    private static boolean typeKind(String kind) {
        return switch (kind) {
            case "class", "interface", "enum", "struct" -> true;
            default -> false;
        };
    }
}
