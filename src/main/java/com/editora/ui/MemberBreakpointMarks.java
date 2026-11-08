package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.editora.config.MemberBreakpoint;
import com.editora.editor.EditorBuffer;
import com.editora.editor.debug.BreakpointManager;
import com.editora.lsp.LspManager;
import com.editora.lsp.SymbolNode;

/**
 * Marks each method breakpoint and field watchpoint in the gutter of the open Java file that declares its member, on
 * the member's declaration line — every overload of a method, since a method breakpoint stops in each — as IntelliJ
 * and Eclipse do; before, one showed only in Debug: Breakpoints. Where a member is declared is read from the Java
 * language server's outline: when the breakpoints change, when a file opens, when its server is ready, and on each
 * typing pause.
 */
final class MemberBreakpointMarks {

    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;
    private final LspManager lspManager;

    MemberBreakpointMarks(CoordinatorHost host, DebugCoordinator.Ops ops, LspManager lspManager) {
        this.host = host;
        this.ops = ops;
        this.lspManager = lspManager;
    }

    /** Places the marks again in every open file (the breakpoints changed, or a server became ready). */
    void refreshAll() {
        host.forEachBuffer(this::refresh);
    }

    /** Places {@code b}'s marks from its outline; a file of no Java, or with no breakpoint to mark, has none. */
    void refresh(EditorBuffer b) {
        Path file = b.getPath();
        BreakpointManager mgr = b.getBreakpointManager();
        List<MemberBreakpoint> all = ops.memberBreakpoints();
        if (file == null || !MemberBreakpointCommands.isJava(file) || all.isEmpty()) {
            mgr.setMemberMarks(Map.of());
            return;
        }
        mgr.setMemberMarks(current(mgr.memberMarks(), all)); // at once: what changed shows before the outline answers
        if (!lspManager.supportsDocumentSymbols(file)) {
            return; // the server is not ready: its readiness asks again
        }
        long version = b.docVersion();
        String pkg = MemberBreakpointCommands.packageOf(b.text());
        lspManager.documentSymbols(file, symbols -> {
            if (b.docVersion() != version) {
                return; // edited since: the next typing pause asks again
            }
            int first = mgr.regionFirstLine();
            int lines = b.getArea().getParagraphs().size();
            Map<Integer, BreakpointManager.MemberMark> marks = new HashMap<>();
            declarationLines(pkg, symbols, ops.memberBreakpoints()).forEach((line, members) -> {
                int view = line - first;
                if (view >= 0 && view < lines) { // outside a narrowed region: not shown
                    marks.put(view, new BreakpointManager.MemberMark(members, tooltip(members)));
                }
            });
            mgr.setMemberMarks(marks);
        });
    }

    /**
     * {@code marks} as {@code all} has them now: each mark's breakpoints replaced by their current state (enabled, a
     * condition), those removed dropped, and a mark left with none gone. Pure.
     */
    static Map<Integer, BreakpointManager.MemberMark> current(
            Map<Integer, BreakpointManager.MemberMark> marks, List<MemberBreakpoint> all) {
        Map<Integer, BreakpointManager.MemberMark> out = new HashMap<>();
        marks.forEach((line, mark) -> {
            List<MemberBreakpoint> now = new ArrayList<>();
            for (MemberBreakpoint m : mark.members()) {
                all.stream().filter(m::sameMember).findFirst().ifPresent(now::add);
            }
            if (!now.isEmpty()) {
                out.put(line, new BreakpointManager.MemberMark(now, tooltip(now)));
            }
        });
        return out;
    }

    /** A mark's hover text: each breakpoint's name, then what the Breakpoints list says of it. Pure. */
    static String tooltip(List<MemberBreakpoint> members) {
        List<String> out = new ArrayList<>();
        for (MemberBreakpoint m : members) {
            out.add(m.shortName() + "\n" + BreakpointsPicker.memberDetail(m));
        }
        return String.join("\n", out);
    }

    /**
     * The whole-file lines (0-based) declaring the members of {@code all}, read from a Java outline: each method's,
     * constructor's ({@code <init>}) or field's name line in the class of its binary name ({@code pkg.Outer$Inner}).
     * A line gets every breakpoint whose member is declared on it. Pure.
     */
    static Map<Integer, List<MemberBreakpoint>> declarationLines(
            String pkg, List<SymbolNode> symbols, List<MemberBreakpoint> all) {
        Map<String, List<MemberBreakpoint>> byMember = new LinkedHashMap<>();
        for (MemberBreakpoint m : all) {
            byMember.computeIfAbsent(key(m.kind(), m.className(), m.member()), k -> new ArrayList<>())
                    .add(m);
        }
        Map<Integer, List<MemberBreakpoint>> out = new TreeMap<>();
        if (!byMember.isEmpty()) {
            walk(pkg == null || pkg.isEmpty() ? "" : pkg + ".", symbols, false, byMember, out);
        }
        return out;
    }

    private static void walk(
            String prefix,
            List<SymbolNode> nodes,
            boolean inType,
            Map<String, List<MemberBreakpoint>> byMember,
            Map<Integer, List<MemberBreakpoint>> out) {
        for (SymbolNode n : nodes) {
            String kind = n.kind();
            if (typeKind(kind)) {
                walk(prefix + MemberBreakpointCommands.memberName(n.name()) + "$", n.children(), true, byMember, out);
                continue;
            }
            if (!inType) {
                continue;
            }
            String owner = prefix.substring(0, prefix.length() - 1);
            MemberBreakpoint.Kind memberKind = methodKind(kind)
                    ? MemberBreakpoint.Kind.METHOD
                    : fieldKind(kind) ? MemberBreakpoint.Kind.FIELD : null;
            if (memberKind == null) {
                continue;
            }
            String name = "constructor".equals(kind) ? "<init>" : MemberBreakpointCommands.memberName(n.name());
            List<MemberBreakpoint> found = byMember.get(key(memberKind, owner, name));
            if (found != null) {
                out.computeIfAbsent(n.line(), l -> new ArrayList<>()).addAll(found);
            }
        }
    }

    private static String key(MemberBreakpoint.Kind kind, String className, String member) {
        return kind + " " + className + "#" + member;
    }

    private static boolean typeKind(String kind) {
        return switch (kind) {
            case "class", "interface", "enum", "struct" -> true;
            default -> false;
        };
    }

    private static boolean methodKind(String kind) {
        return "method".equals(kind) || "constructor".equals(kind) || "function".equals(kind);
    }

    private static boolean fieldKind(String kind) {
        return "field".equals(kind) || "constant".equals(kind) || "property".equals(kind);
    }
}
