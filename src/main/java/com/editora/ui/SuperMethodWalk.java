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
    private final Map<Path, java.util.Optional<String>> texts = new java.util.HashMap<>();
    private final java.util.TreeMap<String, LspCoordinator.SuperTarget> found = new java.util.TreeMap<>();
    private final Map<String, String> foundIn = new java.util.HashMap<>();
    private final java.util.Set<String> seen = new java.util.HashSet<>();
    private final java.util.Set<String> covered = new java.util.HashSet<>();
    private int pending;

    /** {@code anchor}: the method asked about, in {@code text} (the anchor file's). */
    SuperMethodWalk(
            LspManager lspManager,
            LspCoordinator.SuperAsk ask,
            String text,
            com.editora.lsp.MemberSpan anchor,
            java.util.function.Consumer<List<LspCoordinator.SuperTarget>> done) {
        this.lspManager = lspManager;
        this.ask = ask;
        this.text = text;
        this.anchor = anchor;
        this.method = anchor.name();
        this.done = done;
    }

    void start(com.editora.lsp.MemberSpan type) {
        pending++;
        Header header = new Header(
                text,
                LspCoordinator.offsetOf(text, type.nameLine()) + type.nameColumn(),
                type.isAnonymous() ? LspCoordinator.offsetOf(text, type.startLine()) : -1,
                Map.of());
        lspManager.prepareTypeHierarchyAt(ask.path(), ask.libraryUri(), type.nameLine(), type.nameColumn(), roots -> {
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

    /** The text of {@code type}'s file: the anchor's, or a project file read once; null for another class file. */
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
            List<com.editora.lsp.MemberSpan> hits = declared == null
                    ? List.of()
                    : com.editora.lsp.JavaMembers.overridden(
                            declared.children(),
                            method,
                            com.editora.lsp.JavaMembers.typeParameters(members, declared),
                            bindings);
            for (int i = 0; i < hits.size(); i++) {
                com.editora.lsp.MemberSpan hit = hits.get(i);
                found.put(
                        order + i,
                        new LspCoordinator.SuperTarget(
                                new LspManager.Target(type.file(), hit.nameLine(), hit.nameColumn(), type.libraryUri()),
                                type.name() + "." + com.editora.lsp.JavaMembers.methodName(hit.name())));
                foundIn.put(order + i, key);
            }
            String own = declared == null ? null : textOf(type);
            Header header = own == null || declared == null
                    ? null
                    : new Header(
                            own,
                            LspCoordinator.offsetOf(own, declared.nameLine()) + declared.nameColumn(),
                            -1,
                            bindings);
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

    /** Whether {@code target} is the method asked about itself, never its own super method (tenth review AO2). */
    private boolean isAnchor(LspManager.Target target) {
        boolean sameFile = ask.libraryUri() != null
                ? ask.libraryUri().equals(target.classFileUri())
                : ask.path().equals(target.file());
        return sameFile && target.line() == anchor.nameLine() && target.character() == anchor.nameColumn();
    }

    /**
     * A type's declaration header in {@code text} from {@code from} (its name), or from {@code fallback} when that
     * names no type arguments (an anonymous class's {@code <>}: its field's declared type), and what its own type
     * variables stand for: the type arguments it gives a supertype bind that supertype's type variables (tenth
     * review DO2).
     */
    private record Header(String text, int from, int fallback, Map<String, String> bindings) {
        Map<String, String> bindingsFor(String supertype) {
            List<String> params = com.editora.lsp.JavaMembers.typeParameters(supertype);
            if (params.isEmpty()) {
                return Map.of();
            }
            List<String> args = com.editora.lsp.JavaMembers.typeArguments(text, from, supertype);
            if (args.isEmpty() && fallback >= 0) {
                args = com.editora.lsp.JavaMembers.typeArguments(text, fallback, supertype);
            }
            if (args.size() != params.size()) {
                return Map.of(); // raw, or not named here: its type variables match any type
            }
            Map<String, String> out = new java.util.HashMap<>();
            for (int i = 0; i < params.size(); i++) {
                out.put(params.get(i), com.editora.lsp.JavaMembers.substitute(args.get(i), bindings));
            }
            return out;
        }
    }
}
