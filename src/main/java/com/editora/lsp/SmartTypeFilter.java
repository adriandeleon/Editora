package com.editora.lsp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import org.jspecify.annotations.Nullable;

/**
 * Smart Type completion's filter over jdtls's completion list: only what fits the expected type
 * ({@link JavaExpectedType}). A field, variable, constant or method stays when its type (the type jdtls puts in
 * {@code labelDetails.description}) can be assigned to the expected one; after {@code new}, a class stays when
 * it is the expected type or a JDK implementation of it; {@code null} stays for a reference type and
 * {@code true} / {@code false} for a boolean.
 *
 * <p>Assignability is decided from the names: the same type (type arguments dropped), primitive widening,
 * boxing, {@code Object}, and the supertypes of common JDK types. When neither side tells (a project type that
 * may implement or extend the expected one), jdtls's own ranking decides: jdtls adds 20 to the relevance of a
 * proposal of a compatible type and 30 of the exact type, and its {@code sortText} is
 * {@code 999999999 - (relevance * 16 + kind)}, so a proposal at least 20 above the weakest of its kind is kept.
 * Pure.
 */
public final class SmartTypeFilter {

    private SmartTypeFilter() {}

    private static final Set<CompletionIconKind> TYPED = Set.of(
            CompletionIconKind.FIELD,
            CompletionIconKind.VARIABLE,
            CompletionIconKind.PARAMETER,
            CompletionIconKind.CONSTANT,
            CompletionIconKind.ENUM_MEMBER,
            CompletionIconKind.PROPERTY,
            CompletionIconKind.METHOD,
            CompletionIconKind.FUNCTION);

    private static final Set<CompletionIconKind> CLASSES = Set.of(
            CompletionIconKind.CLASS,
            CompletionIconKind.INTERFACE,
            CompletionIconKind.ENUM,
            CompletionIconKind.STRUCT,
            CompletionIconKind.CONSTRUCTOR);

    private static final List<String> PRIMITIVES =
            List.of("byte", "short", "char", "int", "long", "float", "double", "boolean");

    /** Each primitive and the wider ones it converts to without a cast. */
    private static final Map<String, Set<String>> WIDENS = Map.of(
            "byte", Set.of("short", "int", "long", "float", "double"),
            "short", Set.of("int", "long", "float", "double"),
            "char", Set.of("int", "long", "float", "double"),
            "int", Set.of("long", "float", "double"),
            "long", Set.of("float", "double"),
            "float", Set.of("double"));

    private static final Map<String, String> BOXES = Map.of(
            "byte", "Byte",
            "short", "Short",
            "char", "Character",
            "int", "Integer",
            "long", "Long",
            "float", "Float",
            "double", "Double",
            "boolean", "Boolean");

    /** Supertypes of common JDK types (beyond Object), by simple name. */
    private static final Map<String, Set<String>> SUPERTYPES = new HashMap<>();

