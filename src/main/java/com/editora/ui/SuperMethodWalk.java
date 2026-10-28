package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

/**
 * The methods a method overrides, found up its type's hierarchy: on each branch the nearest supertype that declares
 * an override-equivalent method (the same name and parameter types; a type variable matching the type the subtype
 * gives it, else any), the superclass's branch first. A hit in a type that another hit's type extends is dropped, as
 * IntelliJ merges them (ninth review EP3), and so is the method asked about. An anonymous class starts from the type
 * it instantiates. JDK outlines are cached for the session, and {@code Object} is read only for its own methods'
 * names (BP5). Go to Super Method's ({@link LspCoordinator}), apart for its size.
 */
final class SuperMethodWalk {
    private static final int MAX_DEPTH = 8;
    private static final java.util.Set<String> OBJECT_METHODS =
            java.util.Set.of("equals", "hashCode", "toString", "clone", "finalize");
    private final LspManager lspManager;
    private final LspCoordinator.SuperAsk ask;
    private final String text;
    private final com.editora.lsp.MemberSpan anchor;
    private final String method;
    private final java.util.function.Consumer<List<LspCoordinator.SuperTarget>> done;
    private final java.util.function.Function<Path, @Nullable String> openText;
    private final List<String> anchorTypeParameters;
    private final Map<Path, java.util.Optional<String>> texts = new java.util.HashMap<>();
    private final java.util.TreeMap<String, LspCoordinator.SuperTarget> found = new java.util.TreeMap<>();
    private final Map<String, String> foundIn = new java.util.HashMap<>();
    private final java.util.Set<String> seen = new java.util.HashSet<>();
    private final java.util.Set<String> covered = new java.util.HashSet<>();
    private int pending;
    private boolean fromAnonymous;
    private boolean objectAsked;

    /**
     * {@code anchor}: the method asked about, in {@code text} (the anchor file's); {@code openText}: an open file's
     * text, which jdtls's outline positions follow, or null for a closed one (eleventh review BM3).
     */
    SuperMethodWalk(
            LspManager lspManager,
            LspCoordinator.SuperAsk ask,
            String text,
            com.editora.lsp.MemberSpan anchor,
            java.util.function.Function<Path, @Nullable String> openText,
            java.util.function.Consumer<List<LspCoordinator.SuperTarget>> done) {
        this.lspManager = lspManager;
        this.ask = ask;
        this.text = text;
        this.anchor = anchor;
        this.method = anchor.name();
        this.openText = openText;
        this.done = done;
        this.anchorTypeParameters = com.editora.lsp.JavaMembers.methodTypeParameters(
                text,
                LspCoordinator.offsetOf(text, anchor.startLine()),
                LspCoordinator.offsetOf(text, anchor.nameLine()) + anchor.nameColumn());
    }

    /**
     * The constructor a constructor with no {@code super(...)} calls: {@code type}'s superclass's no-argument one, else
     * the superclass itself; {@code done} gets null for none ({@code Object}'s), and nothing once {@code ask} is stale.
     * Go to Super Method's ({@link LspCoordinator}), apart for its size.
     */
    static void implicitSuperConstructor(
            LspManager lspManager,
            LspCoordinator.SuperAsk ask,
            com.editora.lsp.MemberSpan type,
            java.util.function.Consumer<LspManager.@Nullable Target> done) {
        lspManager.prepareTypeHierarchyAt(ask.path(), ask.libraryUri(), type.nameLine(), type.nameColumn(), roots -> {
            if (!ask.current().getAsBoolean()) {
                return;
            }
            if (roots.isEmpty()) {
                done.accept(null);
                return;
            }
            lspManager.typeHierarchyChildren(ask.path(), roots.get(0).raw(), true, supers -> {
                LspManager.HierarchyNode superclass = supers.stream()
                        .filter(n -> n.kindName().equals("class") && !n.name().equals("Object"))
                        .findFirst()
                        .orElse(null);
                if (!ask.current().getAsBoolean()) {
                    return;
                }
                if (superclass == null) {
                    done.accept(null);
                    return;
                }
                lspManager.memberSpans(ask.path(), superclass.file(), superclass.libraryUri(), members -> {
                    if (!ask.current().getAsBoolean()) {
                        return;
                    }
                    com.editora.lsp.MemberSpan declared =
                            com.editora.lsp.JavaMembers.typeAt(members, superclass.line(), superclass.name());
                    com.editora.lsp.MemberSpan noArgs = declared == null
                            ? null
                            : declared.children().stream()
                                    .filter(m -> m.kind().equals("constructor")
                                            && com.editora.lsp.JavaMembers.parameters(m.name())
                                                    .isEmpty())
                                    .findFirst()
                                    .orElse(null);
                    int line = noArgs == null ? superclass.line() : noArgs.nameLine();
                    int col = noArgs == null ? superclass.col() : noArgs.nameColumn();
                    done.accept(new LspManager.Target(superclass.file(), line, col, superclass.libraryUri()));
                });
            });
        });
    }

