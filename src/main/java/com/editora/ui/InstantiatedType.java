package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.lsp.JavaMembers;
import com.editora.lsp.LspManager;
import com.editora.lsp.MemberSpan;
import org.jspecify.annotations.Nullable;

/**
 * The type an anonymous class instantiates, as a type-hierarchy item, checked against the name written after its
 * {@code new}. In a {@code <>} anonymous creation jdtls resolves a type name to a same-named JDK type
 * ({@code java.util.stream.Sink}) or to the enclosing class, for Go to Definition too, so nothing it answers there is
 * opened unchecked (thirteenth review BK1, BL1, DK1, BK4). A simple name is asked where it is written, then where the
 * variable's declared type names it, then through the file's import; a qualified one ({@code Outer.Mid.Sink}) from its
 * first segment's type, the rest found in that type's outline (flat in a class file without sources) or a supertype's
 * (an inherited member type). Go to Super Method's, apart for {@link LspCoordinator}'s size.
 */
final class InstantiatedType {
    private static final int MAX_DEPTH = 4;
    private final LspManager lspManager;
    private final Path anchor;
    private final @Nullable String libraryUri;
    private final String text;
    private final String code;
    private final int last;
    private final int first;
    private final List<String> segments;
    private final int fromLine;
    private final int toLine;
    private final Consumer<LspManager.@Nullable HierarchyNode> done;

    private InstantiatedType(
            LspManager lspManager,
            Path anchor,
            @Nullable String libraryUri,
            String text,
            int name,
            int fromLine,
            int toLine,
            Consumer<LspManager.@Nullable HierarchyNode> done) {
        this.lspManager = lspManager;
        this.anchor = anchor;
        this.libraryUri = libraryUri;
        this.text = text;
        this.code = JavaMembers.codeOf(text);
        int word = Math.max(0, Math.min(name, text.length()));
        while (word > 0 && Character.isJavaIdentifierPart(text.charAt(word - 1))) {
            word--; // from the word's start: an outline's name may start inside it
        }
        this.last = JavaMembers.lastSegment(text, word);
        var qualified = JavaMembers.qualifiedName(text, last);
        this.first = qualified.getKey();
        this.segments = qualified.getValue().isEmpty() ? List.of("") : qualified.getValue();
        this.fromLine = fromLine;
        this.toLine = toLine;
        this.done = done;
    }

    /**
     * Resolves the type instantiated by the anonymous class whose {@code new} names it at {@code name} in {@code text}
     * (any offset in the name), the class itself on lines {@code fromLine} to {@code toLine}; {@code done} gets the
     * type's item on the FX thread, or null when none checks out.
     */
    static void resolve(
            LspManager lspManager,
            LspCoordinator.SuperAsk ask,
            String text,
            int name,
            int fromLine,
            int toLine,
            Consumer<LspManager.@Nullable HierarchyNode> done) {
        new InstantiatedType(lspManager, ask.path(), ask.libraryUri(), text, name, fromLine, toLine, done).run();
    }

    /**
     * Go to Definition's {@code targets} at {@code caret} in a Java file: on the last segment of a {@code <>} anonymous
     * creation's type name, where jdtls answers a same-named JDK type, the checked instantiated type instead (thirteenth
     * review BK5); else, or when none checks out, as they are.
     */
    static void checkDefinition(
            LspManager lspManager,
            Path path,
            String text,
            int caret,
            List<LspManager.Target> targets,
            Consumer<List<LspManager.Target>> cb) {
        int last = path.toString().endsWith(".java") ? diamondLast(text, caret) : -1;
        if (last < 0) {
            cb.accept(targets);
            return;
        }
        int line = (int) text.substring(0, last).chars().filter(c -> c == '\n').count();
        new InstantiatedType(
                        lspManager,
                        path,
                        null,
                        text,
                        last,
                        line,
                        line,
                        root -> cb.accept(
                                root == null
                                        ? targets
                                        : List.of(new LspManager.Target(
                                                root.file(), root.line(), root.col(), root.libraryUri()))))
                .run();
    }