    static {
        Set<String> number = Set.of("Number", "Comparable", "Serializable", "Constable");
        for (String boxed : List.of("Byte", "Short", "Integer", "Long", "Float", "Double")) {
            SUPERTYPES.put(boxed, number);
        }
        SUPERTYPES.put("BigInteger", number);
        SUPERTYPES.put("BigDecimal", number);
        SUPERTYPES.put("Character", Set.of("Comparable", "Serializable", "Constable"));
        SUPERTYPES.put("Boolean", Set.of("Comparable", "Serializable", "Constable"));
        SUPERTYPES.put("String", Set.of("CharSequence", "Comparable", "Serializable", "Constable"));
        Set<String> builder = Set.of("CharSequence", "Appendable", "Serializable", "Comparable");
        SUPERTYPES.put("StringBuilder", builder);
        SUPERTYPES.put("StringBuffer", builder);
        Set<String> collection = Set.of("Collection", "Iterable", "SequencedCollection");
        Set<String> list = union(collection, Set.of("List"));
        for (String t : List.of("ArrayList", "CopyOnWriteArrayList", "Vector", "Stack")) {
            SUPERTYPES.put(t, union(list, Set.of("RandomAccess", "Cloneable", "Serializable")));
        }
        SUPERTYPES.put("LinkedList", union(list, Set.of("Deque", "Queue", "Cloneable", "Serializable")));
        SUPERTYPES.put("List", collection);
        Set<String> set = union(collection, Set.of("Set"));
        SUPERTYPES.put("HashSet", union(set, Set.of("Cloneable", "Serializable")));
        SUPERTYPES.put("LinkedHashSet", union(set, Set.of("SequencedSet", "Cloneable", "Serializable")));
        SUPERTYPES.put("TreeSet", union(set, Set.of("SortedSet", "NavigableSet", "SequencedSet", "Serializable")));
        SUPERTYPES.put("Set", collection);
        SUPERTYPES.put("SortedSet", set);
        SUPERTYPES.put("NavigableSet", union(set, Set.of("SortedSet")));
        Set<String> queue = union(collection, Set.of("Queue"));
        SUPERTYPES.put("Queue", collection);
        SUPERTYPES.put("Deque", queue);
        SUPERTYPES.put("ArrayDeque", union(queue, Set.of("Deque", "Cloneable", "Serializable")));
        SUPERTYPES.put("PriorityQueue", union(queue, Set.of("Serializable")));
        Set<String> map = Set.of("Map");
        SUPERTYPES.put("HashMap", union(map, Set.of("Cloneable", "Serializable")));
        SUPERTYPES.put("LinkedHashMap", union(map, Set.of("SequencedMap", "Cloneable", "Serializable")));
        SUPERTYPES.put("TreeMap", union(map, Set.of("SortedMap", "NavigableMap", "SequencedMap", "Serializable")));
        SUPERTYPES.put("ConcurrentHashMap", union(map, Set.of("ConcurrentMap", "Serializable")));
        SUPERTYPES.put("SortedMap", map);
        SUPERTYPES.put("NavigableMap", union(map, Set.of("SortedMap")));
        SUPERTYPES.put("Path", Set.of("Comparable", "Iterable", "Watchable"));
        SUPERTYPES.put("Optional", Set.of());
        SUPERTYPES.put("Stream", Set.of("BaseStream", "AutoCloseable"));
        SUPERTYPES.put("Object", Set.of());
        SUPERTYPES.put("CharSequence", Set.of());
        SUPERTYPES.put("Collection", Set.of("Iterable"));
        SUPERTYPES.put("Iterable", Set.of());
        SUPERTYPES.put("Map", Set.of());
        SUPERTYPES.put("Number", Set.of("Serializable"));
        SUPERTYPES.put("Comparable", Set.of());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.HashSet<>(a);
        out.addAll(b);
        return out;
    }

    /** Whether a value of type {@code from} can be assigned to {@code to}: yes, no, or not known from names. */
    enum Fit {
        YES,
        NO,
        UNKNOWN
    }

