package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.text.FoldRegions.Region;
import org.jspecify.annotations.Nullable;

/**
 * A Go file's outline read from its lines, the way gopls lists a file's symbols: functions, methods labelled by
 * their receiver ({@code (*Kubelet).Run}), types (one per spec of a {@code type ( … )} group) with their struct
 * fields or interface methods, and the names of {@code const} and {@code var} declarations, grouped or not. A
 * method is listed under its receiver's type when that type is declared in the file.
 *
 * <p>The TextMate grammar gives a bare {@code entity.name.type.go} to declarations and references alike, so the
 * generic outline listed the package clause, embedded fields and composite literals as types, left methods without
 * their receiver and had no fields or constants at all (Project Map review 3, C3). Go is written gofmt'd and its
 * declarations are line-shaped, so a line scan over {@link BraceLines} reads them reliably. Pure: any thread.
 */
final class GoOutline {

    private GoOutline() {}

    private static final Pattern FUNC = Pattern.compile("^func\\s*(\\(([^)]*)\\))?\\s*([\\p{L}_][\\p{L}\\p{N}_]*)");
    private static final Pattern SPEC =
            Pattern.compile("^([\\p{L}_][\\p{L}\\p{N}_]*)\\s*(\\[[^\\]]*\\])?\\s*(=\\s*)?(.*)$");
    private static final Pattern NAMES =
            Pattern.compile("^([\\p{L}_][\\p{L}\\p{N}_]*(?:\\s*,\\s*[\\p{L}_][\\p{L}\\p{N}_]*)*)(\\s+\\S.*|\\s*=.*)?$");
    private static final Pattern IDENT = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");

    /** The parameter list a Go row shows: Go has no overloads, so the names add only width (C15). */
    static final String SOME_PARAMETERS = "(…)";

    static final String NO_PARAMETERS = "()";

    static List<StructureNode> build(List<String> lines) {
        BraceLines src = new BraceLines(lines, true);
        List<StructureNode> roots = new ArrayList<>();
        Map<String, StructureNode> types = new HashMap<>();
        List<StructureNode> methods = new ArrayList<>();
        Map<StructureNode, String> receivers = new HashMap<>();
        Map<StructureNode, String> constantTypes = new HashMap<>();
        int n = src.size();
        for (int line = 0; line < n; line++) {
            if (src.startsInside(line) || src.braceStart(line) != 0 || src.parenStart(line) != 0) {
                continue;
            }
            String code = src.code(line).strip();
            if (code.startsWith("func")) {
                StructureNode fn = function(src, line, code, receivers);
                if (fn != null) {
                    (receivers.containsKey(fn) ? methods : roots).add(fn);
                }
            } else if (code.startsWith("type") && isKeyword(code, "type")) {
                for (StructureNode type : specs(src, line, code.substring(4).strip(), true)) {
                    roots.add(type);
                    types.putIfAbsent(type.label(), type);
                }
            } else if (code.startsWith("const") && isKeyword(code, "const")) {
                roots.addAll(specs(src, line, code.substring(5).strip(), false, "constant", constantTypes));
            } else if (code.startsWith("var") && isKeyword(code, "var")) {
                roots.addAll(specs(src, line, code.substring(3).strip(), false, "variable", null));
            }
            int end = src.blockEnd(line);
            if (end > line) {
                line = end; // the body of what this line opened is not top-level
            }
        }
        for (StructureNode method : methods) {
            StructureNode type = types.get(receivers.get(method));
            (type == null ? roots : type.children()).add(method);
        }
        // A typed constant is one of its type's values (`PodPending PodPhase = "Pending"`): it is listed under that
        // type when the file declares it, so an enumeration reads as one, not as hundreds of top-level rows.
        for (Map.Entry<StructureNode, String> typed : constantTypes.entrySet()) {
            StructureNode type = types.get(typed.getValue());
            if (type != null && roots.remove(typed.getKey())) {
                type.children().add(typed.getKey());
            }
        }
        roots.sort(Comparator.comparingInt(StructureNode::line));
        for (StructureNode type : types.values()) {
            type.children().sort(Comparator.comparingInt(StructureNode::line));
        }
        return roots;
    }

    private static boolean isKeyword(String code, String keyword) {
        return code.length() == keyword.length()
                || !Character.isLetterOrDigit(code.charAt(keyword.length())) && code.charAt(keyword.length()) != '_';
    }