    /** The offset of the word at {@code caret} when it is the last segment of {@code new X<>(…) {}}'s name, else -1. */
    static int diamondLast(String text, int caret) {
        String code = JavaMembers.codeOf(text);
        int s = Math.max(0, Math.min(caret, code.length()));
        int e = s;
        while (s > 0 && Character.isJavaIdentifierPart(code.charAt(s - 1))) {
            s--;
        }
        while (e < code.length() && Character.isJavaIdentifierPart(code.charAt(e))) {
            e++;
        }
        if (s == e) {
            return -1;
        }
        int start = JavaMembers.qualifiedName(text, s).getKey();
        boolean afterNew = code.substring(Math.max(0, start - 40), start).matches("(?s).*\\bnew\\s+");
        boolean diamond = java.util.regex.Pattern.compile("\\s*<\\s*>\\s*\\([^{};]*\\)\\s*\\{")
                .matcher(code)
                .region(e, Math.min(code.length(), e + 300))
                .lookingAt();
        return afterNew && diamond ? s : -1;
    }

    private void run() {
        int declared = declaredAt();
        int declaredLast = declared < 0 ? -1 : JavaMembers.lastSegment(text, declared);
        List<Integer> firsts = new ArrayList<>();
        int imported = importedAt();
        for (int at : new int[] {imported, declared, first}) {
            if (at >= 0 && !firsts.contains(at)) {
                firsts.add(at);
            }
        }
        if (segments.size() > 1) {
            // a nested type: from its first segment's type, which jdtls resolves (BL1); a package-qualified name
            // (java.util.Comparator) has none, and is asked at its last segment, here and then at the declaration
            structural(firsts, () -> prepareChecked(last, () -> prepareChecked(declaredLast, () -> done.accept(null))));
        } else {
            prepareChecked(last, () -> prepareChecked(declaredLast, () -> structural(firsts, () -> done.accept(null))));
        }
    }

    /** The item jdtls prepares at {@code offset}, when it is the written type; else {@code otherwise}. */
    private void prepareChecked(int offset, Runnable otherwise) {
        if (offset < 0) {
            otherwise.run();
            return;
        }
        int[] at = lineColumn(offset);
        lspManager.prepareTypeHierarchyAt(anchor, libraryUri, at[0], at[1], roots -> {
            LspManager.HierarchyNode root = roots.isEmpty() ? null : roots.get(0);
            if (root != null && itself(root)) {
                // the anonymous class itself, as for `new pkg.Type<>()` (tenth review AO2): its supertype as written
                lspManager.typeHierarchyChildren(anchor, root.raw(), true, supers -> {
                    LspManager.HierarchyNode named =
                            supers.stream().filter(this::names).findFirst().orElse(null);
                    if (named != null) {
                        done.accept(named);
                    } else {
                        otherwise.run();
                    }
                });
            } else if (root != null && names(root)) {
                done.accept(root);
            } else {
                otherwise.run();
            }
        });
    }

    /** From the type the first segment names at one of {@code firsts}, the rest of the written name in it. */
    private void structural(List<Integer> firsts, Runnable otherwise) {
        if (firsts.isEmpty()) {
            otherwise.run();
            return;
        }
        Runnable next = () -> structural(firsts.subList(1, firsts.size()), otherwise);
        int[] at = lineColumn(firsts.get(0));
        lspManager.definitionAt(anchor, libraryUri, at[0], at[1], targets -> {
            LspManager.Target outer = targets.isEmpty() ? null : targets.get(0);
            if (outer == null) {
                next.run();
                return;
            }
            lspManager.memberSpans(anchor, outer.file(), outer.classFileUri(), members -> {
                MemberSpan top = JavaMembers.typeAt(members, outer.line(), outer.character());
                if (top == null || !simple(top.name()).equals(segments.get(0))) {
                    next.run(); // a package's (java of java.util.Comparator), or a same-named other type
                    return;
                }
                member(outer.file(), outer.classFileUri(), members, top, segments.subList(1, segments.size()), 0, r -> {
                    if (r != null) {
                        done.accept(r);
                    } else {
                        next.run();
                    }
                });
            });
        });
    }

