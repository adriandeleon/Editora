package com.editora.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import com.editora.editor.crypto.EncryptedNames;
import com.editora.editor.fold.FoldRegions.Region;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.editor.highlight.TextMateHighlighter.Symbol;
import com.editora.editor.language.LanguageRegistry;
import com.editora.lsp.SymbolNode;
import com.editora.maven.PomFiles;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The structure of a source file as the Structure tool window shows it: the language server's document
 * symbols when there are some ({@link #fromLsp}), otherwise a heuristic outline built from the fold regions
 * and the TextMate symbols ({@link #build}), with dedicated outlines for Markdown and Typst headings, {@code
 * .http} requests, Clojure forms and pom coordinates. It is shared by {@link StructurePanel} (the active
 * buffer) and the Project Map's structure columns ({@link ProjectMapStructure}, any file, open or not).
 */
final class StructureOutline {

    private StructureOutline() {}

    /**
     * Whether a file of this name can have an outline: a language with a bundled grammar, or one of the
     * languages with an outline of their own (Markdown, Typst, {@code .http}, Clojure). Decided by the name
     * alone, so it costs no file read; an encrypted file has none (its text is not readable without a key).
     */
    static boolean supports(String fileName) {
        if (fileName == null || EncryptedNames.extensionOf(fileName) != null) {
            return false;
        }
        String language = LanguageRegistry.forFileName(fileName);
        return switch (language) {
            case "markdown", "typst", "http", "clojure" -> true;
            case LanguageRegistry.PLAINTEXT, "log" -> false;
            default ->
                GrammarRegistry.shared().hasGrammarFor(fileName)
                        || GrammarRegistry.shared().pickableLanguageNames().contains(language);
        };
    }

    /** Converts the LSP symbol tree into structure nodes (region-less; navigation uses the symbol line). */
    static List<StructureNode> fromLsp(List<SymbolNode> symbols) {
        List<StructureNode> out = new ArrayList<>();
        for (SymbolNode s : symbols) {
            if (s == null) {
                continue;
            }
            int line = Math.max(0, s.line());
            Region range = s.endLine() >= line ? new Region(line, s.endLine()) : null;
            StructureNode node = new StructureNode(range, s.name(), s.kind(), line, s.detail());
            node.children().addAll(fromLsp(s.children()));
            out.add(node);
        }
        return out;
    }

    /**
     * What the heuristic outline is built from: the text as lines, the fold regions and the TextMate symbols,
     * with the buffer's kind. {@link #area} reads the editor's own (the whole document while the buffer is
     * whole); {@link #document} snapshots {@code getContent()} for a narrowed buffer, whose regions and symbols
     * are then detected afresh — off the FX thread, see {@link #analyzed()}.
     */
    record Source(
            String text,
            int lineCount,
            java.util.function.IntFunction<String> lineAt,
            String language,
            boolean markdown,
            boolean typst,
            boolean http,
            boolean pom,
            org.eclipse.tm4e.core.grammar.@Nullable IGrammar grammar,
            List<Region> regions,
            List<Symbol> symbols) {

        static Source area(EditorBuffer buffer) {
            CodeArea area = buffer.getArea();
            return new Source(
                    area.getText(),
                    area.getParagraphs().size(),
                    i -> area.getParagraph(i).getText(),
                    buffer.getLanguage(),
                    buffer.isMarkdown(),
                    buffer.isTypst(),
                    buffer.isHttpFile(),
                    buffer.isPom(),
                    null,
                    buffer.getFoldManager().regions(),
                    buffer.symbols());
        }

        /** The whole document, regions and symbols still to be detected ({@link #analyzed}). FX thread. */
        static Source document(EditorBuffer buffer) {
            String text = buffer.getContent();
            List<String> lines = List.of(text.split("\n", -1));
            String language = buffer.getLanguage();
            return new Source(
                    text,
                    lines.size(),
                    lines::get,
                    language,
                    buffer.isMarkdown(),
                    buffer.isTypst(),
                    buffer.isHttpFile(),
                    buffer.isPom(),
                    GrammarRegistry.shared().forLanguageName(language),
                    List.of(),
                    List.of());
        }

        /**
         * A file's text as read from disk, its kind decided by its name the way a buffer's is ({@link
         * LanguageRegistry#forFileName}); regions and symbols still to be detected ({@link #analyzed}). Any thread,
         * though the first use of a language compiles its grammar, so callers keep it off the FX thread.
         */
        static Source file(String fileName, String text) {
            List<String> lines = List.of(text.split("\n", -1));
            String language = LanguageRegistry.forFileName(fileName);
            boolean pom = "xml".equals(language)
                    && PomFiles.isPom(fileName, text.substring(0, Math.min(text.length(), PomFiles.SNIFF_CHARS)));
            return new Source(
                    text,
                    lines.size(),
                    lines::get,
                    language,
                    "markdown".equals(language),
                    "typst".equals(language),
                    "http".equals(language),
                    pom,
                    GrammarRegistry.shared().forLanguageName(language),
                    List.of(),
                    List.of());
        }

        /** This source with its regions and symbols detected from the text. Pure: any thread. */
        Source analyzed() {
            List<Region> detected =
                    com.editora.editor.fold.FoldManager.detectRegions(text, language, null, List.of(), true);
            TextMateHighlighter.Analysis analysis = grammar == null ? null : TextMateHighlighter.analyze(text, grammar);
            List<Symbol> found = analysis == null ? List.of() : analysis.symbols();
            return new Source(text, lineCount, lineAt, language, markdown, typst, http, pom, grammar, detected, found);
        }
    }

    static List<StructureNode> build(Source src) {
        if (src.markdown()) {
            return markdownHeadingNodes(src.text());
        }
        if (src.typst()) {
            return typstHeadingNodes(src.text());
        }
        if (src.http()) {
            return httpRequestNodes(src.text());
        }
        int paras = src.lineCount();
        List<Region> regions = new ArrayList<>(new LinkedHashSet<>(src.regions()));
        if ("clojure".equals(src.language())) {
            return clojureFormNodes(src.text(), regions);
        }
        // Sort so each region precedes the regions it contains: by start asc, then by end desc.
        regions.sort(Comparator.comparingInt(Region::startLine)
                .thenComparing(Comparator.comparingInt(Region::endLine).reversed()));

        // Name/kind come from the TextMate symbols on (or, for Allman braces, just above) each region's
        // header. Regions with no symbol are control-flow/anonymous blocks and are dropped (declutter).
        Map<Integer, Symbol> symbolByLine = new HashMap<>();
        for (Symbol s : src.symbols()) {
            symbolByLine.putIfAbsent(s.line(), s);
        }
        boolean haveSymbols = !symbolByLine.isEmpty();
        boolean brace = isBraceLanguage(src.language());
        if (haveSymbols && "python".equals(src.language())) {
            // Python nests by indentation, as Python does: a one-line `def f(): ...` folds nothing but is still
            // structure. Each node takes the block its header opens (sorted outermost-first per start line).
            Map<Integer, Region> blockByLine = new HashMap<>();
            for (Region r : regions) {
                blockByLine.putIfAbsent(r.startLine(), r);
            }
            return symbolNodes(src.lineAt(), symbolByLine, paras, blockByLine);
        }
        if (regions.isEmpty() && haveSymbols) {
            // No delimiter folding for this language (python, ruby, shell, lua, yaml, ini …): there are no
            // regions to hang the symbols on, and the outline used to come out empty. Nest by indentation.
            return symbolNodes(src.lineAt(), symbolByLine, paras, Map.of());
        }

        List<StructureNode> rootNodes = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        for (Region r : regions) {
            if (r.startLine() < 0 || r.startLine() >= paras) {
                continue;
            }
            StructureNode node = nodeFor(src.lineAt(), r, symbolByLine, haveSymbols, brace);
            if (node == null) {
                continue; // no symbol: skip this region (its kept descendants nest under the nearest kept ancestor)
            }
            while (!stack.isEmpty() && !contains(stack.peek().region(), r)) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                rootNodes.add(node);
            } else {
                stack.peek().children().add(node);
            }
            stack.push(node);
        }
        if (rootNodes.isEmpty() && haveSymbols) {
            // Regions, but none headed by a symbol — say only `# region` markers in a language whose blocks
            // do not fold: the symbols are still the structure (one marker used to empty the outline).
            return symbolNodes(src.lineAt(), symbolByLine, paras, Map.of());
        }
        String language = src.language();
        if (language != null && (language.startsWith("javascript") || language.startsWith("typescript"))) {
            addFoldlessSymbols(rootNodes, symbolByLine, paras);
        }
        if (src.pom()) {
            rootNodes = pomLabelled(src.lineAt(), paras, rootNodes);
        }
        return rootNodes;
    }

    /** A pom's outline names dependencies/plugins by {@code groupId:artifactId} and profiles by id (C16). */
    private static List<StructureNode> pomLabelled(
            java.util.function.IntFunction<String> lineAt, int count, List<StructureNode> nodes) {
        List<StructureNode> out = new ArrayList<>(nodes.size());
        for (StructureNode n : nodes) {
            int end = n.region() == null ? -1 : n.region().endLine();
            String pom = PomOutlineLabels.label(n.label(), n.line(), end, count, lineAt);
            String label = pom == null ? n.label() : pom; // null only for a null element name
            StructureNode copy = new StructureNode(n.region(), label, n.kind(), n.line(), n.detail());
            copy.children().addAll(pomLabelled(lineAt, count, n.children()));
            out.add(copy);
        }
        return out;
    }

    /**
     * Adds the symbols no region is headed by — a one-line method ({@code get(key) { return x; }}), a one-line
     * arrow function ({@code const twice = (x) => x * 2;}) — as leaves under the innermost node whose block holds
     * their line. In JavaScript those are most declarations, and the outline used to leave every one of them out.
     */
    private static void addFoldlessSymbols(
            List<StructureNode> rootNodes, Map<Integer, Symbol> symbolByLine, int paras) {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        collectLines(rootNodes, used);
        boolean added = false;
        for (Map.Entry<Integer, Symbol> e : symbolByLine.entrySet()) {
            int line = e.getKey();
            if (used.contains(line)
                    || line < 0
                    || line >= paras
                    || "tag".equals(e.getValue().kind())) {
                continue; // a JSX element (`<p>`) is markup, not a declaration
            }
            List<StructureNode> siblings = rootNodes;
            for (boolean deeper = true; deeper; ) {
                deeper = false;
                for (StructureNode n : siblings) {
                    if (n.region() != null
                            && n.region().startLine() < line
                            && line <= n.region().endLine()) {
                        siblings = n.children();
                        deeper = true;
                        break;
                    }
                }
            }
            siblings.add(
                    new StructureNode(null, e.getValue().name(), e.getValue().kind(), line));
            added = true;
        }
        if (added) {
            sortByLine(rootNodes);
        }
    }

    private static void collectLines(List<StructureNode> nodes, java.util.Set<Integer> out) {
        for (StructureNode n : nodes) {
            out.add(n.line());
            collectLines(n.children(), out);
        }
    }

    private static void sortByLine(List<StructureNode> nodes) {
        nodes.sort(Comparator.comparingInt(StructureNode::line));
        for (StructureNode n : nodes) {
            sortByLine(n.children());
        }
    }

    /**
     * Builds a node for a region: its label/kind from the TextMate symbol on the header line (or, for
     * brace languages, the signature on the nearest non-blank lines above the {@code &#123;}). Returns
     * {@code null} when no symbol is found and a grammar is active (declutter). Without symbols (no
     * grammar) it falls back to the header-line text, keeping every region.
     */
    private static @Nullable StructureNode nodeFor(
            java.util.function.IntFunction<String> lineAt,
            Region r,
            Map<Integer, Symbol> symbolByLine,
            boolean haveSymbols,
            boolean brace) {
        int start = r.startLine();
        if (haveSymbols) {
            Symbol symbol = symbolByLine.get(start);
            int line = start;
            // Only look above when the delimiter is on its own line (Allman style). Looking above every
            // brace-backed fold lets an inner `if (...) {` reuse its enclosing method's symbol and lets
            // `} else {` pick up the preceding call expression, producing outlines such as
            // ifLsp() -> ifLsp() -> run(). A non-standalone opening brace belongs to its own header line;
            // without a definition symbol there, the region is control flow/anonymous and is not structure.
            if (symbol == null && brace && standaloneOpeningBrace(lineAt.apply(start))) {
                int probe = start - 1;
                int examined = 0;
                while (probe >= 0 && examined < 3) {
                    String t = lineAt.apply(probe).trim();
                    if (!t.isEmpty()) {
                        examined++;
                        Symbol up = symbolByLine.get(probe);
                        if (up != null) {
                            symbol = up;
                            line = probe;
                            break;
                        }
                    }
                    probe--;
                }
            } else if (symbol == null && brace) {
                // A signature wrapped over several lines: the name is on the line that opened the
                // parameter list, the region starts on the line that closes it (`String language) {`).
                int opener = signatureStartLine(lineAt, start);
                if (opener >= 0 && symbolByLine.get(opener) != null) {
                    symbol = symbolByLine.get(opener);
                    line = opener;
                }
            }
            if (symbol == null) {
                return null;
            }
            return new StructureNode(r, symbol.name(), symbol.kind(), line);
        }
        // No grammar/symbols: keep the old header-text label and show every region.
        String text = lineAt.apply(start).trim();
        return new StructureNode(r, text.isEmpty() ? tr("structure.line", start + 1) : text, null, start);
    }

    /** How far above a block's opening line the start of its wrapped signature is looked for. */
    private static final int MAX_SIGNATURE_LINES = 40;

    /**
     * The line that opened the parameter list which {@code braceLine} closes, or -1 when {@code braceLine}
     * does not close one. Only a header with more {@code )} than {@code (} qualifies, so an ordinary
     * {@code if (x) &#123;} or a lambda argument never borrows the symbol of a line above it.
     */
    static int signatureStartLine(java.util.function.IntFunction<String> lineText, int braceLine) {
        String header = lineText.apply(braceLine);
        int balance = parenBalance(header);
        if (balance >= 0 || header.strip().startsWith("}")) {
            return -1;
        }
        for (int line = braceLine - 1; line >= 0 && braceLine - line <= MAX_SIGNATURE_LINES; line--) {
            balance += parenBalance(lineText.apply(line));
            if (balance >= 0) {
                return line;
            }
        }
        return -1;
    }

    private static int parenBalance(String line) {
        int balance = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') {
                balance++;
            } else if (c == ')') {
                balance--;
            }
        }
        return balance;
    }

    /** An outline built from the symbols alone, nested by each symbol line's indentation. */
    private static List<StructureNode> symbolNodes(
            java.util.function.IntFunction<String> lineAt,
            Map<Integer, Symbol> symbolByLine,
            int paras,
            Map<Integer, Region> blockByLine) {
        List<StructureNode> rootNodes = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (int line : new java.util.TreeSet<>(symbolByLine.keySet())) {
            if (line < 0 || line >= paras) {
                continue;
            }
            Symbol symbol = symbolByLine.get(line);
            if (symbol == null) {
                continue; // a key of the map itself: never null
            }
            String text = lineAt.apply(line);
            int indent = text.length() - text.stripLeading().length();
            while (!indents.isEmpty() && indents.peek() >= indent) {
                indents.pop();
                stack.pop();
            }
            StructureNode node = new StructureNode(blockByLine.get(line), symbol.name(), symbol.kind(), line);
            if (stack.isEmpty()) {
                rootNodes.add(node);
            } else {
                stack.peek().children().add(node);
            }
            stack.push(node);
            indents.push(indent);
        }
        return rootNodes;
    }

    /** Whether an Allman-style opening delimiter may take its declaration symbol from a preceding line. */
    static boolean standaloneOpeningBrace(String line) {
        if (line == null) {
            return false;
        }
        String stripped = line.strip();
        return stripped.equals("{") || stripped.startsWith("{ //") || stripped.startsWith("{/*");
    }

    /** Builds the heading outline for a Markdown buffer (ATX/Setext), nested by heading level. */
    private static List<StructureNode> markdownHeadingNodes(String text) {
        List<StructureNode> roots = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        for (com.editora.markdown.MarkdownOutline.Heading h : com.editora.markdown.MarkdownOutline.headings(text)) {
            String title = com.editora.markdown.MarkdownText.plain(h.title()) // "**Bold** `x`" reads "Bold x"
                    .replaceAll("\\s+", " ");
            StructureNode node =
                    new StructureNode(null, title.isBlank() ? tr("common.untitled") : title, "heading", h.line());
            while (!levels.isEmpty() && levels.peek() >= h.level()) {
                stack.pop();
                levels.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children().add(node);
            }
            stack.push(node);
            levels.push(h.level());
        }
        return roots;
    }

    /**
     * One node per request of a {@code .http} file: its name (when it has one) and method + URL. Its own
     * "request" kind: as a "method" it got the function icon and a trailing {@code ()} ({@code GET /slow()}).
     */
    private static List<StructureNode> httpRequestNodes(String text) {
        List<StructureNode> roots = new ArrayList<>();
        for (com.editora.http.HttpFile.Request r : com.editora.http.HttpFile.parse(text)) {
            String call = r.method() + " " + r.url();
            String label = r.name() == null || r.name().isBlank() ? call : r.name() + " — " + call;
            roots.add(new StructureNode(null, label, HTTP_REQUEST_KIND, r.startLine()));
        }
        return roots;
    }

    /**
     * One node per top-level Clojure definition — {@code ns}, {@code def}/{@code defonce}, {@code defn},
     * {@code defmacro}, {@code defmulti}, {@code deftest}, {@code defprotocol}, {@code defrecord}/{@code deftype}
     * — read by the server-free declaration scanner. The Clojure grammar names none of them (it emits no
     * {@code entity.name.*} scope), so the TextMate path found no structure in a Clojure file at all. Each node
     * takes the fold region its form opens, when the form spans lines.
     */
    private static List<StructureNode> clojureFormNodes(String text, List<Region> regions) {
        Map<Integer, Region> formByLine = new HashMap<>();
        for (Region r : regions) {
            formByLine.merge(r.startLine(), r, (a, b) -> a.endLine() >= b.endLine() ? a : b); // the outermost
        }
        List<StructureNode> roots = new ArrayList<>();
        List<Integer> lineStarts = new ArrayList<>(List.of(0));
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            lineStarts.add(i + 1);
        }
        for (com.editora.index.Symbol s : com.editora.index.DeclarationScanner.scan(text, "clojure")) {
            String kind = switch (s.kind()) {
                case MODULE -> "namespace";
                case INTERFACE -> "interface";
                case TYPE, ENUM -> "struct";
                case VARIABLE, FIELD -> "variable";
                case FUNCTION, METHOD -> "function";
            };
            String params = "function".equals(kind) && s.line() < lineStarts.size()
                    ? clojureParams(
                            text,
                            lineStarts.get(s.line()) + s.column() + s.name().length())
                    : "";
            roots.add(new StructureNode(formByLine.get(s.line()), s.name(), kind, s.line(), params));
        }
        return roots;
    }

    /** How far past a Clojure definition's name its parameter vector is looked for. */
    private static final int MAX_CLOJURE_PARAMS_REACH = 400;

    /**
     * The parameter vector of the Clojure {@code defn}/{@code defmacro} whose name ends at {@code from} —
     * {@code [x y]}, past a docstring and an attribute map — whitespace collapsed; {@code ""} for a multi-arity
     * definition or none within reach. Shown in place of the {@code ()} every other function gets.
     */
    static String clojureParams(String text, int from) {
        int n = Math.min(text.length(), from + MAX_CLOJURE_PARAMS_REACH);
        int i = skipClojureSpace(text, from, n);
        if (i < n && text.charAt(i) == '"') { // the docstring
            for (i++; i < n && text.charAt(i) != '"'; i++) {
                if (text.charAt(i) == '\\') {
                    i++;
                }
            }
            i = skipClojureSpace(text, i + 1, n);
        }
        if (i < n && text.charAt(i) == '{') { // an attribute map
            int depth = 0;
            for (; i < n; i++) {
                char c = text.charAt(i);
                depth += c == '{' ? 1 : c == '}' ? -1 : 0;
                if (depth == 0) {
                    break;
                }
            }
            i = skipClojureSpace(text, i + 1, n);
        }
        if (i >= n || text.charAt(i) != '[') {
            return "";
        }
        int depth = 0;
        for (int j = i; j < n; j++) {
            char c = text.charAt(j);
            if (c == '[' || c == '{' || c == '(') {
                depth++;
            } else if (c == ']' || c == '}' || c == ')') {
                if (--depth == 0) {
                    return text.substring(i, j + 1).replaceAll("\\s+", " ");
                }
            }
        }
        return "";
    }

    private static int skipClojureSpace(String text, int i, int n) {
        while (i < n && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
            i++;
        }
        return i;
    }

    /** The Structure kind of a {@code .http} request (its icon: {@link StructureIcons}). */
    static final String HTTP_REQUEST_KIND = "request";

    /**
     * Builds the heading outline for a Typst buffer, nested by level — the Markdown branch's twin.
     *
     * <p>Without it a {@code .typ} file fell through to the generic fold-region path, which for Typst meant
     * its brace pairs labelled by whatever TextMate symbol sat on the header line: a report with three
     * sections showed one entry reading {@code #align()}. Sections are what a Typst document is navigated
     * by, and nothing else in the outline was worth keeping alongside them.
     */
    private static List<StructureNode> typstHeadingNodes(String text) {
        List<StructureNode> roots = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        var outline = com.editora.typst.TypstOutline.scan(text);

        // Headings and bindings are merged by line so the outline reads in document order, and each
        // binding nests under whichever section it is written in — a #let below "= Setup" belongs to it.
        // A binding above the first heading stays at the root, which is where such definitions live.
        record Entry(int line, int level, String label, String kind) {}
        List<Entry> entries = new ArrayList<>();
        for (var h : outline.headings()) {
            entries.add(
                    new Entry(h.line(), h.level(), h.title().isBlank() ? tr("common.untitled") : h.title(), "heading"));
        }
        for (var b : outline.bindings()) {
            // Level 0 = "not a section": it never becomes a parent and never closes one.
            entries.add(new Entry(b.line(), 0, b.name(), "let".equals(b.kind()) ? "function" : "property"));
        }
        entries.sort(java.util.Comparator.comparingInt(Entry::line));

        for (Entry e : entries) {
            StructureNode node = new StructureNode(null, e.label(), e.kind(), e.line());
            if (e.level() > 0) {
                while (!levels.isEmpty() && levels.peek() >= e.level()) {
                    stack.pop();
                    levels.pop();
                }
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children().add(node);
            }
            if (e.level() > 0) {
                stack.push(node);
                levels.push(e.level());
            }
        }
        return roots;
    }

    /** Languages whose folds are brace-delimited (Allman braces put the signature above the start line). */
    private static boolean isBraceLanguage(String language) {
        return switch (language == null ? "" : language) {
            case "java", "json", "c", "cpp", "rust", "go", "kotlin", "groovy", "scala", "csharp", "css" -> true;
            default -> false;
        };
    }

    private static boolean contains(@Nullable Region outer, Region inner) {
        return outer != null && outer.startLine() <= inner.startLine() && outer.endLine() >= inner.endLine();
    }
}