    /** A {@code func} line: a function, or a method whose receiver's type name goes into {@code receivers}. */
    private static @Nullable StructureNode function(
            BraceLines src, int line, String code, Map<StructureNode, String> receivers) {
        Matcher m = FUNC.matcher(code);
        if (!m.find()) {
            return null; // a function type or literal at top level: not a declaration
        }
        String name = m.group(3);
        String receiver = m.group(2);
        String label = name;
        String base = null;
        if (receiver != null) {
            String[] words = receiver.strip().split("\\s+");
            String type = words[words.length - 1];
            int generic = type.indexOf('[');
            if (generic >= 0) {
                type = type.substring(0, generic); // (s *Set[T]) → Set
            }
            boolean pointer = type.startsWith("*");
            base = pointer ? type.substring(1) : type;
            label = "(" + (pointer ? "*" : "") + base + ")." + name;
        }
        int at = m.end();
        if (at < code.length() && code.charAt(at) == '[') { // type parameters
            at = closing(code, at, '[', ']') + 1;
        }
        while (at < code.length() && code.charAt(at) == ' ') {
            at++;
        }
        String parameters = SOME_PARAMETERS;
        if (at < code.length() && code.charAt(at) == '(') {
            int next = at + 1;
            while (next < code.length() && code.charAt(next) == ' ') {
                next++;
            }
            if (next < code.length() && code.charAt(next) == ')') {
                parameters = NO_PARAMETERS;
            }
        }
        int end = src.blockEnd(line);
        StructureNode node = new StructureNode(
                new Region(line, end), label, receiver == null ? "function" : "method", line, parameters);
        if (base != null) {
            receivers.put(node, base);
        }
        return node;
    }

