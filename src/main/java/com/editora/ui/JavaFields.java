package com.editora.ui;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fields of the classes in a heuristic Java outline. The TextMate grammar marks no field name, so a class's
 * state never showed in the Structure window or on the Project Map without the language server, and the Map never
 * has the server's symbols for a file that is not open (review 2 C11, review 3 C6). Each class body is read at its
 * member depth ({@link BraceLines}) for {@code [modifiers] Type name (= | ; | ,)} lines, and each such line is added
 * to its class as a {@code field} leaf. Pure: any thread.
 */
final class JavaFields {

    private JavaFields() {}

    private static final Pattern ANNOTATION = Pattern.compile("@[\\w.$]+(\\s*\\([^)]*\\))?\\s*");
    private static final Pattern FIELD = Pattern.compile("^(?:(?:public|protected|private|static|final|transient"
            + "|volatile)\\s+)*([\\w$.]+(?:\\s*<.*>)?(?:\\s*\\[\\s*])*)\\s+([\\w$]+)\\s*(?:\\[\\s*]\\s*)*(=|;|,)");
    private static final Set<String> NOT_TYPES = Set.of(
            "return",
            "throw",
            "new",
            "package",
            "import",
            "else",
            "case",
            "yield",
            "assert",
            "goto",
            "break",
            "continue",
            "default");

    /** Adds the fields of every class among {@code nodes} (and their nested classes) read from {@code lines}. */
    static void add(List<StructureNode> nodes, List<String> lines) {
        BraceLines code = null;
        for (StructureNode node : nodes) {
            if (isType(node)) {
                code = code == null ? new BraceLines(lines, false) : code;
                addTo(node, code);
            }
        }
    }

    private static boolean isType(StructureNode node) {
        String kind = node.kind();
        return node.region() != null
                && ("type".equals(kind)
                        || "class".equals(kind)
                        || "interface".equals(kind)
                        || "enum".equals(kind)
                        || "record".equals(kind));
    }

    private static void addTo(StructureNode type, BraceLines code) {
        var region = type.region();
        if (region == null) {
            return;
        }
        for (StructureNode child : type.children()) {
            if (isType(child)) {
                addTo(child, code);
            }
        }
        int open = region.startLine();
        int end = Math.min(region.endLine(), code.size() - 1);
        if (open < 0 || open >= code.size()) {
            return;
        }
        int depth = code.braceEnd(open);
        int paren = code.parenEnd(open);
        Set<Integer> taken = new HashSet<>();
        for (StructureNode child : type.children()) {
            taken.add(child.line());
        }
        boolean statementOpen = false;
        boolean added = false;
        for (int line = open + 1; line < end; line++) {
            if (code.startsInside(line) || code.braceStart(line) != depth || code.parenStart(line) != paren) {
                statementOpen = false; // inside a method, an initializer or a wrapped list: back at a new member
                continue;
            }
            String text = code.code(line).strip();
            if (text.isEmpty()) {
                continue;
            }
            String bare = ANNOTATION.matcher(text).replaceAll("").strip();
            if (bare.isEmpty()) {
                continue; // an annotation on a line of its own: the member is on the next line
            }
            if (!statementOpen && !taken.contains(line)) {
                Matcher m = FIELD.matcher(bare);
                if (m.find() && !NOT_TYPES.contains(m.group(1)) && !NOT_TYPES.contains(m.group(2))) {
                    type.children().add(new StructureNode(null, m.group(2), "field", line));
                    added = true;
                }
            }
            char last = bare.charAt(bare.length() - 1);
            statementOpen = last != ';' && last != '{' && last != '}';
        }
        if (added) {
            type.children().sort(Comparator.comparingInt(StructureNode::line));
        }
    }
}