    /** The member type {@code path} names in {@code type} (of {@code members}), declared or inherited, prepared. */
    private void member(
            @Nullable Path file,
            @Nullable String uri,
            List<MemberSpan> members,
            MemberSpan type,
            List<String> path,
            int depth,
            Consumer<LspManager.@Nullable HierarchyNode> cb) {
        MemberSpan nested = path.isEmpty() ? type : JavaMembers.nestedType(members, type, path);
        if (nested != null) {
            lspManager.prepareTypeHierarchyAt(anchor, file, uri, nested.nameLine(), nested.nameColumn(), roots -> {
                LspManager.HierarchyNode root = roots.isEmpty() ? null : roots.get(0);
                cb.accept(root != null && simple(root.name()).equals(segments.get(segments.size() - 1)) ? root : null);
            });
            return;
        }
        if (depth >= MAX_DEPTH) {
            cb.accept(null);
            return;
        }
        // an inherited member type (Sub13.Sink, class Sub13 extends Wrong13.Mid): in a supertype (DK1)
        lspManager.prepareTypeHierarchyAt(anchor, file, uri, type.nameLine(), type.nameColumn(), roots -> {
            if (roots.isEmpty()) {
                cb.accept(null);
                return;
            }
            lspManager.typeHierarchyChildren(
                    anchor, roots.get(0).raw(), true, supers -> inSupers(supers, 0, path, depth, cb));
        });
    }

    private void inSupers(
            List<LspManager.HierarchyNode> supers,
            int i,
            List<String> path,
            int depth,
            Consumer<LspManager.@Nullable HierarchyNode> cb) {
        if (i >= supers.size()) {
            cb.accept(null);
            return;
        }
        LspManager.HierarchyNode s = supers.get(i);
        if (s.name().equals("Object")) {
            inSupers(supers, i + 1, path, depth, cb);
            return;
        }
        lspManager.memberSpans(anchor, s.file(), s.libraryUri(), members -> {
            MemberSpan type = JavaMembers.typeAt(members, s.line(), s.name());
            if (type == null) {
                inSupers(supers, i + 1, path, depth, cb);
                return;
            }
            member(s.file(), s.libraryUri(), members, type, path, depth + 1, r -> {
                if (r != null) {
                    cb.accept(r);
                } else {
                    inSupers(supers, i + 1, path, depth, cb);
                }
            });
        });
    }

    /** Whether {@code node} is the anonymous class itself, which jdtls answers at some names. */
    private boolean itself(LspManager.HierarchyNode node) {
        boolean here = libraryUri != null ? libraryUri.equals(node.libraryUri()) : anchor.equals(node.file());
        return here && node.line() >= fromLine && node.line() <= toLine;
    }

