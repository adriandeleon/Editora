package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.config.MemberBreakpoint;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.SymbolNode;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Method breakpoints and field watchpoints: a breakpoint on a member of a Java class rather than on a line, which
 * stops when the method is entered or the field is assigned (IntelliJ, Eclipse and NetBeans have both; java-debug
 * takes them as DAP function and data breakpoints). They are kept per project with the line breakpoints, listed in
 * Debug: Breakpoints, and sent to every Java session, at its start and whenever they change.
 *
 * <p>Set from the caret (the enclosing method, or the field declared on the caret line, read from the Java
 * language server's outline), by name ({@code pkg.Class#member}), or while paused from a field in Variables.
 */
final class MemberBreakpointCommands {

    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;
    /** The focused debug session's manager. */
    private final java.util.function.Supplier<DapManager> dapManager;

    /** Every debug session's manager. */
    private final java.util.function.Supplier<List<DapManager>> allManagers;

    private final LspManager lspManager;
    private final BooleanSupplier muted;

    MemberBreakpointCommands(
            CoordinatorHost host,
            DebugCoordinator.Ops ops,
            java.util.function.Supplier<DapManager> dapManager,
            java.util.function.Supplier<List<DapManager>> allManagers,
            LspManager lspManager,
            BooleanSupplier muted) {
        this.host = host;
        this.ops = ops;
        this.dapManager = dapManager;
        this.allManagers = allManagers;
        this.lspManager = lspManager;
        this.muted = muted;
    }

    /** This project's method breakpoints and field watchpoints, in the order they were added. */
    List<MemberBreakpoint> all() {
        return List.copyOf(ops.memberBreakpoints());
    }

    /** Tells the debugger the ones in force: the enabled ones, none while breakpoints are muted. */
    void push() {
        List<com.editora.dap.DapModels.MemberBreakpoint> now =
                muted.getAsBoolean() ? List.of() : adapterBreakpoints(all());
        for (DapManager each : allManagers.get()) { // every session of the window is told
            each.setMemberBreakpoints(now);
        }
    }

    /** The enabled ones as the adapter takes them. Pure. */
    static List<DapModels.MemberBreakpoint> adapterBreakpoints(List<MemberBreakpoint> all) {
        List<DapModels.MemberBreakpoint> out = new ArrayList<>();
        for (MemberBreakpoint m : all) {
            if (m.enabled() && !m.className().isEmpty() && !m.member().isEmpty()) {
                out.add(new DapModels.MemberBreakpoint(
                        m.kind() == MemberBreakpoint.Kind.FIELD,
                        m.qualifiedName(),
                        m.access(),
                        m.condition(),
                        m.hitCondition()));
            }
        }
        return out;
    }

    private Runnable onChanged = () -> {};

    /** Runs after every change to the list (the gutter's marks follow it). */
    void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged == null ? () -> {} : onChanged;
    }

    private void save(List<MemberBreakpoint> list) {
        ops.persistMemberBreakpoints(list);
        push();
        onChanged.run();
    }

    /** Adds {@code m}, or removes the breakpoint already on its member. Reports which on the status line. */
    void toggle(MemberBreakpoint m) {
        List<MemberBreakpoint> list = new ArrayList<>(ops.memberBreakpoints());
        boolean removed = list.removeIf(m::sameMember);
        if (!removed) {
            list.add(m);
        }
        save(list);
        boolean method = m.kind() == MemberBreakpoint.Kind.METHOD;
        String key = method
                ? (removed ? "status.debug.methodBreakpointRemoved" : "status.debug.methodBreakpointAdded")
                : (removed ? "status.debug.fieldWatchpointRemoved" : "status.debug.fieldWatchpointAdded");
        host.setStatus(tr(key, m.shortName()));
    }

    /** Adds {@code m} unless its member already has one (then it is left as it is). */
    void add(MemberBreakpoint m) {
        List<MemberBreakpoint> list = new ArrayList<>(ops.memberBreakpoints());
        if (list.stream().noneMatch(m::sameMember)) {
            list.add(m);
            save(list);
        }
        host.setStatus(tr(
                m.kind() == MemberBreakpoint.Kind.METHOD
                        ? "status.debug.methodBreakpointAdded"
                        : "status.debug.fieldWatchpointAdded",
                m.shortName()));
    }

    /** Replaces the breakpoint on {@code next}'s member with {@code next} (enabled, condition…). */
    void replace(MemberBreakpoint next) {
        List<MemberBreakpoint> list = new ArrayList<>(ops.memberBreakpoints());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).sameMember(next)) {
                list.set(i, next);
                save(list);
                return;
            }
        }
    }

    void remove(MemberBreakpoint m) {
        List<MemberBreakpoint> list = new ArrayList<>(ops.memberBreakpoints());
        if (list.removeIf(m::sameMember)) {
            save(list);
        }
    }

    /**
     * Debug: Toggle Method Breakpoint — on the method the caret is in. Outside one (or with no Java language
     * server to say), it asks for the method by name.
     */
    void toggleMethodAtCaret() {
        atCaret(MemberBreakpoint.Kind.METHOD);
    }

    /** Debug: Toggle Field Watchpoint — on the field declared on the caret line, else asks for it by name. */
    void toggleFieldAtCaret() {
        atCaret(MemberBreakpoint.Kind.FIELD);
    }

    private void atCaret(MemberBreakpoint.Kind kind) {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null || !isJava(file) || !lspManager.supportsDocumentSymbols(file)) {
            promptByName(kind, "");
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
        String pkg = packageOf(b.text());
        lspManager.documentSymbols(file, symbols -> {
            Member found = memberAt(pkg, symbols, line, kind);
            if (found == null) {
                promptByName(kind, defaultClass(pkg, symbols, line));
                return;
            }
            toggle(
                    kind == MemberBreakpoint.Kind.METHOD
                            ? MemberBreakpoint.method(found.className(), found.member())
                            : MemberBreakpoint.field(found.className(), found.member(), MemberBreakpoint.WRITE));
        });
    }

    /** Asks for {@code pkg.Class#member}, starting from {@code initial} (the caret's class, when known). */
    void promptByName(MemberBreakpoint.Kind kind, String initial) {
        boolean method = kind == MemberBreakpoint.Kind.METHOD;
        host.promptText(
                tr(method ? "dialog.debug.methodBreakpointTitle" : "dialog.debug.fieldWatchpointTitle"),
                tr(method ? "dialog.debug.methodBreakpointLabel" : "dialog.debug.fieldWatchpointLabel"),
                initial,
                text -> {
                    if (text == null || text.isBlank()) {
                        return;
                    }
                    MemberBreakpoint.Named named = MemberBreakpoint.parse(text);
                    if (named == null) {
                        host.setStatus(tr("status.debug.memberNameInvalid", text.strip()));
                        return;
                    }
                    toggle(
                            method
                                    ? MemberBreakpoint.method(named.className(), named.member())
                                    : MemberBreakpoint.field(
                                            named.className(), named.member(), MemberBreakpoint.WRITE));
                });
    }

    /**
     * Variables → Break When Value Changes on {@code name}, a variable of container {@code containerRef}: the
     * adapter says which field it is ({@code dataBreakpointInfo}); a local variable cannot be watched.
     */
    void watchVariable(int containerRef, String name) {
        dapManager.get().dataBreakpointInfo(containerRef, name, target -> {
            MemberBreakpoint.Named named = target.isEmpty()
                    ? null
                    : MemberBreakpoint.parse(target.get().dataId());
            if (named == null) {
                host.setStatus(tr("status.debug.notAField", name));
                return;
            }
            add(MemberBreakpoint.field(named.className(), named.member(), MemberBreakpoint.WRITE));
        });
    }

    /** Whether the live session can watch a field (the Variables menu offers Break When Value Changes). */
    boolean canWatchFields() {
        return dapManager.get().supportsDataBreakpoints();
    }

    /**
     * Opens the member's declaration, found by the Java language server's workspace symbols (anchored at an open
     * Java file); reports it when nothing is found.
     */
    void jump(MemberBreakpoint m) {
        Path anchor = javaAnchor();
        if (anchor == null || !lspManager.supportsWorkspaceSymbols(anchor)) {
            host.setStatus(tr("status.debug.memberNotFound", m.shortName()));
            return;
        }
        lspManager.workspaceSymbols(anchor, m.member(), matches -> {
            LspManager.WorkspaceSymbolMatch best = bestMatch(m, matches);
            Path file = best == null ? null : best.file();
            if (best == null || file == null) {
                host.setStatus(tr("status.debug.memberNotFound", m.shortName()));
                return;
            }
            ops.openPath(file);
            EditorBuffer b = ops.bufferForPath(file);
            if (b != null) {
                ops.afterBufferLoad(
                        b,
                        () -> b.jumpToLine(
                                best.line() - b.getBreakpointManager().regionFirstLine()));
            }
        });
    }

    private @Nullable Path javaAnchor() {
        EditorBuffer active = host.activeBuffer();
        if (active != null && active.getPath() != null && isJava(active.getPath())) {
            return active.getPath();
        }
        Path[] found = new Path[1];
        host.forEachBuffer(b -> {
            if (found[0] == null && b.getPath() != null && isJava(b.getPath())) {
                found[0] = b.getPath();
            }
        });
        return found[0];
    }

    /**
     * The workspace symbol that is {@code m}'s member: named it (a method's parameters aside) and in its class —
     * jdtls names the container by the class's simple name, or its qualified one. Pure.
     */
    static LspManager.@Nullable WorkspaceSymbolMatch bestMatch(
            MemberBreakpoint m, List<LspManager.WorkspaceSymbolMatch> matches) {
        String simple = m.className().substring(m.className().lastIndexOf('.') + 1);
        String innermost = simple.substring(simple.lastIndexOf('$') + 1);
        for (LspManager.WorkspaceSymbolMatch s : matches) {
            if (s.file() == null || !memberName(s.name()).equals(m.member())) {
                continue;
            }
            String container = s.container();
            if (container.equals(m.className())
                    || container.equals(innermost)
                    || container.endsWith("." + innermost)
                    || container.equals(simple.replace('$', '.'))) {
                return s;
            }
        }
        return null;
    }

    static boolean isJava(Path file) {
        Path name = file.getFileName();
        return name != null && name.toString().endsWith(".java");
    }

    private static final Pattern PACKAGE = Pattern.compile(
            "^\\s*package\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.]*)\\s*;", Pattern.MULTILINE);

    /** The package a Java source declares, or {@code ""}. Pure. */
    static String packageOf(String source) {
        if (source == null) {
            return "";
        }
        // The declaration comes first: comments and annotations aside, nothing much precedes it.
        Matcher m = PACKAGE.matcher(source.length() > 20_000 ? source.substring(0, 20_000) : source);
        return m.find() ? m.group(1) : "";
    }

    /** A member found at the caret: its class's binary name and the member's name. */
    record Member(String className, String member) {}

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

    /** {@code total(int) : long} → {@code total}: jdtls puts the parameters (and sometimes the type) in the name. */
    static String memberName(String symbolName) {
        String n = symbolName == null ? "" : symbolName.strip();
        for (char stop : new char[] {'(', ' ', ':', '<'}) {
            int i = n.indexOf(stop);
            if (i > 0) {
                n = n.substring(0, i);
            }
        }
        return n;
    }

    /**
     * The method {@code line} (0-based, whole file) is in, or — {@code FIELD} — the field declared on it, with its
     * class's binary name ({@code pkg.Outer$Inner}), read from a Java outline. Null when there is none (the caret
     * outside every method, a line with no field). Pure.
     */
    static @Nullable Member memberAt(String pkg, List<SymbolNode> symbols, int line, MemberBreakpoint.Kind kind) {
        return memberAt(pkg.isEmpty() ? "" : pkg + ".", symbols, line, kind, false);
    }

    private static @Nullable Member memberAt(
            String prefix, List<SymbolNode> nodes, int line, MemberBreakpoint.Kind kind, boolean inType) {
        for (SymbolNode n : nodes) {
            if (line < n.line() || line > n.endLine()) {
                continue; // from the member's name to the end of its declaration
            }
            if (typeKind(n.kind())) {
                String className = prefix + memberName(n.name());
                Member inner = memberAt(className + "$", n.children(), line, kind, true);
                if (inner != null) {
                    return inner;
                }
                continue;
            }
            if (!inType) {
                continue;
            }
            String owner = prefix.substring(0, prefix.length() - 1);
            if (kind == MemberBreakpoint.Kind.METHOD && methodKind(n.kind())) {
                String name = "constructor".equals(n.kind()) ? "<init>" : memberName(n.name());
                return new Member(owner, name);
            }
            if (kind == MemberBreakpoint.Kind.FIELD && fieldKind(n.kind())) {
                return new Member(owner, memberName(n.name()));
            }
        }
        return null;
    }

    /** The binary name of the innermost class {@code line} is in, followed by {@code #}; {@code ""} for none. */
    static String defaultClass(String pkg, List<SymbolNode> symbols, int line) {
        String found = innermostClass(pkg.isEmpty() ? "" : pkg + ".", symbols, line);
        return found == null ? "" : found + "#";
    }

    private static @Nullable String innermostClass(String prefix, List<SymbolNode> nodes, int line) {
        for (SymbolNode n : nodes) {
            if (typeKind(n.kind()) && line >= n.line() && line <= n.endLine()) {
                String className = prefix + memberName(n.name());
                String inner = innermostClass(className + "$", n.children(), line);
                return inner != null ? inner : className;
            }
        }
        return null;
    }
}