    private static int closing(String code, int open, char opener, char closer) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == opener) {
                depth++;
            } else if (c == closer && --depth == 0) {
                return i;
            }
        }
        return code.length() - 1;
    }

    private static List<StructureNode> specs(BraceLines src, int line, String rest, boolean types) {
        return specs(src, line, rest, types, null, null);
    }

    /**
     * The declarations of a {@code type}, {@code const} or {@code var} line whose keyword is cut off ({@code
     * rest}): one spec, or each spec of the {@code ( … )} group it opens.
     */
    private static List<StructureNode> specs(
            BraceLines src,
            int line,
            String rest,
            boolean types,
            @Nullable String valueKind,
            @Nullable Map<StructureNode, String> valueTypes) {
        List<StructureNode> out = new ArrayList<>();
        String[] lastType = {null}; // a bare name in a const group repeats the spec above it, type and all
        if (!rest.startsWith("(")) {
            spec(src, line, rest, types, valueKind, out, valueTypes, lastType);
            return out;
        }
        int end = src.blockEnd(line);
        boolean continued = false;
        for (int spec = line + 1; spec < end; spec++) {
            if (src.startsInside(spec)
                    || src.braceStart(spec) != src.braceStart(line)
                    || src.parenStart(spec) != src.parenStart(line) + 1) {
                continue;
            }
            String code = src.code(spec).strip();
            if (code.isEmpty()) {
                continue;
            }
            if (!continued) {
                spec(src, spec, code, types, valueKind, out, valueTypes, lastType);
            }
            continued = continues(code);
        }
        return out;
    }

    /** Whether a line's expression goes on to the next line ({@code "a" +}). */
    private static boolean continues(String code) {
        if (code.isEmpty()) {
            return false;
        }
        char last = code.charAt(code.length() - 1);
        return "+-*/%&|^=<>!,.".indexOf(last) >= 0;
    }

    private static void spec(
            BraceLines src,
            int line,
            String code,
            boolean types,
            @Nullable String valueKind,
            List<StructureNode> out,
            @Nullable Map<StructureNode, String> valueTypes,
            @Nullable String[] lastType) {
        if (types) {
            Matcher m = SPEC.matcher(code);
            if (!m.matches()) {
                return;
            }
            String body = m.group(4).strip();
            String kind = body.startsWith("struct") && isKeyword(body, "struct")
                    ? "struct"
                    : body.startsWith("interface") && isKeyword(body, "interface") ? "interface" : "class";
            int end = src.blockEnd(line);
            StructureNode type = new StructureNode(new Region(line, end), m.group(1), kind, line);
            if (end > line && !"class".equals(kind)) {
                members(src, line, end, "struct".equals(kind), type.children());
            }
            out.add(type);
            return;
        }
        Matcher m = NAMES.matcher(code);
        if (!m.matches()) {
            return;
        }
        int end = src.blockEnd(line);
        String rest = m.group(2) == null ? "" : m.group(2).strip();
        if (!rest.isEmpty()) {
            Matcher typed = TYPED_VALUE.matcher(rest);
            lastType[0] = typed.matches() ? typed.group(1) : null;
        }
        for (String name : m.group(1).split("\\s*,\\s*")) {
            if (!"_".equals(name)) {
                StructureNode value =
                        new StructureNode(end > line ? new Region(line, end) : null, name, valueKind, line);
                out.add(value);
                if (valueTypes != null && lastType[0] != null) {
                    valueTypes.put(value, lastType[0]);
                }
            }
        }
    }

    /** What follows a constant's names when it has a type of its own: {@code PodPhase = "Pending"}. */
    private static final Pattern TYPED_VALUE = Pattern.compile("^([\\p{L}_][\\p{L}\\p{N}_]*)\\s*(=.*)?$");

    /** The fields of a struct, or the methods of an interface, whose body runs from {@code open} to {@code end}. */
    private static void members(BraceLines src, int open, int end, boolean struct, List<StructureNode> out) {
        int depth = src.braceEnd(open);
        boolean continued = false;
        for (int line = open + 1; line < end; line++) {
            if (src.startsInside(line) || src.braceStart(line) != depth || src.parenStart(line) != src.parenEnd(open)) {
                continue;
            }
            String code = src.code(line).strip();
            if (code.isEmpty() || code.startsWith("}")) {
                continue;
            }
            if (!continued) {
                if (struct) {
                    field(src, line, code, out);
                } else {
                    interfaceMember(line, code, out);
                }
            }
            continued = continues(code);
        }
    }

    private static void field(BraceLines src, int line, String code, List<StructureNode> out) {
        String declaration = code.replaceAll("\\s*`[^`]*`?\\s*$", "").replaceAll("\\s*\"\\s*\"\\s*$", "");
        Matcher names = NAMES.matcher(declaration);
        int end = src.blockEnd(line);
        Region region = end > line ? new Region(line, end) : null;
        if (names.matches() && names.group(2) != null && !names.group(2).strip().startsWith("=")) {
            String type = names.group(2).strip();
            for (String name : names.group(1).split("\\s*,\\s*")) {
                StructureNode field = new StructureNode(region, name, "field", line);
                if (region != null && type.startsWith("struct")) {
                    members(src, line, end, true, field.children()); // an anonymous struct's own fields
                }
                out.add(field);
            }
            return;
        }
        // An embedded field: its name is its type ({@code *metav1.ObjectMeta}, {@code lifecycle.PodSyncHandlers}).
        String embedded = declaration.startsWith("*") ? declaration.substring(1).strip() : declaration;
        int generic = embedded.indexOf('[');
        if (generic > 0) {
            embedded = embedded.substring(0, generic);
        }
        if (embedded.matches("[\\p{L}_][\\p{L}\\p{N}_]*(\\.[\\p{L}_][\\p{L}\\p{N}_]*)?")) {
            out.add(new StructureNode(null, embedded, "field", line));
        }
    }

    private static void interfaceMember(int line, String code, List<StructureNode> out) {
        Matcher name = IDENT.matcher(code);
        if (!name.lookingAt()) {
            return; // a type set (~int | ~float64)
        }
        int at = name.end();
        if (at < code.length() && code.charAt(at) == '(') {
            int next = at + 1;
            while (next < code.length() && code.charAt(next) == ' ') {
                next++;
            }
            boolean none = next < code.length() && code.charAt(next) == ')';
            out.add(new StructureNode(null, name.group(), "method", line, none ? NO_PARAMETERS : SOME_PARAMETERS));
        } else if (!code.contains("|")
                && code.matches("[\\p{L}_][\\p{L}\\p{N}_]*(\\.[\\p{L}_][\\p{L}\\p{N}_]*)?(\\[.*\\])?")) {
            out.add(new StructureNode(null, code.replaceAll("\\[.*$", ""), "interface", line)); // an embedded one
        }
    }
}