    /**
     * The super methods of an anonymous class's method no outline holds (in an initializer, or a class file), found in
     * {@code text} ({@code anonymous}: the offsets of the instantiated type's name and the method's): jdtls's
     * {@code findLinks}, else the walk from the type it instantiates, as for one the outline holds (tenth review BO2).
     * {@code done} gets them, empty for none, and nothing once {@code ask} is stale; {@code failed} a request's error.
     */
    static void anonymousMethod(
            LspManager lspManager,
            LspCoordinator.SuperAsk ask,
            String text,
            int[] anonymous,
            java.util.function.Function<Path, @Nullable String> openText,
            java.util.function.Consumer<List<LspCoordinator.SuperTarget>> done,
            java.util.function.Consumer<String> failed) {
        int[] name = LspCoordinator.lineColumn(text, anonymous[1]);
        int[] type = LspCoordinator.lineColumn(text, anonymous[0]);
        lspManager.superMethodAt(
                ask.path(),
                ask.libraryUri(),
                name[0],
                name[1],
                linked -> {
                    if (!ask.current().getAsBoolean()) {
                        return;
                    }
                    if (!linked.isEmpty()) {
                        done.accept(LspCoordinator.labelled(linked));
                        return;
                    }
                    var method = new com.editora.lsp.MemberSpan(
                            com.editora.lsp.JavaMembers.signatureAt(text, anonymous[1]),
                            "method",
                            name[0],
                            name[0],
                            name[0],
                            name[1],
                            List.of());
                    var anonymousClass = new com.editora.lsp.MemberSpan(
                            "new {...}", "class", type[0], name[0], type[0], type[1], List.of(method));
                    new SuperMethodWalk(lspManager, ask, text, method, openText, done).start(anonymousClass);
                },
                failed);
    }

    /** {@code supers} with the superclass first, as IntelliJ lists them; jdtls gives the interfaces first. */
    static List<LspManager.HierarchyNode> superclassFirst(List<LspManager.HierarchyNode> supers) {
        List<LspManager.HierarchyNode> out = new java.util.ArrayList<>(supers);
        out.sort(java.util.Comparator.comparingInt(n -> n.kindName().equals("interface") ? 1 : 0));
        return out;
    }

    /** {@code supers} without {@code java.lang.Object}, which IntelliJ never offers (twelfth review EL1). */
    static List<LspManager.HierarchyNode> withoutObject(List<LspManager.HierarchyNode> supers) {
        return supers.stream()
                .filter(n -> !(n.name().equals("Object") && "java.lang".equals(n.detail())))
                .toList();
    }

    void start(com.editora.lsp.MemberSpan type) {
        pending++;
        int name = LspCoordinator.offsetOf(text, type.nameLine()) + type.nameColumn();
        // an anonymous class from the type it instantiates, checked against its written name (thirteenth review BK1)
        int at = type.isAnonymous() ? com.editora.lsp.JavaMembers.lastSegment(text, name) : name;
        fromAnonymous = type.isAnonymous();
        Header header = new Header(
                text,
                at,
                type.isAnonymous() ? LspCoordinator.offsetOf(text, type.startLine()) : -1,
                Map.of(),
                type.name(),
                !type.isAnonymous());
        if (type.isAnonymous()) {
            InstantiatedType.resolve(lspManager, ask, text, name, type.startLine(), type.endLine(), root -> {
                if (root != null) {
                    visit(root, "a", 1, false, header);
                }
                finish();
            });
            return;
        }
        lspManager.prepareTypeHierarchyAt(ask.path(), ask.libraryUri(), type.nameLine(), type.nameColumn(), roots -> {
            if (!roots.isEmpty()) {
                expand(roots.get(0), "", 0, false, header);
            }
            finish();
        });
    }