    /** The items that fit {@code expected}, in their order. */
    public static List<Completion> filter(List<Completion> items, String expected, boolean afterNew) {
        String to = erasure(expected);
        if (to.isEmpty() || "void".equals(to)) {
            return List.of();
        }
        Map<CompletionIconKind, Long> weakest = new HashMap<>();
        for (Completion c : items) {
            long r = relevance(c);
            if (r >= 0) {
                weakest.merge(c.iconKind(), r, Math::min);
            }
        }
        List<Completion> out = new ArrayList<>();
        for (Completion c : items) {
            if (keeps(c, to, afterNew, weakest)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean keeps(Completion c, String to, boolean afterNew, Map<CompletionIconKind, Long> weakest) {
        CompletionIconKind kind = c.iconKind();
        String name = erasure(name(c));
        if (kind == CompletionIconKind.KEYWORD) {
            return switch (name) {
                case "null" -> !PRIMITIVES.contains(to);
                case "true", "false" -> "boolean".equals(to) || "Boolean".equals(to);
                default -> false;
            };
        }
        if (CLASSES.contains(kind)) {
            if (!afterNew) {
                return name.equals(to); // its static factories: `List.of(…)`
            }
            return fit(name, to) == Fit.YES;
        }
        if (afterNew || !TYPED.contains(kind)) {
            return false;
        }
        String type = c.detail() == null ? "" : erasure(c.detail());
        if (type.isEmpty() || "void".equals(type)) {
            return false;
        }
        return switch (fit(type, to)) {
            case YES -> true;
            case NO -> false;
            case UNKNOWN -> {
                long r = relevance(c);
                Long min = weakest.get(kind);
                yield r >= 0 && min != null && r - min >= 20;
            }
        };
    }

    /** The name the item stands for: its label without parameters. */
    private static String name(Completion c) {
        String label = c.label();
        int paren = label.indexOf('(');
        return (paren < 0 ? label : label.substring(0, paren)).strip();
    }

    /** {@code java.util.List<String>[]} → {@code List[]}: no package, no type arguments. */
    static String erasure(String type) {
        String t = type.strip();
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            } else if (depth == 0) {
                out.append(ch);
            }
        }
        String s = out.toString().replace(" ", "").replace("...", "[]");
        int dims = s.indexOf('[');
        String base = dims < 0 ? s : s.substring(0, dims);
        String suffix = dims < 0 ? "" : s.substring(dims);
        int dot = base.lastIndexOf('.');
        return (dot < 0 ? base : base.substring(dot + 1)) + suffix;
    }

    /** Whether {@code from} can be assigned to {@code to} (both erased). */
    static Fit fit(String from, String to) {
        if (from.equals(to)) {
            return Fit.YES;
        }
        boolean fromArray = from.endsWith("[]");
        boolean toArray = to.endsWith("[]");
        if (fromArray || toArray) {
            if ("Object".equals(to) || "Cloneable".equals(to) || "Serializable".equals(to)) {
                return fromArray ? Fit.YES : Fit.NO;
            }
            if (fromArray && toArray) {
                String f = from.substring(0, from.length() - 2);
                String t = to.substring(0, to.length() - 2);
                return PRIMITIVES.contains(f) || PRIMITIVES.contains(t) ? Fit.NO : fit(f, t);
            }
            return Fit.NO;
        }
        boolean fromPrimitive = PRIMITIVES.contains(from);
        boolean toPrimitive = PRIMITIVES.contains(to);
        if (fromPrimitive && toPrimitive) {
            return WIDENS.getOrDefault(from, Set.of()).contains(to) ? Fit.YES : Fit.NO;
        }
        if (fromPrimitive) { // boxing, then the box's supertypes
            String box = BOXES.getOrDefault(from, "");
            return box.equals(to)
                            || "Object".equals(to)
                            || SUPERTYPES.getOrDefault(box, Set.of()).contains(to)
                    ? Fit.YES
                    : Fit.NO;
        }
        if (toPrimitive) { // unboxing, then widening
            for (Map.Entry<String, String> e : BOXES.entrySet()) {
                if (e.getValue().equals(from)) {
                    return fit(e.getKey(), to);
                }
            }
            return Fit.NO;
        }
        if ("Object".equals(to)) {
            return Fit.YES;
        }
        Set<String> supers = SUPERTYPES.get(from);
        if (supers != null) {
            if (supers.contains(to)) {
                return Fit.YES;
            }
            return Fit.NO; // a JDK type: it extends nothing of the project's, and its own supertypes are listed
        }
        return SUPERTYPES.containsKey(to) && isFinalJdk(to) ? Fit.NO : Fit.UNKNOWN;
    }

    /** JDK types no project type can extend. */
    private static boolean isFinalJdk(String type) {
        return Set.of(
                        "String",
                        "Integer",
                        "Long",
                        "Short",
                        "Byte",
                        "Double",
                        "Float",
                        "Character",
                        "Boolean",
                        "StringBuilder",
                        "StringBuffer",
                        "Optional",
                        "BigInteger",
                        "BigDecimal")
                .contains(type);
    }

    /** jdtls's relevance of {@code c}, from its sortText, or -1 when it carries none. */
    static long relevance(Completion c) {
        @Nullable String sort = c.sortText();
        if (sort == null || sort.length() != 9 || !sort.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return (999_999_999L - Long.parseLong(sort)) / 16;
    }
}
