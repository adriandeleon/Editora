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

    void start(com.editora.lsp.MemberSpan type) {
        pending++;
        int name = LspCoordinator.offsetOf(text, type.nameLine()) + type.nameColumn();
        // an anonymous class from its name's last segment (Comparator of java.util.Comparator<>, Pair of
        // Holder.Pair<>):
        // there jdtls prepares the instantiated type, at the first segment the anonymous class, which it may give no
        // supertypes (eleventh review BM1)
        int at = type.isAnonymous() ? com.editora.lsp.JavaMembers.lastSegment(text, name) : name;
        int column = type.nameColumn() + Math.max(0, at - name);
        Header header = new Header(
                text,
                at,
                type.isAnonymous() ? LspCoordinator.offsetOf(text, type.startLine()) : -1,
                Map.of(),
                type.name(),
                !type.isAnonymous());
        lspManager.prepareTypeHierarchyAt(ask.path(), ask.libraryUri(), type.nameLine(), column, roots -> {
            if (!roots.isEmpty()) {
                LspManager.HierarchyNode root = roots.get(0);
                boolean itself = root.line() >= type.startLine()
                        && root.line() <= type.endLine()
                        && (ask.libraryUri() != null
                                ? ask.libraryUri().equals(root.libraryUri())
                                : ask.path().equals(root.file()));
                if (type.isAnonymous() && !itself) {
                    // jdtls answers with the instantiated type itself, but with the anonymous class for
                    // `new pkg.Type<>()` (tenth review AO2)
                    visit(root, "a", 1, false, header);
                } else {
                    expand(root, "", 0, false, header);
                }
            }
            finish();
        });
    }

    private void expand(
            LspManager.HierarchyNode node, String order, int depth, boolean coveredBranch, @Nullable Header header) {
        pending++;
        lspManager.typeHierarchyChildren(ask.path(), node.raw(), true, supers -> {
            List<LspManager.HierarchyNode> sorted = LspCoordinator.superclassFirst(supers);
            for (int i = 0; i < sorted.size(); i++) {
                visit(sorted.get(i), order + (char) ('a' + Math.min(i, 25)), depth + 1, coveredBranch, header);
            }
            finish();
        });
    }

    /**
     * The text of {@code type}'s file: the anchor's, an open file's buffer, or a closed project file read once; null for
     * another class file.
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
            Map<String, String> bindings =
                    declared == null || subtype == null ? Map.of() : subtype.bindingsFor(declared.name());
            String own = declared == null ? null : textOf(type);
            List<com.editora.lsp.MemberSpan> hits = declared == null
                    ? List.of()
                    : com.editora.lsp.JavaMembers.overridden(
                            declared.children(),
                            method,
                            com.editora.lsp.JavaMembers.typeParameters(members, declared),
                            bindings,
                            m -> ownTypeVariables(own, m));
            for (int i = 0; i < hits.size(); i++) {
                com.editora.lsp.MemberSpan hit = hits.get(i);
                found.put(
                        order + i,
                        new LspCoordinator.SuperTarget(
                                new LspManager.Target(type.file(), hit.nameLine(), hit.nameColumn(), type.libraryUri()),
                                type.name() + "." + com.editora.lsp.JavaMembers.methodName(hit.name())));
                foundIn.put(order + i, key);
            }
            Header header = declared == null
                    ? null
                    : new Header(
                            own,
                            own == null
                                    ? -1
                                    : LspCoordinator.offsetOf(own, declared.nameLine()) + declared.nameColumn(),
                            -1,
                            bindings,
                            declared.name(),
                            true);
            expand(type, order, depth, !hits.isEmpty(), header);
            finish();
        });
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
        done.accept(out);
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
     * {@code <>}: its field's declared type), and what its own type variables stand for: the type arguments it gives a
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
            if (args.isEmpty() && fallback >= 0) {
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