    private void expand(
            LspManager.HierarchyNode node, String order, int depth, boolean coveredBranch, @Nullable Header header) {
        pending++;
        lspManager.typeHierarchyChildren(ask.path(), node.raw(), true, supers -> {
            List<LspManager.HierarchyNode> sorted = superclassFirst(supers);
            for (int i = 0; i < sorted.size(); i++) {
                visit(sorted.get(i), order + (char) ('a' + Math.min(i, 25)), depth + 1, coveredBranch, header);
            }
            finish();
        });
    }

    /**
     * The text of {@code type}'s file: the anchor's, an open file's buffer (in any window sharing the server), or a
     * closed project file read once; null for another class file.
     */
    private @Nullable String textOf(LspManager.HierarchyNode type) {
        if (ask.libraryUri() != null
                ? ask.libraryUri().equals(type.libraryUri())
                : ask.path().equals(type.file())) {
            return text;
        }
        Path file = type.file();
        if (file == null) {
            return null;
        }
        String open = openText.apply(file);
        if (open == null) {
            open = lspManager.sentText(ask.path(), file); // open in another window sharing the server (twelfth BM3)
        }
        if (open != null) {
            return open;
        }
        return texts.computeIfAbsent(file, f -> {
                    try {
                        return java.util.Optional.of(java.nio.file.Files.readString(f));
                    } catch (java.io.IOException | RuntimeException e) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(null);
    }

    /**
     * {@code line} of {@code file} as it opens here: a file open only in another window that shares the server has its
     * positions in that window's unsaved text, and opens here from disk, so the line is the same line's there, the
     * nearest one (thirteenth review DK3).
     */
    private int onDisk(@Nullable Path file, int line) {
        if (file == null || openText.apply(file) != null) {
            return line;
        }
        String sent = lspManager.sentText(ask.path(), file);
        String disk = sent == null
                ? null
                : texts.computeIfAbsent(file, f -> {
                            try {
                                return java.util.Optional.of(java.nio.file.Files.readString(f));
                            } catch (java.io.IOException | RuntimeException e) {
                                return java.util.Optional.empty();
                            }
                        })
                        .orElse(null);
        if (sent == null || disk == null || sent.equals(disk)) {
            return line;
        }
        List<String> theirs = sent.lines().toList();
        List<String> ours = disk.lines().toList();
        if (line >= theirs.size()) {
            return line;
        }
        int best = line;
        for (int i = 0, d = Integer.MAX_VALUE; i < ours.size(); i++) {
            if (ours.get(i).equals(theirs.get(line)) && Math.abs(i - line) < d) {
                d = Math.abs(i - line);
                best = i;
            }
        }
        return best;
    }

    private String key(LspManager.HierarchyNode type) {
        return (type.file() != null ? type.file().toString() : type.libraryUri()) + ":" + type.line();
    }

    private void visit(
            LspManager.HierarchyNode type, String order, int depth, boolean coveredBranch, @Nullable Header subtype) {
        String key = key(type);
        if (coveredBranch) {
            // above a hit: a method found here is one the hit overrides; only its ancestors are marked
            if (covered.add(key) && depth <= MAX_DEPTH) {
                expand(type, order, depth, true, null);
            }
            return;
        }
        if (depth > MAX_DEPTH || !seen.add(key)) {
            return;
        }
        if (type.name().equals("Object") && !OBJECT_METHODS.contains(com.editora.lsp.JavaMembers.methodName(method))) {
            return; // Object declares none of this name: its outline is not read
        }
        pending++;
        lspManager.memberSpans(ask.path(), type.file(), type.libraryUri(), members -> {
            com.editora.lsp.MemberSpan declared = com.editora.lsp.JavaMembers.typeAt(members, type.line(), type.name());
            if (declared != null
                    && type.file() == null
                    && type.libraryUri() != null
                    && !LspManager.jdkClassFile(type.libraryUri()) // has sources: its outline names them (DK5)
                    && !declared.name().contains("<")) {
                // a jar class without sources: its outline names no type parameters, its decompiled header does
                // (LKeyed<Key>, LMid<T> extends LStore<T>) (twelfth review DL2, DO2)
                lspManager.classFileContents(
                        ask.path(),
                        type.libraryUri(),
                        own -> visited(type, order, depth, subtype, members, declared, own));
                return;
            }
            visited(type, order, depth, subtype, members, declared, declared == null ? null : textOf(type));
        });
    }

    /** {@link #visit}'s rest, with {@code type}'s outline and {@code own}, its file's text (null if none). */
    private void visited(
            LspManager.HierarchyNode type,
            String order,
            int depth,
            @Nullable Header subtype,
            List<com.editora.lsp.MemberSpan> members,
            com.editora.lsp.@Nullable MemberSpan declared,
            @Nullable String own) {
        String key = key(type);
        String name = declared == null
                ? ""
                : own == null || declared.name().contains("<")
                        ? declared.name()
                        : com.editora.lsp.JavaMembers.withTypeParameters(
                                own,
                                LspCoordinator.offsetOf(own, declared.nameLine()) + declared.nameColumn(),
                                declared.name());
        Map<String, String> bindings = declared == null || subtype == null ? Map.of() : subtype.bindingsFor(name);
        java.util.Set<String> typeVariables = new java.util.HashSet<>();
        if (declared != null) {
            typeVariables.addAll(com.editora.lsp.JavaMembers.typeParameters(members, declared));
            typeVariables.addAll(com.editora.lsp.JavaMembers.typeParameters(name));
        }
        List<com.editora.lsp.MemberSpan> hits = declared == null
                ? List.of()
                : com.editora.lsp.JavaMembers.overridden(
                        declared.children(), method, typeVariables, bindings, m -> ownTypeVariables(own, m));
        for (int i = 0; i < hits.size(); i++) {
            com.editora.lsp.MemberSpan hit = hits.get(i);
            found.put(
                    order + i,
                    new LspCoordinator.SuperTarget(
                            new LspManager.Target(
                                    type.file(),
                                    onDisk(type.file(), hit.nameLine()),
                                    hit.nameColumn(),
                                    type.libraryUri()),
                            type.name().replace('$', '.') + "." + com.editora.lsp.JavaMembers.methodName(hit.name())));
            foundIn.put(order + i, key);
        }
        Header header = declared == null
                ? null
                : new Header(
                        own,
                        own == null ? -1 : LspCoordinator.offsetOf(own, declared.nameLine()) + declared.nameColumn(),
                        -1,
                        bindings,
                        name,
                        true);
        expand(type, order, depth, !hits.isEmpty(), header);
        finish();
    }

    private void finish() {
        if (--pending > 0 || !ask.current().getAsBoolean()) {
            return;
        }
        List<LspCoordinator.SuperTarget> out = new java.util.ArrayList<>();
        found.forEach((order, target) -> {
            if (!covered.contains(foundIn.get(order)) && !isAnchor(target.target())) {
                out.add(target);
            }
        });
        if (out.isEmpty()
                && fromAnonymous
                && !objectAsked
                && OBJECT_METHODS.contains(com.editora.lsp.JavaMembers.methodName(method))) {
            objectAsked = true; // an interface's anonymous class: its hashCode overrides Object's (thirteenth BK4)
            objectMethods();
            return;
        }
        done.accept(out);
    }

    /** {@code java.lang.Object}'s method of the anchor's signature, found through jdtls's library types. */
    private void objectMethods() {
        pending++;
        lspManager.javaLibraryTypes(ask.path(), "Object", types -> {
            LspManager.WorkspaceSymbolMatch object = types.stream()
                    .filter(t -> t.name().equals("Object") && t.container().equals("java.lang"))
                    .findFirst()
                    .orElse(null);
            if (object == null || object.libraryUri() == null) {
                finish();
                return;
            }
            lspManager.memberSpans(ask.path(), null, object.libraryUri(), members -> {
                com.editora.lsp.MemberSpan declared =
                        com.editora.lsp.JavaMembers.typeAt(members, object.line(), "Object");
                List<com.editora.lsp.MemberSpan> hits = declared == null
                        ? List.of()
                        : com.editora.lsp.JavaMembers.overridden(declared.children(), method);
                for (int i = 0; i < hits.size(); i++) {
                    found.put(
                            "z" + i,
                            new LspCoordinator.SuperTarget(
                                    new LspManager.Target(
                                            null,
                                            hits.get(i).nameLine(),
                                            hits.get(i).nameColumn(),
                                            object.libraryUri()),
                                    "Object."
                                            + com.editora.lsp.JavaMembers.methodName(
                                                    hits.get(i).name())));
                    foundIn.put("z" + i, "Object");
                }
                finish();
            });
        });
    }

    /**
     * A candidate super method's own type variables, read from {@code own} (its file's text), each bound to the asked
     * method's at the same position ({@code <Out>} to {@code <U>}), or to null (any) when they do not pair up; none
     * for a class file, whose one-letter names the shape rule takes (eleventh review BM2).
     */
    private Map<String, @Nullable String> ownTypeVariables(@Nullable String own, com.editora.lsp.MemberSpan m) {
        if (own == null) {
            return Map.of();
        }
        List<String> theirs = com.editora.lsp.JavaMembers.methodTypeParameters(
                own,
                LspCoordinator.offsetOf(own, m.startLine()),
                LspCoordinator.offsetOf(own, m.nameLine()) + m.nameColumn());
        Map<String, @Nullable String> out = new java.util.HashMap<>();
        for (int i = 0; i < theirs.size(); i++) {
            out.put(theirs.get(i), theirs.size() == anchorTypeParameters.size() ? anchorTypeParameters.get(i) : null);
        }
        return out;
    }

    /** Whether {@code target} is the method asked about itself, never its own super method (tenth review AO2). */
    private boolean isAnchor(LspManager.Target target) {
        boolean sameFile = ask.libraryUri() != null
                ? ask.libraryUri().equals(target.classFileUri())
                : ask.path().equals(target.file());
        return sameFile && target.line() == anchor.nameLine() && target.character() == anchor.nameColumn();
    }

    /**
     * A type's declaration header in {@code text} from {@code from} ({@code ownName}: its own name; else the type an
     * anonymous class instantiates), or from {@code fallback} when that names no type arguments (an anonymous class's
     * {@code <>}: its field's declared type, never for a raw {@code new X()}), and what its own type variables stand for: the type arguments it gives a
     * supertype bind that supertype's type variables (tenth review DO2). A wildcard binds nothing (eleventh review
     * EM1); a raw supertype's are their erasure (BM4). With no text (a class file), a supertype's type variables keep
     * what this type's of the same names stand for when the two lists are the same ({@code List<E>} to
     * {@code Collection<E>}) (DO2).
     */
    private record Header(
            @Nullable String text, int from, int fallback, Map<String, String> bindings, String name, boolean ownName) {
        Map<String, String> bindingsFor(String supertype) {
            List<String> params = com.editora.lsp.JavaMembers.typeParameters(supertype);
            if (params.isEmpty()) {
                return Map.of();
            }
            Map<String, String> out = new java.util.HashMap<>();
            if (text == null) {
                if (params.equals(com.editora.lsp.JavaMembers.typeParameters(name))) {
                    for (String p : params) {
                        String bound = bindings.get(p);
                        if (bound != null) {
                            out.put(p, bound);
                        }
                    }
                }
                return out;
            }
            List<String> args = com.editora.lsp.JavaMembers.typeArguments(text, from, supertype, ownName);
            if (args.isEmpty()
                    && fallback >= 0
                    && !com.editora.lsp.JavaMembers.namesRaw(text, from, supertype, ownName)) {
                // only a <> takes its variable's type: a raw new Store2() {…} is raw (twelfth review BM4)
                args = com.editora.lsp.JavaMembers.typeArguments(text, fallback, supertype, false);
            }
            if (args.isEmpty() && com.editora.lsp.JavaMembers.namesRaw(text, from, supertype, ownName)) {
                Map<String, String> erasures = com.editora.lsp.JavaMembers.typeParameterErasures(supertype);
                for (String p : params) {
                    out.put(p, erasures.getOrDefault(p, "Object"));
                }
                return out;
            }
            if (args.size() != params.size()) {
                return out; // not named here: its type variables match any type
            }
            for (int i = 0; i < params.size(); i++) {
                if (!args.get(i).startsWith("?")) {
                    out.put(params.get(i), com.editora.lsp.JavaMembers.substitute(args.get(i), bindings));
                }
            }
            return out;
        }
    }
}
