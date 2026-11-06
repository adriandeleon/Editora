package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Go to Super Method ({@code lsp.gotoSuperMethod}) for {@link LspCoordinator}: finds the member at the caret from
 * the server's member spans and asks what it overrides — jdtls's {@code findLinks} first, then the type hierarchy
 * for the branches it misses ({@link SuperMethodWalk}, {@link InstantiatedType}) — and opens or lists the answer.
 * The request is {@link LspNavigation}'s, so the same staleness guard drops an answer for a tab that moved on.
 * FX thread.
 */
final class LspSuperMethods {

    /** The source text of an open buffer at {@code path}, or null. */
    @FunctionalInterface
    interface Host {
        @Nullable
        String openText(Path path);
    }

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final LspCoordinator.Ops ops;
    private final LspNavigation nav;
    private final Host lsp;

    LspSuperMethods(CoordinatorHost host, LspManager lspManager, LspCoordinator.Ops ops, LspNavigation nav, Host lsp) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.nav = nav;
        this.lsp = lsp;
    }

    /**
     * Go to Super Method ({@code lsp.gotoSuperMethod}, IntelliJ's Ctrl+U): what the member at the caret overrides, as
     * IntelliJ finds it. The member is the innermost method, constructor or type whose whole range (its annotations and
     * Javadoc too) holds the caret, an anonymous class's method included, also in a field or an enum constant; in a
     * lambda, in a method or a field, it is the lambda's functional interface method; a constructor goes to the super
     * constructor it calls, through {@code this(...)} too; a type to its supertypes. A static or private method has none.
     * jdtls's {@code findLinks} answers first; the type hierarchy adds the branches it misses (eighth and ninth reviews).
     */
    void gotoSuperMethod() {
        LspNavigation.NavOrigin origin = nav.navOrigin();
        if (origin == null) {
            return;
        }
        Path path = origin.anchor();
        if (!lspManager.supportsSuperMethod(path)) {
            host.setStatus(tr("status.lsp.superMethodUnsupported"));
            return;
        }
        EditorBuffer b = origin.buffer();
        CodeArea area = b.getFocusedArea();
        int line = area.getCurrentParagraph();
        int column = area.getCaretColumn();
        int caret = area.getCaretPosition();
        String text = area.getText();
        long version = b.docVersion();
        long generation = nav.nextRequestGeneration();
        nav.syncForRequest(origin);
        SuperAsk ask = new SuperAsk(
                path,
                origin.libraryUri(),
                () -> nav.navigationRequestCurrent(b, origin.ownPath(), version, generation));
        lspManager.memberSpans(path, null, origin.libraryUri(), members -> {
            if (ask.current().getAsBoolean()) {
                superAt(ask, text, line, column, caret, members);
            }
        });
    }

    private void superAt(
            SuperAsk ask, String text, int line, int column, int caret, List<com.editora.lsp.MemberSpan> members) {
        List<com.editora.lsp.MemberSpan> chain = com.editora.lsp.JavaMembers.enclosing(members, line, column, text);
        if (caret > 0 && caret < text.length() && text.startsWith("->", caret - 1)) {
            caret++; // the caret inside a lambda's "->": the lambda's (eleventh review EM7)
        }
        int at = chain.size() - 1;
        while (at >= 0 && !chain.get(at).isType() && !chain.get(at).isCallable()) {
            at--;
        }
        if (at < 0) {
            superMethods(ask, line, column, List.of());
            return;
        }
        com.editora.lsp.MemberSpan member = chain.get(at);
        com.editora.lsp.MemberSpan inner = chain.get(chain.size() - 1); // a field or constant around the caret
        com.editora.lsp.MemberSpan scope = member.isCallable() ? member : inner != member ? inner : null;
        int base = scope != null
                ? offsetOf(text, scope.startLine())
                : member.isType() ? offsetOf(text, member.nameLine()) : -1;
        int reference = base < 0 ? -1 : com.editora.lsp.JavaMembers.methodReference(text, caret);
        if (reference >= base && base >= 0) { // String::length: its functional interface's method (tenth review BO3)
            definitionAsSuper(ask, text, reference + 1);
            return;
        }
        int parameters = base < 0 ? -1 : com.editora.lsp.JavaMembers.lambdaParameterArrow(text, base, caret);
        if (parameters >= 0) { // in a lambda's parameters, the lambda's (BO3)
            definitionAsSuper(ask, text, parameters);
            return;
        }
        int arrow = scope == null
                ? -1
                : com.editora.lsp.JavaMembers.lambdaArrow(text, offsetOf(text, scope.startLine()), caret);
        if (arrow >= 0) { // a lambda's super method is its functional interface's method, as in IntelliJ
            definitionAsSuper(ask, text, arrow);
            return;
        }
        int header = com.editora.lsp.JavaMembers.anonymousHeaderAt(text, caret);
        if (header >= 0) { // an anonymous class's header anywhere: outlined, a call's argument, any segment of its name
            anonymousSuper(ask, text, new int[] {header, -1});
            return;
        }
        if (member.isType() && scope == null) {
            // in no member of the type: a static or instance initializer, which jdtls leaves out of the outline; an
            // anonymous class's method or a lambda there is found in the text (ninth review BQ2)
            int[] anonymous =
                    com.editora.lsp.JavaMembers.anonymousMethod(text, offsetOf(text, member.startLine()), caret);
            int lambda = com.editora.lsp.JavaMembers.lambdaArrow(text, offsetOf(text, member.nameLine()), caret);
            if (anonymous != null
                    && (anonymous[1] >= 0 || !member.isAnonymous()) // an outlined one: its supertypes, below
                    && Math.max(anonymous[0], anonymous[1]) > lambda) {
                anonymousSuper(ask, text, anonymous);
                return;
            }
            if (lambda >= 0 && caret > offsetOf(text, member.nameLine() + 1)) {
                definitionAsSuper(ask, text, lambda);
                return;
            }
        }
        if (member.isType() && scope != null && !scope.isCallable()) {
            // a field's anonymous class no outline holds, as in a class file (tenth review BP2)
            int[] anonymous =
                    com.editora.lsp.JavaMembers.anonymousMethod(text, offsetOf(text, scope.startLine()), caret);
            if (anonymous != null) {
                anonymousSuper(ask, text, anonymous);
                return;
            }
        }
        if (member.isType() && member.name().equals("{...}") && at > 0) {
            // an enum constant's own body: its class is the enum's, as IntelliJ goes there
            com.editora.lsp.MemberSpan constant = chain.get(at - 1);
            for (int i = at - 1; i >= 0; i--) {
                if (chain.get(i).isType()) {
                    nav.openTarget(
                            ask.path(),
                            new LspManager.Target(
                                    ask.libraryUri() == null ? ask.path() : null,
                                    chain.get(i).nameLine(),
                                    chain.get(i).nameColumn(),
                                    ask.libraryUri()));
                    return;
                }
            }
            superTypes(ask, text, constant);
            return;
        }
        if (member.isType()) {
            superTypes(ask, text, member);
            return;
        }
        com.editora.lsp.MemberSpan type = null;
        for (int i = at - 1; i >= 0 && type == null; i--) {
            type = chain.get(i).isType() ? chain.get(i) : null;
        }
        int[] anonymous = ask.libraryUri() == null
                ? null
                : com.editora.lsp.JavaMembers.anonymousMethod(text, offsetOf(text, member.startLine()), caret);
        if (anonymous != null) {
            // a class file's outline has no anonymous classes: the method is found in the text (ninth review BP2)
            anonymousSuper(ask, text, anonymous);
            return;
        }
        if (member.kind().equals("constructor")) {
            superConstructor(ask, text, member, type, chain, new java.util.HashSet<>());
            return;
        }
        java.util.Set<String> modifiers = com.editora.lsp.JavaMembers.modifiers(
                text, offsetOf(text, member.startLine()), offsetOf(text, member.nameLine()) + member.nameColumn());
        if (modifiers.contains("static") || modifiers.contains("private")) {
            host.setStatus(tr("status.lsp.noSuperMethod")); // neither overrides anything (ninth review DP1)
            return;
        }
        superMethodsOf(
                ask,
                text,
                member,
                type,
                com.editora.lsp.JavaMembers.annotated(
                        text,
                        offsetOf(text, member.startLine()),
                        offsetOf(text, member.nameLine()) + member.nameColumn(),
                        "Override"));
    }

    /**
     * A method's super methods: jdtls's {@code findLinks} first, which is exact for a class's method; the type hierarchy
     * then finds the branches it misses (an interface's methods, a second interface, an anonymous class written with
     * {@code <>} or extending a project class). A class's method findLinks finds nothing for overrides nothing, unless
     * it is marked {@code @Override}: findLinks misses an inner class of a generic class.
     */
    private void superMethodsOf(
            SuperAsk ask,
            String text,
            com.editora.lsp.MemberSpan method,
            com.editora.lsp.@Nullable MemberSpan type,
            boolean override) {
        javafx.animation.PauseTransition slow = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
        slow.setOnFinished(e -> {
            if (ask.current().getAsBoolean()) {
                host.setStatus(tr("status.lsp.superMethodSearching")); // a deep hierarchy takes a moment (BP5)
            }
        });
        slow.play();
        lspManager.superMethodAt(
                ask.path(),
                ask.libraryUri(),
                method.nameLine(),
                method.nameColumn(),
                linked -> {
                    if (!ask.current().getAsBoolean()) {
                        slow.stop();
                        return;
                    }
                    // findLinks misses an inner class of a generic class: a method marked @Override has one, so the
                    // hierarchy is walked before "none" is said (tenth review EO1)
                    if (type == null
                            || !(type.kind().equals("interface")
                                    || type.isAnonymous()
                                    || !linked.isEmpty()
                                    || override)) {
                        slow.stop();
                        openOneOrPick(ask, labelled(linked), "picker.superMethod.title", "status.lsp.noSuperMethod");
                        return;
                    }
                    new SuperMethodWalk(lspManager, ask, text, method, lsp::openText, found -> {
                                slow.stop();
                                openOneOrPick(
                                        ask,
                                        found.isEmpty() ? labelled(linked) : found,
                                        "picker.superMethod.title",
                                        "status.lsp.noSuperMethod");
                            })
                            .start(type);
                },
                why -> {
                    slow.stop();
                    navigationFailed(ask, why);
                });
    }

    /** One Go to Super Method request: the anchor file, the library tab's URI (or null), and whether it still counts. */
    record SuperAsk(Path path, @Nullable String libraryUri, java.util.function.BooleanSupplier current) {}

    /** A Go to Super Method target and its picker row: the type, or the type and method ({@code Shape.area}). */
    record SuperTarget(LspManager.Target target, String label) {}

    /** The offset of {@code line}'s start in {@code text}. */
    static int offsetOf(String text, int line) {
        int at = 0;
        for (int i = 0; i < line && at >= 0; i++) {
            at = text.indexOf('\n', at);
            at = at < 0 ? -1 : at + 1;
        }
        return Math.max(0, at);
    }

    /** The 0-based line and column of {@code offset} in {@code text}. */
    static int[] lineColumn(String text, int offset) {
        int line = 0;
        int start = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                start = i + 1;
            }
        }
        return new int[] {line, offset - start};
    }

    /**
     * The super method of an anonymous class's method no outline holds (in an initializer, or a class file), found in
     * {@code text} ({@code anonymous}: the offsets of the instantiated type's name and the method's): jdtls's
     * {@code findLinks}, else the walk from the type it instantiates, as for one the outline holds (tenth review BO2).
     */
    private void anonymousSuper(SuperAsk ask, String text, int[] anonymous) {
        if (anonymous[1] < 0) { // its header or a field: the anonymous class, whose super is the type it instantiates
            int line = lineColumn(text, anonymous[0])[0]; // (twelfth review EL2, thirteenth review BK1, BK3)
            InstantiatedType.resolve(lspManager, ask, text, anonymous[0], line, line, root -> openSuperType(ask, root));
            return;
        }
        SuperMethodWalk.anonymousMethod(
                lspManager,
                ask,
                text,
                anonymous,
                lsp::openText,
                found -> openOneOrPick(ask, found, "picker.superMethod.title", "status.lsp.noSuperMethod"),
                why -> navigationFailed(ask, why));
    }

    /** jdtls's {@code java/findLinks} at a method's name: its one answer, else {@code otherwise}. */
    private void superMethods(SuperAsk ask, int line, int column, List<SuperTarget> otherwise) {
        lspManager.superMethodAt(
                ask.path(),
                ask.libraryUri(),
                line,
                column,
                targets -> {
                    if (ask.current().getAsBoolean()) {
                        openOneOrPick(
                                ask,
                                targets.isEmpty() ? otherwise : labelled(targets),
                                "picker.superMethod.title",
                                "status.lsp.noSuperMethod");
                    }
                },
                why -> navigationFailed(ask, why));
    }

    private void navigationFailed(SuperAsk ask, String why) {
        if (ask.current().getAsBoolean()) {
            host.setError(
                    "timeout".equals(why)
                            ? tr("status.lsp.navigationTimedOut")
                            : tr("status.lsp.navigationFailed", why));
        }
    }

    static List<SuperTarget> labelled(List<LspManager.Target> targets) {
        return targets.stream()
                .map(t -> new SuperTarget(t, ImplementationTargets.implementationLabel(t)))
                .toList();
    }

    /** Go to Definition at {@code offset} (a lambda's arrow, a {@code super(...)} call), opened as the super method. */
    private void definitionAsSuper(SuperAsk ask, String text, int offset) {
        int[] at = lineColumn(text, offset);
        lspManager.definitionAt(ask.path(), ask.libraryUri(), at[0], at[1], targets -> {
            if (ask.current().getAsBoolean()) {
                openOneOrPick(ask, labelled(targets), "picker.superMethod.title", "status.lsp.noSuperMethod");
            }
        });
    }

    /**
     * A constructor's super constructor: the one its {@code super(...)} calls; through a {@code this(...)} call, the one
     * the chained constructor reaches (ninth review BP3); else the superclass's no-argument constructor, else the
     * superclass itself, as IntelliJ does.
     */
    private void superConstructor(
            SuperAsk ask,
            String text,
            com.editora.lsp.MemberSpan ctor,
            com.editora.lsp.@Nullable MemberSpan type,
            List<com.editora.lsp.MemberSpan> chain,
            java.util.Set<Integer> seen) {
        int from = offsetOf(text, ctor.startLine());
        int to = offsetOf(text, ctor.endLine() + 1);
        int call = com.editora.lsp.JavaMembers.superCall(text, from, to);
        if (call >= 0) {
            definitionAsSuper(ask, text, call);
            return;
        }
        int chained = com.editora.lsp.JavaMembers.thisCall(text, from, to);
        if (chained >= 0 && type != null && seen.add(ctor.nameLine())) {
            int[] at = lineColumn(text, chained);
            lspManager.definitionAt(ask.path(), ask.libraryUri(), at[0], at[1], targets -> {
                if (!ask.current().getAsBoolean()) {
                    return;
                }
                com.editora.lsp.MemberSpan next = targets.isEmpty()
                        ? null
                        : type.children().stream()
                                .filter(m -> m.kind().equals("constructor")
                                        && m.nameLine() == targets.get(0).line())
                                .findFirst()
                                .orElse(null);
                if (next == null) {
                    implicitSuperConstructor(ask, type);
                } else {
                    superConstructor(ask, text, next, type, chain, seen);
                }
            });
            return;
        }
        if (type == null) {
            host.setStatus(tr("status.lsp.noSuperMethod"));
            return;
        }
        implicitSuperConstructor(ask, type);
    }

    /** The superclass's no-argument constructor, else the superclass itself (none for {@code Object}). */
    private void implicitSuperConstructor(SuperAsk ask, com.editora.lsp.MemberSpan type) {
        SuperMethodWalk.implicitSuperConstructor(lspManager, ask, type, t -> {
            if (t == null) {
                host.setStatus(tr("status.lsp.noSuperMethod"));
            } else {
                nav.openTarget(ask.path(), t);
            }
        });
    }

    /**
     * A type's supertypes, the superclass first and each named by its type (eighth review BQ6). An anonymous class
     * goes to the type it instantiates.
     */
    private void superTypes(SuperAsk ask, String text, com.editora.lsp.MemberSpan type) {
        if (type.isAnonymous() && !type.name().equals("{...}")) { // the type it instantiates, checked (thirteenth BK1)
            int name = offsetOf(text, type.nameLine()) + type.nameColumn();
            InstantiatedType.resolve(
                    lspManager, ask, text, name, type.startLine(), type.endLine(), root -> openSuperType(ask, root));
            return;
        }
        lspManager.prepareTypeHierarchyAt(ask.path(), ask.libraryUri(), type.nameLine(), type.nameColumn(), roots -> {
            if (!ask.current().getAsBoolean()) {
                return;
            }
            if (roots.isEmpty()) {
                host.setStatus(tr("status.lsp.noSuperType"));
                return;
            }
            if (type.isAnonymous()) { // an enum constant's body: jdtls answers with its enum
                openOneOrPick(
                        ask, typeTargets(roots.subList(0, 1)), "picker.superTypes.title", "status.lsp.noSuperType");
                return;
            }
            lspManager.typeHierarchyChildren(ask.path(), roots.get(0).raw(), true, supers -> {
                if (ask.current().getAsBoolean()) {
                    openOneOrPick(
                            ask,
                            typeTargets(SuperMethodWalk.superclassFirst(SuperMethodWalk.withoutObject(supers))),
                            "picker.superTypes.title",
                            "status.lsp.noSuperType");
                }
            });
        });
    }

    /** Opens {@code root}, an anonymous class's instantiated type, or says it has none. */
    private void openSuperType(SuperAsk ask, LspManager.@Nullable HierarchyNode root) {
        String label = root == null ? "" : InstantiatedType.simple(root.name());
        InstantiatedType.typeTarget(lspManager, ask, root, t -> {
            if (ask.current().getAsBoolean()) {
                openOneOrPick(
                        ask,
                        t == null ? List.of() : List.of(new SuperTarget(t, label)),
                        "picker.superTypes.title",
                        "status.lsp.noSuperType");
            }
        });
    }

    private static List<SuperTarget> typeTargets(List<LspManager.HierarchyNode> nodes) {
        return nodes.stream()
                .map(n -> new SuperTarget(new LspManager.Target(n.file(), n.line(), n.col(), n.libraryUri()), n.name()))
                .toList();
    }

    /** Opens the one target, or lets the user pick among several (IntelliJ's chooser); says {@code emptyKey} if none. */
    private void openOneOrPick(SuperAsk ask, List<SuperTarget> targets, String titleKey, String emptyKey) {
        if (targets.isEmpty()) {
            host.setStatus(tr(emptyKey));
            return;
        }
        host.setStatus(""); // "Looking for super methods…" is over, picker or not (tenth review DO3)
        if (targets.size() == 1) {
            nav.openTarget(ask.path(), targets.get(0).target());
            return;
        }
        java.nio.file.Path base = ops.lspProjectRoot();
        LspCoordinator.pickOne(
                host,
                tr(titleKey),
                tr("picker.superMethod.prompt"),
                targets,
                SuperTarget::label,
                t -> ImplementationTargets.implementationDetail(t.target(), base),
                chosen -> nav.openTarget(ask.path(), chosen.target()));
    }
}
