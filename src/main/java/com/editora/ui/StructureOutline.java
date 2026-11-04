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
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.editor.highlight.TextMateHighlighter.Symbol;
import com.editora.lsp.SymbolNode;
import com.editora.maven.PomFiles;
import com.editora.text.EncryptedNames;
import com.editora.text.FoldRegions.Region;
import com.editora.text.LanguageRegistry;
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

    /**
     * As {@link #fromLsp(List)} for a document in {@code language}: an XML document's outline is tidied (no
     * "Grammars" pseudo-rows, element rows named by their {@code name}/{@code id}/{@code match} attribute — see
     * {@link XmlOutline#tidyLsp}), reading the document's lines through {@code lineAt}.
     */
    static List<StructureNode> fromLsp(
            List<SymbolNode> symbols,
            @Nullable String language,
            java.util.function.IntFunction<String> lineAt,
            int lineCount) {
        return "xml".equals(language) ? XmlOutline.tidyLsp(symbols, lineAt, lineCount) : fromLsp(symbols);
    }

    /**
     * As {@link #fromLsp(List, String, java.util.function.IntFunction, int)}, a pom's rows named the pom-aware way
     * ({@link PomOutlineLabels}): the Maven server names its symbols by element ("dependency", "plugin"), so the
     * outline was a wall of identical rows exactly when that server was on (review 2, E4; Maven review C16).
     */
    static List<StructureNode> fromLsp(
            List<SymbolNode> symbols,
            @Nullable String language,
            boolean pom,
            java.util.function.IntFunction<String> lineAt,
            int lineCount) {
        List<StructureNode> nodes = fromLsp(symbols, language, lineAt, lineCount);
        return pom ? pomLabelled(lineAt, lineCount, nodes) : nodes;
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
            List<Symbol> symbols,
            @Nullable String fileName) {

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
                    buffer.symbols(),
                    nameOf(buffer));
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
                    List.of(),
                    nameOf(buffer));
        }

        private static @Nullable String nameOf(EditorBuffer buffer) {
            java.nio.file.Path path = buffer.getPath();
            java.nio.file.Path name = path == null ? null : path.getFileName();
            return name == null ? null : name.toString();
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
                    List.of(),
                    fileName);
        }

        /**
         * This source with its regions and symbols detected from the text. Pure: any thread. A language whose outline
         * is read from its lines alone (Go, YAML, Dockerfile) skips the tokenizing: it was most of the cost of a big
         * Go file's outline and none of its content.
         */
        Source analyzed() {
            if ("go".equals(language) || "yaml".equals(language) || "dockerfile".equals(language)) {
                return this;
            }
            List<Region> detected =
                    com.editora.editor.fold.FoldManager.detectRegions(text, language, null, List.of(), true);
            TextMateHighlighter.Analysis analysis = grammar == null ? null : TextMateHighlighter.analyze(text, grammar);
            List<Symbol> found = analysis == null ? List.of() : analysis.symbols();
            return new Source(
                    text, lineCount, lineAt, language, markdown, typst, http, pom, grammar, detected, found, fileName);
        }
    }

    static List<StructureNode> build(Source src) {
        List<StructureNode> nodes = outline(src);
        return "makefile".equals(src.language()) ? makeTargets(nodes, src.lineAt(), src.lineCount()) : nodes;
    }

    private static List<StructureNode> outline(Source src) {
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
        if ("yaml".equals(src.language())) {
            return yamlKeyNodes(src.lineAt(), paras);
        }
        if ("go".equals(src.language())) {
            return GoOutline.build(linesOf(src)); // receivers, fields, constants; no package-as-type (review 3, C3)
        }
        if ("dockerfile".equals(src.language())) {
            return dockerfileStageNodes(src.lineAt(), paras);
        }
        if (com.editora.text.HtmlElements.isHtmlLanguage(src.language())) {
            return HtmlOutline.build(src.text()); // tag#id.class, as the server labels them (HTML review C14)
        }
        if ("xml".equals(src.language()) && !src.pom()) {
            List<StructureNode> elements = XmlOutline.build(src.text()); // by qualified name and naming attribute
            return elements.isEmpty() ? DtdOutline.build(src.text()) : elements; // a DTD has declarations instead
        }
        // Sort so each region precedes the regions it contains: by start asc, then by end desc.
        regions.sort(Comparator.comparingInt(Region::startLine)
                .thenComparing(Comparator.comparingInt(Region::endLine).reversed()));

        // Name/kind come from the TextMate symbols on (or, for Allman braces, just above) each region's
        // header. Regions with no symbol are control-flow/anonymous blocks and are dropped (declutter).
        Map<Integer, Symbol> symbolByLine = new HashMap<>();
        boolean shell = "shell".equals(src.language());
        String fileName = src.fileName();
        boolean gradleScript =
                fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".gradle.kts");
        for (Symbol s : src.symbols()) {
            if (shell && !SHELL_NAME.matcher(s.name()).matches()) {
                continue; // `"${DOCKER[@]}" version` at a line start is tagged as a name: `$` is no function (C13)
            }
            if (s.name().startsWith("@")) {
                continue; // an annotation (Kotlin scopes `@Deprecated` as a type): not a declaration (C8)
            }
            symbolByLine.putIfAbsent(s.line(), s);
        }
        String lang = src.language();
        if (lang != null && (lang.startsWith("javascript") || lang.startsWith("typescript"))) {
            // A JSX element (`<ul>`) is markup, not a declaration, now that a multi-line one folds too.
            symbolByLine.values().removeIf(sym -> "tag".equals(sym.kind()));
            scriptConstants(src.lineAt(), regions, symbolByLine, paras); // `const routes = {` … (A106)
        }
        boolean haveSymbols = !symbolByLine.isEmpty();
        boolean brace = isBraceLanguage(src.language());
        if ("css".equals(src.language())) {
            return cssRuleNodes(src.lineAt(), regions, paras);
        }
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
            // A Kotlin build script is its DSL blocks (`plugins {`, `tasks.register("x") {`), which carry no symbol:
            // every block is labelled by its header, as a Groovy build's are; its one-line `fun`s still come below.
            StructureNode node = nodeFor(src.lineAt(), r, symbolByLine, haveSymbols && !gradleScript, brace);
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
        if (language != null) {
            // Every language, not only JS/TS: a one-line member (`int get() { return x; }`, Kotlin's
            // `fun top() = 1`) has no fold region to hang on, and a class of them came out empty.
            boolean script = language.startsWith("javascript") || language.startsWith("typescript");
            addFoldlessSymbols(rootNodes, symbolByLine, paras, script ? null : src.lineAt());
        }
        if ("java".equals(language)) {
            JavaFields.add(rootNodes, linesOf(src)); // the grammar marks no field name (review 2 C11, review 3 C6)
        }
        if (src.pom()) {
            rootNodes = pomLabelled(src.lineAt(), paras, rootNodes);
        }
        return rootNodes;
    }

    /** {@code const|let|var NAME = {} / [} / (}, exported or not: a variable whose value spans lines. */
    private static final java.util.regex.Pattern SCRIPT_CONSTANT = java.util.regex.Pattern.compile(
            "\\s*(?:export\\s+)?(?:declare\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]*)?=\\s*[{\\[(]");

    /**
     * A JavaScript/TypeScript variable whose object, array or call spans lines ({@code const routes = {},
     * {@code export const config = {} … {@code } satisfies X;}) heads a fold region but is no TextMate symbol, so
     * the outline left it out with all its members. It gets a symbol of its own on its header line.
     */
    static void scriptConstants(
            java.util.function.IntFunction<String> lineAt,
            List<Region> regions,
            Map<Integer, Symbol> symbols,
            int lines) {
        for (Region r : regions) {
            int line = r.startLine();
            if (line < 0 || line >= lines || symbols.containsKey(line)) {
                continue;
            }
            String text = lineAt.apply(line);
            java.util.regex.Matcher m = text == null ? null : SCRIPT_CONSTANT.matcher(text);
            if (m != null && m.lookingAt()) {
                symbols.put(line, new Symbol(line, m.group(1), "variable"));
            }
        }
    }

    /** A shell function's name: what `name()` or `function name` may declare, `::` and `-` included. */
    private static final java.util.regex.Pattern SHELL_NAME =
            java.util.regex.Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_:.\\-]*");

    /** The source's lines as a list (read through its {@code lineAt}). */
    private static List<String> linesOf(Source src) {
        java.util.function.IntFunction<String> lineAt = src.lineAt();
        int size = src.lineCount();
        return new java.util.AbstractList<>() {
            @Override
            public String get(int index) {
                String line = lineAt.apply(index);
                return line == null ? "" : line;
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    /**
     * A Makefile's targets, each with its recipe (the tab-indented lines below it) as its block, and a target
     * defined more than once — once per {@code ifeq}/{@code else} branch — labelled by its line ({@code all (line
     * 93)}, {@code all (line 96)}): the rows were identical, with nothing to tell which branch each one opens (C14).
     */
    private static List<StructureNode> makeTargets(
            List<StructureNode> nodes, java.util.function.IntFunction<String> lineAt, int paras) {
        Map<String, Integer> counts = new HashMap<>();
        for (StructureNode n : nodes) {
            counts.merge(n.label(), 1, Integer::sum);
        }
        List<StructureNode> out = new ArrayList<>(nodes.size());
        for (StructureNode n : nodes) {
            Region region = n.region();
            if (region == null) {
                int end = n.line();
                while (end + 1 < paras && lineAt.apply(end + 1).startsWith("\t")) {
                    end++;
                }
                region = end > n.line() ? new Region(n.line(), end) : null;
            }
            String label = counts.getOrDefault(n.label(), 0) > 1
                    ? tr("structure.targetAtLine", n.label(), n.line() + 1)
                    : n.label();
            StructureNode copy = new StructureNode(region, label, n.kind(), n.line(), n.detail());
            copy.children().addAll(n.children());
            out.add(copy);
        }
        return out;
    }

    /** A Dockerfile instruction that starts a build stage: {@code FROM [--platform=…] image [AS name]}. */
    private static final java.util.regex.Pattern DOCKER_FROM =
            java.util.regex.Pattern.compile("(?i)^\\s*FROM\\s+(?:--\\S+\\s+)*(\\S+)(?:\\s+AS\\s+(\\S+))?\\s*$");

    /**
     * A Dockerfile's build stages, one row per {@code FROM} line — named by its {@code AS} stage, else by its image —
     * each running to the line before the next stage. The Structure button used to lead to "No structure" for
     * every Dockerfile (C14).
     */
    static List<StructureNode> dockerfileStageNodes(java.util.function.IntFunction<String> lineAt, int paras) {
        List<Integer> starts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int line = 0; line < paras; line++) {
            String text = lineAt.apply(line);
            java.util.regex.Matcher m = text == null ? null : DOCKER_FROM.matcher(text);
            if (m != null && m.matches()) {
                starts.add(line);
                labels.add(m.group(2) != null ? m.group(2) + " — " + m.group(1) : m.group(1));
            }
        }
        List<StructureNode> roots = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : paras - 1;
            while (end > start && lineAt.apply(end).isBlank()) {
                end--;
            }
            roots.add(new StructureNode(new Region(start, Math.max(start, end)), labels.get(i), "module", start));
        }
        return roots;
    }

    /**
     * A stylesheet's rules by their selector or at-rule, as written before the {@code &#123;} — {@code .card .title},
     * {@code #id > p}, {@code @media (…)} — nested as their blocks are. The grammar names only type selectors
     * ({@code body}), so compound selectors and at-rules were left out of the outline.
     */
    private static List<StructureNode> cssRuleNodes(
            java.util.function.IntFunction<String> lineAt, List<Region> regions, int paras) {
        regions.sort(Comparator.comparingInt(Region::startLine)
                .thenComparing(Comparator.comparingInt(Region::endLine).reversed()));
        List<StructureNode> roots = new ArrayList<>();
        Deque<StructureNode> stack = new ArrayDeque<>();
        for (Region r : regions) {
            if (r.startLine() < 0 || r.startLine() >= paras) {
                continue;
            }
            String header = lineAt.apply(r.startLine());
            int brace = header.indexOf('{');
            String label =
                    (brace >= 0 ? header.substring(0, brace) : header).strip().replaceAll("\\s+", " ");
            if (label.isEmpty() || label.startsWith("/*")) {
                continue; // a comment block, or a brace on a line of its own
            }
            StructureNode node = new StructureNode(r, label, label.startsWith("@") ? "module" : "class", r.startLine());
            while (!stack.isEmpty() && !contains(stack.peek().region(), r)) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(node);
            } else {
                stack.peek().children().add(node);
            }
            stack.push(node);
        }
        return roots;
    }

    /**
     * A YAML file's keys, nested by indentation, read from the lines: {@code key:} and {@code - key:}, quoted or
     * not, but not the lines of a block scalar ({@code |}, {@code >}) or a comment. The grammar marks keys such as
     * {@code on} or {@code yes} as booleans, so GitHub's {@code on:} went missing and the keys under it hung under
     * the key before.
     *
     * <p>A key whose value goes on below it — a block scalar, a list, a nested map — gets that block as its region,
     * up to the line before the next line indented no deeper than the key (a list written at the key's own indent,
     * as Kubernetes manifests do, still belongs to it): a ConfigMap's {@code data} script used to preview as its one
     * key line (review 3, C7).
     */
    static List<StructureNode> yamlKeyNodes(java.util.function.IntFunction<String> lineAt, int paras) {
        java.util.regex.Pattern key = java.util.regex.Pattern.compile(
                "^(\\s*)(- +)?(\"[^\"]*\"|'[^']*'|[^\\s#'\"\\-?:,\\[\\]{}&*!|>%@`][^:#]*?)\\s*:(\\s|$)");
        final class Open {
            final String name;
            final int line;
            final int at;
            final boolean valueBelow;
            final List<StructureNode> children = new ArrayList<>();

            Open(String name, int line, int at, boolean valueBelow) {
                this.name = name;
                this.line = line;
                this.at = at;
                this.valueBelow = valueBelow;
            }
        }
        List<StructureNode> roots = new ArrayList<>();
        Deque<Open> stack = new ArrayDeque<>();
        int lastContent = -1;
        int scalarIndent = -1; // inside a block scalar opened at this indent
        for (int line = 0; line <= paras; line++) {
            String text = line < paras ? lineAt.apply(line) : null;
            if (text != null && text.isBlank()) {
                continue;
            }
            int indent = text == null ? -1 : text.length() - text.stripLeading().length();
            if (scalarIndent >= 0 && text != null && indent > scalarIndent) {
                lastContent = line; // a line of the block scalar
                continue;
            }
            scalarIndent = -1;
            if (text != null && text.stripLeading().startsWith("#")) {
                continue; // a comment ends no key
            }
            boolean item = text != null && text.stripLeading().startsWith("-");
            while (!stack.isEmpty()
                    && (text == null
                            || indent < stack.peek().at
                            || indent == stack.peek().at && !(item && stack.peek().valueBelow))) {
                Open done = stack.pop();
                int end = Math.max(done.line, lastContent);
                StructureNode node = new StructureNode(
                        end > done.line ? new Region(done.line, end) : null, done.name, "key", done.line);
                node.children().addAll(done.children);
                (stack.isEmpty() ? roots : stack.peek().children).add(node);
            }
            if (text == null) {
                break;
            }
            lastContent = line;
            java.util.regex.Matcher m = key.matcher(text);
            if (!m.find()) {
                continue;
            }
            int at = m.group(2) == null ? indent : indent + m.group(2).length();
            String name = m.group(3).strip();
            if (name.length() > 1 && (name.startsWith("\"") || name.startsWith("'"))) {
                name = name.substring(1, name.length() - 1);
            }
            String rest = text.substring(m.end()).strip();
            if (rest.startsWith("|") || rest.startsWith(">")) {
                scalarIndent = indent;
            }
            stack.push(new Open(name, line, at, rest.isEmpty() || rest.startsWith("#")));
        }
        return roots;
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
            List<StructureNode> rootNodes,
            Map<Integer, Symbol> symbolByLine,
            int paras,
            java.util.function.@Nullable IntFunction<String> callCheck) {
        java.util.Set<Integer> used = new java.util.HashSet<>();
        collectLines(rootNodes, used);
        boolean added = false;
        for (Map.Entry<Integer, Symbol> e : symbolByLine.entrySet()) {
            int line = e.getKey();
            if (used.contains(line)
                    || line < 0
                    || line >= paras
                    || "tag".equals(e.getValue().kind())
                    || callCheck != null
                            && memberCall(callCheck.apply(line), e.getValue().name())) {
                continue; // a JSX element (`<p>`) is markup, not a declaration; `x.run()` is a call
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

    /**
     * Whether {@code name} stands on {@code line} only as a member call ({@code action.run();}): a declaration never
     * has a dot right before its name, except a Kotlin extension function ({@code fun Int.twice() = …}).
     */
    private static boolean memberCall(@Nullable String line, String name) {
        if (line == null || name == null || name.isEmpty() || line.contains("fun ")) {
            return false;
        }
        int at = line.indexOf(name);
        return at > 0 && line.charAt(at - 1) == '.';
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
                if (opener < 0) {
                    // A class header wrapped before its brace (`class A extends B` / `implements C {`): the name is
                    // on a line above, which neither opens nor ends anything (review 3, C5 and C8).
                    opener = wrappedHeaderLine(lineAt, start, symbolByLine);
                }
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

    /** How far above a block's opening line the start of a wrapped declaration header is looked for. */
    private static final int MAX_HEADER_LINES = 6;

    /**
     * The line of the declaration whose header {@code braceLine} ends ({@code implements Foo &#123;}, Kotlin's
     * {@code : Base &#123;} under a wrapped constructor), or -1: the nearest line above, within {@link
     * #MAX_HEADER_LINES}, with a symbol, passing only lines that neither end a statement or block nor open one. A
     * blank line, a comment's end, an annotation or a line ending in {@code ;}, {@code &#123;} or {@code &#125;}
     * stops the search, so a block inside a method never borrows a declaration above it.
     */
    static int wrappedHeaderLine(
            java.util.function.IntFunction<String> lineText, int braceLine, Map<Integer, Symbol> symbolByLine) {
        String header = lineText.apply(braceLine).strip();
        if (header.startsWith("}") || header.startsWith(")")) {
            return -1;
        }
        for (int line = braceLine - 1; line >= 0 && braceLine - line <= MAX_HEADER_LINES; line--) {
            String text = lineText.apply(line).strip();
            if (text.isEmpty()
                    || text.startsWith("@")
                    || text.startsWith("}")
                    || text.endsWith("*/")
                    || text.endsWith(";")
                    || text.endsWith("{")
                    || text.endsWith("}")) {
                return -1;
            }
            if (symbolByLine.containsKey(line)) {
                return line;
            }
        }
        return -1;
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