    /**
     * Whether {@code node} is the written type: its simple name, from a package the file can see it in (its own, an
     * import's, {@code java.lang}, or the one the name is qualified with), never a same-named JDK type it does not
     * import ({@code java.util.stream.Sink} for an imported {@code Sink}).
     */
    private boolean names(LspManager.HierarchyNode node) {
        if (!simple(node.name()).equals(segments.get(segments.size() - 1))) {
            return false;
        }
        String detail = node.detail() == null ? "" : node.detail().replace('$', '.');
        String outer = String.join(".", segments.subList(0, segments.size() - 1));
        if (!outer.isEmpty()) {
            // jdtls names a member type by its $-path (Wrong12$Mid$Sink, Map$Entry): it must be the written one, or
            // end with it under an outer type the file imports; else, written after a package, that package
            // (fourteenth check RJ1, RJ2)
            String written = String.join(".", segments);
            String path = node.name().replaceAll("<.*", "").replace('$', '.').strip();
            String above = path.endsWith("." + written) ? path.substring(0, path.length() - written.length()) : null;
            return path.equals(written)
                    || detail.isEmpty()
                    || (above != null
                            && java.util.regex.Pattern.compile("(?m)^\\s*import\\s+"
                                            + java.util.regex.Pattern.quote(detail + "." + above + segments.get(0))
                                            + "\\s*;")
                                    .matcher(code)
                                    .find())
                    || (written.endsWith("." + path)
                            && written.substring(0, written.length() - path.length() - 1)
                                    .equals(detail));
        }
        java.util.regex.Matcher pkg = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;")
                .matcher(code);
        String own = pkg.find() ? pkg.group(1) : "";
        return detail.isEmpty()
                || detail.equals("java.lang")
                || detail.equals(own)
                || (!own.isEmpty()
                        && detail.startsWith(own + ".")
                        && Character.isUpperCase(detail.charAt(own.length() + 1))) // a type of its package
                || (!outer.isEmpty() && (outer.startsWith(detail) || detail.endsWith("." + outer)))
                || java.util.regex.Pattern.compile(
                                "(?m)^\\s*import\\s+(static\\s+)?" + java.util.regex.Pattern.quote(detail) + "\\.")
                        .matcher(code)
                        .find();
    }

    /** The offset of the first segment's own name in the file's import of it ({@code Mid} of {@code import a.Pub13.Mid;}). */
    private int importedAt() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*import\\s+[\\w$.\\s]*\\.\\s*("
                        + java.util.regex.Pattern.quote(segments.get(0)) + ")\\s*;")
                .matcher(code);
        return m.find() ? m.start(1) : -1;
    }

    /**
     * The offset of the declared type's first segment when the statement declares the variable with the same name as
     * the {@code new} ({@code Mid.Sink<String> t = new Mid.Sink<>()}): there jdtls resolves it. -1 when it does not.
     */
    private int declaredAt() {
        int statement = Math.max(
                Math.max(code.lastIndexOf(';', first), code.lastIndexOf('{', first)), code.lastIndexOf('}', first));
        StringBuilder name = new StringBuilder("\\b");
        for (int i = 0; i < segments.size(); i++) {
            name.append(i == 0 ? "" : "\\s*\\.\\s*").append(java.util.regex.Pattern.quote(segments.get(i)));
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(name + "\\b")
                .matcher(code)
                .region(Math.max(0, statement + 1), Math.max(Math.max(0, statement + 1), first));
        int found = -1;
        while (m.find()) {
            found = m.start();
        }
        return found;
    }

    /** A type's simple name: jdtls names a member type by its $-path ({@code Holder$Pair}) (fourteenth check RJ1). */
    static String simple(String name) {
        return name.replaceAll("<.*", "").replaceAll(".*[$.]", "").strip();
    }

    /**
     * Where to open {@code root}, an anonymous class's instantiated type, for Go to Super Method on the class: its
     * name, not a constructor jdtls placed it at, and none for {@code java.lang.Object}, whose supertypes IntelliJ
     * never offers (fourteenth check RJ5).
     */
    static void typeTarget(
            LspManager lspManager,
            LspCoordinator.SuperAsk ask,
            LspManager.@Nullable HierarchyNode root,
            Consumer<LspManager.@Nullable Target> done) {
        if (root == null || (simple(root.name()).equals("Object") && "java.lang".equals(root.detail()))) {
            done.accept(null);
            return;
        }
        lspManager.memberSpans(ask.path(), root.file(), root.libraryUri(), members -> {
            MemberSpan type = JavaMembers.typeAt(members, root.line(), root.col());
            done.accept(
                    type == null || type.nameLine() == root.line()
                            ? new LspManager.Target(root.file(), root.line(), root.col(), root.libraryUri())
                            : new LspManager.Target(
                                    root.file(), type.nameLine(), type.nameColumn(), root.libraryUri()));
        });
    }

    private int[] lineColumn(int offset) {
        return LspCoordinator.lineColumn(text, offset);
    }
}
