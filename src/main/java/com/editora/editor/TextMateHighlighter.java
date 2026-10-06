package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Computes RichTextFX style spans for a whole document using a TextMate {@link IGrammar}.
 *
 * <p>The document is tokenized line by line, carrying the grammar's {@link IStateStack} across line
 * boundaries so multi-line constructs (block comments, heredocs, fenced code) highlight correctly.
 * Each token's TextMate scope list is reduced to a single CSS style class (see
 * {@link #styleForScopes}); the classes are themed in {@code styles/syntax.css}.
 */
public final class TextMateHighlighter {

    /**
     * Longest line handed to the tokenizer; a longer one is left unstyled and the grammar state is carried
     * across it unchanged. Tokenizing is not interruptible within a line and holds the per-grammar lock, and
     * its cost grows with the line: a single 60,000-character minified-JavaScript line measured ~1.9 s
     * (20,000: ~0.6 s) while the same length of JSON took ~20 ms. The cap is VS Code's
     * {@code editor.maxTokenizationLineLength} default.
     */
    static final int MAX_TOKENIZED_LINE = 20_000;

    /**
     * Per-line budget passed to tm4e, the backstop for a line under the length cap that a grammar's regexes
     * handle pathologically. Generous on purpose: an ordinary line takes microseconds and the slowest capped
     * line of real code measured stays under it, so hitting it means something is wrong with that line, and
     * what the budget buys is a bounded wait for cancellation and for the next buffer of the same language.
     */
    static final Duration LINE_TIMEOUT = Duration.ofMillis(1000);

    private static final Logger LOG = Logger.getLogger(TextMateHighlighter.class.getName());
    private static final Set<String> REPORTED_FAILURES = ConcurrentHashMap.newKeySet();

    private TextMateHighlighter() {}

    /** A named definition found while tokenizing: the 0-based {@code line}, its {@code name}, and a coarse {@code kind}. */
    public record Symbol(int line, String name, String kind) {}

    /** The result of one tokenization pass: highlight {@code spans} plus the document's {@code symbols}. */
    public record Analysis(StyleSpans<Collection<String>> spans, List<Symbol> symbols) {}

    /**
     * The result of an incremental pass tokenizing lines {@code [fromLine … end]}:
     * <ul>
     *   <li>{@code fromOffset} — character offset of {@code fromLine}'s start (pass to
     *       {@code CodeArea.setStyleSpans(fromOffset, spans)});</li>
     *   <li>{@code spans} — styles covering {@code [fromOffset … end]};</li>
     *   <li>{@code endStates} — the grammar end-state of each tokenized line, so the next edit can
     *       resume mid-document;</li>
     *   <li>{@code symbols} — definitions found on the tokenized lines (absolute line indices).</li>
     * </ul>
     */
    public record IncrementalAnalysis(
            int fromOffset, StyleSpans<Collection<String>> spans, List<IStateStack> endStates, List<Symbol> symbols) {}

    /**
     * Follows a pass line by line, on the tokenizing thread. It sees exactly what goes into the spans — so a
     * consumer needing the token classes (bracket depth skips strings and comments) does not have to walk
     * the finished spans again — and it can end the pass early.
     */
    interface LineHook {
        /** The next {@code length} characters of the range carry {@code style} ({@code null} = unstyled). */
        void run(String style, int length);

        /**
         * Line {@code lineIndex}, occupying {@code [lineStart, lineEnd)} of the text, has been tokenized and
         * ended in {@code endState}. Returning {@code true} stops the pass after this line; the analysis then
         * covers up to {@code lineEnd}, without the line's terminator.
         */
        boolean lineDone(int lineIndex, int lineStart, int lineEnd, IStateStack endState);
    }

    /**
     * Style spans covering the whole text. Returns {@code null} for empty text or a null grammar
     * (RichTextFX cannot build zero-length spans) — callers should skip applying styles then.
     */
    public static StyleSpans<Collection<String>> compute(String text, IGrammar grammar) {
        Analysis analysis = analyze(text, grammar);
        return analysis == null ? null : analysis.spans();
    }

    /**
     * Tokenizes the whole document once, producing both the highlight spans and the list of named
     * definitions (functions, types, sections, tags…) for the structure view. Returns {@code null}
     * for empty text or a null grammar.
     */
    public static Analysis analyze(String text, IGrammar grammar) {
        if (text == null || text.isEmpty() || grammar == null) {
            return null;
        }
        IncrementalAnalysis a = analyzeFrom(text, grammar, 0, null);
        return a == null ? null : new Analysis(a.spans(), a.symbols());
    }

    /**
     * Tokenizes {@code text} from line {@code fromLine} (with grammar {@code startState}, the end
     * state of the previous line, or {@code null} for line 0) to the end. Used for incremental
     * re-highlighting: the unchanged prefix before an edit is skipped. Returns {@code null} for a
     * null grammar/text.
     */
    public static IncrementalAnalysis analyzeFrom(String text, IGrammar grammar, int fromLine, IStateStack startState) {
        return analyzeFrom(text, grammar, fromLine, startState, () -> false);
    }

    /**
     * {@link #analyzeFrom(String, IGrammar, int, IStateStack)} with a cooperative cancellation check,
     * consulted between lines: when {@code cancelled} reports true the pass returns {@code null} and — the
     * point — <b>releases the shared grammar monitor</b>. Without it a superseded pass runs to completion
     * however large the document: a 16k-line buffer whose highlight was dispatched and then invalidated
     * (an edit, a language switch, a closed tab) kept tokenizing for minutes on a slow machine while every
     * other buffer of the same language sat blocked behind the per-grammar lock (see the suite wedge this
     * fixed: a perf test's leaked pass serialized all java highlighting on CI). The check is one volatile
     * read per line — noise next to {@code tokenizeLine}.
     */
    public static IncrementalAnalysis analyzeFrom(
            String text, IGrammar grammar, int fromLine, IStateStack startState, BooleanSupplier cancelled) {
        return analyzeFrom(text, grammar, fromLine, startState, cancelled, null);
    }

    /**
     * {@link #analyzeFrom(String, IGrammar, int, IStateStack, BooleanSupplier)} observed by a
     * {@link LineHook}, which may stop the pass before the end of the text. {@code spans} is {@code null}
     * when the tokenized range is empty.
     */
    static IncrementalAnalysis analyzeFrom(
            String text,
            IGrammar grammar,
            int fromLine,
            IStateStack startState,
            BooleanSupplier cancelled,
            LineHook hook) {
        return analyzeFrom(text, grammar, fromLine, startState, cancelled, hook, LINE_TIMEOUT);
    }

    /** As above with an explicit per-line budget, so a test can make a line run out of it. */
    static IncrementalAnalysis analyzeFrom(
            String text,
            IGrammar grammar,
            int fromLine,
            IStateStack startState,
            BooleanSupplier cancelled,
            LineHook hook,
            Duration lineTimeout) {
        if (text == null || grammar == null) {
            return null;
        }
        // A grammar instance is shared across buffers (GrammarRegistry caches one per scope), and each
        // buffer tokenizes on its own background thread. tm4e's tokenizeLine is not thread-safe, so two
        // buffers of the same language highlighting at once would corrupt the grammar's internal state
        // and throw — silently dropping one file's highlighting. Serialize per grammar instance:
        // same-grammar passes run sequentially; different grammars still run in parallel.
        synchronized (grammar) {
            return analyzeFromLocked(text, grammar, fromLine, startState, cancelled, hook, lineTimeout);
        }
    }

    /**
     * Logs a swallowed tokenizer failure, once per grammar. Degrading a line to plain text is right for the
     * editor, but doing it silently hid a grammar whose root scanner could never compile (one look-behind
     * joni rejects): every line of every Ruby file threw, and nothing said so. Once per grammar, because a
     * broken grammar throws on each line of each pass.
     */
    private static void reportTokenizeFailure(IGrammar grammar, Throwable e) {
        String scope;
        try {
            scope = String.valueOf(grammar.getScopeName());
        } catch (RuntimeException | LinkageError ex) {
            scope = "?";
        }
        if (REPORTED_FAILURES.add(scope)) {
            LOG.log(Level.WARNING, "Syntax highlighting: the " + scope + " grammar failed to tokenize a line", e);
        }
    }

    /** Scopes whose tokenizer failure has been logged (see {@link #reportTokenizeFailure}). Test hook. */
    static Set<String> reportedFailures() {
        return REPORTED_FAILURES;
    }

    private static IncrementalAnalysis analyzeFromLocked(
            String text,
            IGrammar grammar,
            int fromLine,
            IStateStack startState,
            BooleanSupplier cancelled,
            LineHook hook,
            Duration lineTimeout) {
        // Adjacent runs with the same style are merged before they reach the builder: we collapse
        // many TextMate scopes onto a few coarse classes, so a single line yields long stretches of
        // identical (or empty) styling. RichTextFX materializes one Text node per span, so emitting
        // every token as its own span balloons the node count and makes layout/scrolling crawl.
        SpanMerger spans = new SpanMerger(hook);
        List<IStateStack> endStates = new ArrayList<>();
        List<Symbol> symbols = new ArrayList<>();
        IStateStack state = startState;
        int fromOffset = offsetOfLine(text, fromLine);
        int pos = fromOffset;
        int length = text.length();
        int lineIndex = fromLine;
        while (true) {
            if (cancelled.getAsBoolean()) {
                return null; // superseded — stop tokenizing and release the grammar monitor promptly
            }
            int newline = text.indexOf('\n', pos);
            int lineEnd = newline < 0 ? length : newline;
            int lineLength = lineEnd - pos;
            if (lineLength > MAX_TOKENIZED_LINE) {
                spans.add(null, lineLength); // too long to tokenize: plain text, state carried across it
            } else {
                // One copy per line, terminator included when the text has one: the newline is what makes
                // end-of-line ($) anchors and line-ending rules match, and tm4e only appends its own (a
                // second copy) to a line that lacks it.
                String line = text.substring(pos, newline < 0 ? length : newline + 1);
                // Some grammars contain rules tm4e can't parse (malformed captures) or regexes the joni
                // backend rejects (variable-length look-behind, etc.). Tokenizing such a line throws; we
                // must never let that escape onto the JavaFX thread, so degrade that line to plain text
                // and carry the last good state forward.
                try {
                    ITokenizeLineResult<IToken[]> result = grammar.tokenizeLine(line, state, lineTimeout);
                    IToken[] tokens = result.getTokens();
                    int count = tokens.length;
                    if (result.isStoppedEarly()) {
                        // Out of budget part-way: the state it stopped in belongs to the middle of the
                        // line, so the line's start state is carried instead, and its last token — tm4e's
                        // filler from the stopping point to the end of the line — is left unstyled.
                        count = Math.max(0, count - 1);
                    } else {
                        state = result.getRuleStack();
                    }
                    addLineSpans(spans, lineLength, tokens, count);
                    collectSymbol(symbols, lineIndex, line, lineLength, tokens, count);
                } catch (Exception | LinkageError e) {
                    spans.add(null, lineLength - spans.lineProgress());
                    reportTokenizeFailure(grammar, e);
                }
            }
            spans.endLine();
            endStates.add(state); // end state of this line (carried unchanged over a failed or skipped line)
            if (hook != null && hook.lineDone(lineIndex, pos, lineEnd, state)) {
                break;
            }
            if (newline < 0) {
                break;
            }
            spans.add(null, 1); // the '\n' itself
            spans.endLine();
            pos = newline + 1;
            lineIndex++;
        }
        return new IncrementalAnalysis(fromOffset, spans.build(), endStates, symbols);
    }

    /** Character offset where 0-based {@code line} starts; clamps to the text end if past the last line. */
    private static int offsetOfLine(String text, int line) {
        if (line <= 0) {
            return 0;
        }
        int pos = 0;
        for (int count = 0; count < line; count++) {
            int newline = text.indexOf('\n', pos);
            if (newline < 0) {
                return text.length();
            }
            pos = newline + 1;
        }
        return pos;
    }

    /**
     * Records the definition name on a line (if any) for the structure view: the first declaration token,
     * except that a function name wins over a type name before it — in Go's
     * {@code func (s *Server) Run()} the receiver type comes first, and every method of a type used to be
     * listed as that type. A type name that is only a <em>reference</em> is not a definition at all.
     */
    private static void collectSymbol(
            List<Symbol> symbols, int lineIndex, String line, int len, IToken[] tokens, int count) {
        Symbol type = null;
        for (int i = 0; i < count; i++) {
            IToken token = tokens[i];
            String kind = kindForScopes(token.getScopes());
            if (kind == null) {
                continue;
            }
            int start = Math.min(token.getStartIndex(), len);
            int end = Math.min(token.getEndIndex(), len);
            if (end <= start) {
                continue;
            }
            String name = line.substring(start, end).strip();
            if (name.isEmpty()) {
                continue;
            }
            if (!kind.equals("type")) {
                symbols.add(new Symbol(lineIndex, name, kind));
                return; // one definition name per line is enough for the outline
            }
            if (type == null && !isTypeReference(token.getScopes(), line.substring(0, start))) {
                type = new Symbol(lineIndex, name, kind);
            }
        }
        if (type != null) {
            symbols.add(type);
        }
    }

    /** Words that introduce a type declaration, for grammars whose declared and referenced names share a scope. */
    private static final java.util.regex.Pattern TYPE_DECLARATION_PREFIX = java.util.regex.Pattern.compile(
            ".*\\b(?:type|impl|class|struct|interface|enum|trait|union|object|record|module|namespace|typedef"
                    + "|protocol|extension|typealias|newtype|data|message|service|input|scalar)(?:<[^>]*>)?\\s*$");

    /**
     * Whether a type-name token is a use of a type rather than its declaration. Grammars mark type
     * references with {@code entity.name.type*} too — a parameter's or field's annotation, a generic
     * argument, a return type, a Rust lifetime or {@code Some}/{@code None}, a cast — and each of those on
     * a block's header line became a bogus "type" entry in the outline.
     *
     * <p>A declaration is recognised by its sub-kind ({@code entity.name.type.class.ts},
     * {@code .struct.rust}, {@code .interface}, …). A bare {@code entity.name.type.<lang>} — what Go, Rust,
     * TypeScript, Kotlin and C++ give references, and Go also gives declarations — counts as one only
     * when a declaring keyword precedes it on the line, or (Go's grouped {@code type ( … )} form) nothing
     * does.
     *
     * @param scopes the token's scope stack
     * @param before the line's text before the token
     */
    static boolean isTypeReference(List<String> scopes, String before) {
        String entity = null;
        for (String scope : scopes) {
            if (scope.startsWith("meta.type.annotation")
                    || scope.startsWith("meta.type.parameters")
                    || scope.startsWith("meta.return.type")) {
                return true;
            }
            if (scope.startsWith("entity.name.type") || scope.startsWith("entity.name.class")) {
                entity = scope;
            }
        }
        if (entity == null || entity.startsWith("entity.name.class")) {
            return false;
        }
        String[] parts = entity.split("\\.");
        if (parts.length > 4) { // entity.name.type.<sub-kind>.<lang>
            String sub = parts[3];
            return sub.equals("lifetime")
                    || sub.equals("primitive")
                    || sub.equals("numeric")
                    || sub.equals("option")
                    || sub.equals("result")
                    || sub.equals("parameter");
        }
        if (parts.length < 4) {
            return false; // "entity.name.type" with no language suffix: nothing to go by
        }
        if (TYPE_DECLARATION_PREFIX.matcher(before).matches()) {
            return false;
        }
        return !(entity.endsWith(".go") && before.isBlank());
    }

    /**
     * Classifies a token's scopes as a definition kind for the structure view, or {@code null} if the
     * token is not a definition name. Calls are excluded: their name carries {@code entity.name.*} too, but
     * every bundled grammar that can tell the two apart marks the call (see {@link #isCallScope}).
     *
     * <p>What decides is the <em>innermost</em> structural ({@code meta.*}) scope around the name, not any
     * call scope anywhere above it: Java and PHP keep a call's scope open across its whole argument list,
     * so the methods of an anonymous class passed as an argument sit inside one — under their own
     * {@code meta.method} scope, which is nearer and wins.
     */
    static String kindForScopes(List<String> scopes) {
        if (scopes == null) {
            return null;
        }
        for (int i = scopes.size() - 1; i >= 0; i--) {
            ScopeInfo info = scopeInfo(scopes.get(i));
            if (info.call()) {
                return null;
            }
            if (info.meta()) {
                break; // a declaration's own scope is nearer than any enclosing call
            }
        }
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String kind = scopeInfo(scopes.get(i)).kind();
            if (kind != null) {
                return kind;
            }
        }
        return null;
    }

    /** The definition kind a single scope names, or {@code null} — see {@link #kindForScopes}. */
    private static String definitionKind(String scope) {
        if (!scope.startsWith("entity.name.")) {
            return null;
        }
        if (scope.startsWith("entity.name.function")) {
            return "function";
        }
        if (scope.startsWith("entity.name.type") || scope.startsWith("entity.name.class")) {
            return "type";
        }
        if (scope.startsWith("entity.name.namespace")) {
            return "namespace";
        }
        if (scope.startsWith("entity.name.section")) {
            return "section";
        }
        if (scope.startsWith("entity.name.tag")) {
            return "tag";
        }
        return null;
    }

    /**
     * Everything the highlighter derives from one scope name: its style class ({@code null} = none of
     * ours, {@link #PLAIN} = decides the token as unstyled), whether it marks a call, whether it is a
     * structural {@code meta.*} scope, and the definition kind it names.
     */
    private record ScopeInfo(String style, boolean call, boolean meta, String kind) {}

    /** Bound on {@link #SCOPE_INFO}; the bundled grammars together name a few thousand scopes. */
    private static final int SCOPE_CACHE_LIMIT = 16_384;

    /**
     * {@link ScopeInfo} per scope name. Every token carries a stack of scopes and each one used to be run
     * through some forty-five {@code startsWith} tests, twice (style, then outline kind), on every pass;
     * the set of distinct names is small and fixed by the grammars, so the answers are computed once.
     * Bounded because a grammar can build a scope name from captured text: past the limit new names are
     * simply classified without being remembered.
     */
    private static final ConcurrentHashMap<String, ScopeInfo> SCOPE_INFO = new ConcurrentHashMap<>();

    private static ScopeInfo scopeInfo(String scope) {
        ScopeInfo info = SCOPE_INFO.get(scope);
        if (info == null) {
            info = new ScopeInfo(classify(scope), isCallScope(scope), scope.startsWith("meta."), definitionKind(scope));
            if (SCOPE_INFO.size() < SCOPE_CACHE_LIMIT) {
                SCOPE_INFO.put(scope, info);
            }
        }
        return info;
    }

    /**
     * Whether {@code scope} marks a call (or another non-declaring use) of a function. The bundled grammars
     * spell this two ways. An enclosing {@code meta} scope: {@code meta.function-call} (Java, C,
     * TypeScript, Python, Ruby, PHP), {@code meta.method-call} (Java, Groovy, PHP),
     * {@code meta.function.call} and the macro invocation {@code meta.macro.rust} (Rust). Or a suffix on
     * the name scope itself: {@code .call} (C++, Kotlin), {@code .member} (C, C++), {@code .support} (Go),
     * {@code .reference} (Kotlin), {@code .tagged-template} (TypeScript), {@code .decorator} (Python) and
     * {@code .macro.rules}, which is Rust's {@code macro_rules!} keyword rather than the macro's name.
     */
    static boolean isCallScope(String scope) {
        if (scope.startsWith("meta.")) {
            return scope.startsWith("meta.function-call")
                    || scope.startsWith("meta.method-call")
                    || scope.startsWith("meta.function.call")
                    || scope.equals("meta.macro.rust");
        }
        if (!scope.startsWith("entity.name.function.")) {
            return false;
        }
        String kind = scope.substring("entity.name.function.".length());
        return kind.startsWith("call.")
                || kind.startsWith("member.")
                || kind.startsWith("support.")
                || kind.startsWith("reference.")
                || kind.startsWith("tagged-template.")
                || kind.startsWith("decorator.")
                || kind.startsWith("macro.rules.");
    }

    private static void addLineSpans(SpanMerger spans, int lineLength, IToken[] tokens, int count) {
        int last = 0;
        for (int i = 0; i < count; i++) {
            IToken token = tokens[i];
            int start = Math.min(token.getStartIndex(), lineLength);
            int end = Math.min(token.getEndIndex(), lineLength); // clamp the synthetic '\n' away
            if (end <= start) {
                continue;
            }
            if (start > last) {
                spans.add(null, start - last);
            }
            spans.add(styleForScopes(token.getScopes()), end - start);
            last = end;
        }
        if (lineLength > last) {
            spans.add(null, lineLength - last);
        }
    }

    /**
     * Accumulates style spans, coalescing consecutive runs that share the same style class (including
     * runs with no style) into a single span before handing them to the {@link StyleSpansBuilder}.
     */
    private static final class SpanMerger {
        private final StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        private final LineHook hook;
        private String current; // style class of the open run, or null for unstyled
        private int length; // accumulated length of the open run
        private int lineChars; // characters added since the last endLine()
        private boolean any;

        SpanMerger(LineHook hook) {
            this.hook = hook;
        }

        /** Characters added to the line in progress — what a line that failed part-way still has to cover. */
        int lineProgress() {
            return lineChars;
        }

        void endLine() {
            lineChars = 0;
        }

        /** Append {@code len} characters styled with {@code style} (null = unstyled). */
        void add(String style, int len) {
            if (len <= 0) {
                return;
            }
            lineChars += len;
            if (hook != null) {
                hook.run(style, len);
            }
            if (length == 0) {
                current = style;
                length = len;
            } else if (java.util.Objects.equals(style, current)) {
                length += len;
            } else {
                flush();
                current = style;
                length = len;
            }
        }

        private void flush() {
            if (length > 0) {
                builder.add(current == null ? Collections.emptyList() : styleList(current), length);
                length = 0;
                any = true;
            }
        }

        /** The spans, or {@code null} when nothing was added (RichTextFX cannot build zero-length spans). */
        StyleSpans<Collection<String>> build() {
            flush();
            return any ? builder.create() : null;
        }
    }

    /**
     * Reduces a TextMate scope list (least-specific first) to one CSS style class, or {@code null}
     * for unstyled text. The most specific scope wins, so we scan from the end.
     */
    static String styleForScopes(List<String> scopes) {
        if (scopes == null) {
            return null;
        }
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String style = scopeInfo(scopes.get(i)).style();
            if (style != null) {
                return style.isEmpty() ? null : style; // PLAIN
            }
        }
        return null;
    }

    /** One shared single-class style list per class, instead of a fresh {@code List.of} for every span. */
    private static final ConcurrentHashMap<String, List<String>> STYLE_LISTS = new ConcurrentHashMap<>();

    static List<String> styleList(String style) {
        return STYLE_LISTS.computeIfAbsent(style, List::of);
    }

    /** {@link #classify}'s answer for "this scope decides the token, and the token is unstyled". */
    private static final String PLAIN = "";

    /**
     * The style for a {@code storage.*} scope. Nearly every grammar reserves {@code storage} for declaration
     * keywords and modifiers ({@code class}, {@code const}, {@code static}, {@code fn}, primitive type words),
     * which is why it defaults to {@code keyword}. Java and Groovy are the exception: they scope every
     * <em>referenced type name</em> as {@code storage.type.<lang>} / {@code storage.type.generic} /
     * {@code storage.type.object.array} and the whole dotted path of an {@code import}/{@code package} line
     * as {@code storage.modifier.import|package}. Left as keywords those turn a Java file into a wall of the
     * bold keyword colour, so type names take the theme's {@code type} class and import paths stay plain
     * (the themes have no namespace class) — the same special-casing VS Code's own themes apply. Primitive
     * type words ({@code int}, {@code boolean[]}), {@code var}/{@code def} and {@code ->} stay keywords.
     */
    private static String classifyStorage(String scope) {
        if (scope.startsWith("storage.modifier.import.") || scope.startsWith("storage.modifier.package.")) {
            return PLAIN;
        }
        if (scope.endsWith(".java") || scope.endsWith(".groovy")) {
            if (scope.equals("storage.type.java")
                    || scope.equals("storage.type.groovy")
                    || scope.startsWith("storage.type.generic.")
                    || scope.startsWith("storage.type.object.array.")
                    || scope.startsWith("storage.type.parameters.")) {
                return "type";
            }
        }
        return "keyword";
    }

    /** Maps a single TextMate scope to a token category, checking the most specific prefixes first. */
    private static String classify(String scope) {
        if (scope.startsWith("comment")) {
            return "comment";
        }
        if (scope.startsWith("constant.numeric")) {
            return "number";
        }
        if (scope.startsWith("constant.character.escape") || scope.startsWith("constant.other.placeholder")) {
            return "escape";
        }
        if (scope.startsWith("constant.language")) {
            return "constant";
        }
        if (scope.startsWith("string.regexp")) {
            return "regexp";
        }
        if (scope.startsWith("string")) {
            return "string";
        }
        // Annotations/decorators before generic storage/keyword rules.
        // …but not a TypeScript/Python *type* annotation: meta.type.annotation.ts and the ":" / "->" that
        // introduce one are not decorators.
        if ((scope.contains("annotation")
                        && !scope.startsWith("meta.type.annotation")
                        && !scope.startsWith("keyword.operator.type.annotation")
                        && !scope.startsWith("punctuation.separator.annotation"))
                || scope.startsWith("meta.decorator")
                || scope.startsWith("entity.name.function.decorator")) {
            return "annotation";
        }
        if (scope.startsWith("keyword.operator")) {
            return "operator";
        }
        if (scope.startsWith("keyword")) {
            return "keyword";
        }
        if (scope.startsWith("storage")) {
            return classifyStorage(scope);
        }
        // Only the callee is a function name. Python has no entity scope for it, just
        // meta.function-call.generic; the enclosing meta.function-call / …arguments scopes cover the whole
        // call, and styling by them coloured every plain argument, comma and parenthesis as a function.
        if (scope.startsWith("entity.name.function")
                || scope.startsWith("support.function")
                || scope.startsWith("meta.function-call.generic")) {
            return "function";
        }
        if (scope.startsWith("entity.name.tag")) {
            return "tag";
        }
        if (scope.startsWith("entity.other.attribute-name")) {
            return "attribute";
        }
        if (scope.startsWith("entity.name.section") || scope.startsWith("markup.heading")) {
            return "heading";
        }
        if (scope.startsWith("entity.name.type")
                || scope.startsWith("entity.name.class")
                || scope.startsWith("entity.name.namespace")
                || scope.startsWith("entity.other.inherited-class")
                || scope.startsWith("support.type")
                || scope.startsWith("support.class")) {
            return "type";
        }
        if (scope.startsWith("variable.language")) {
            return "keyword";
        }
        if (scope.startsWith("support.constant")) {
            return "constant";
        }
        if (scope.startsWith("variable")) {
            return "variable";
        }
        if (scope.startsWith("support")) {
            return "type";
        }
        // CSV/TSV field separators (only the bundled source.csv grammar emits this scope) — a muted tint so
        // the column boundaries stand out from the field text.
        if (scope.startsWith("punctuation.separator.field.csv")) {
            return "csv-delimiter";
        }
        // Log-viewer levels + timestamps (only the bundled source.log grammar emits these scopes).
        if (scope.startsWith("markup.error") || scope.startsWith("markup.fatal")) {
            return "log-error";
        }
        if (scope.startsWith("markup.warning")) {
            return "log-warn";
        }
        if (scope.startsWith("markup.info")) {
            return "log-info";
        }
        if (scope.startsWith("markup.debug")) {
            return "log-debug";
        }
        if (scope.startsWith("markup.trace")) {
            return "log-trace";
        }
        if (scope.startsWith("constant.other.timestamp")) {
            return "log-timestamp";
        }
        // Unified-diff lines (only the bundled source.diff grammar emits these scopes): added/removed
        // lines tint green/red, hunk ranges + file headers stand out from the unchanged context lines.
        if (scope.startsWith("markup.inserted")) {
            return "diff-inserted";
        }
        if (scope.startsWith("markup.deleted")) {
            return "diff-deleted";
        }
        if (scope.startsWith("markup.changed")) {
            return "diff-changed";
        }
        if (scope.startsWith("meta.diff.range")) {
            return "diff-range";
        }
        if (scope.startsWith("meta.diff") || scope.startsWith("meta.separator.diff")) {
            return "diff-header";
        }
        if (scope.startsWith("markup.bold")) {
            return "bold";
        }
        if (scope.startsWith("markup.italic")) {
            return "italic";
        }
        if (scope.startsWith("markup.underline.link")
                || scope.startsWith("markup.link")
                || scope.startsWith("string.other.link")) {
            return "link";
        }
        if (scope.startsWith("markup.inline.raw")
                || scope.startsWith("markup.raw")
                || scope.startsWith("markup.fenced_code")) {
            return "code";
        }
        if (scope.startsWith("invalid")) {
            return "invalid";
        }
        return null;
    }
}
