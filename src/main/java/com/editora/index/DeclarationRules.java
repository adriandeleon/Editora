package com.editora.index;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The per-language patterns {@link DeclarationScanner} matches declarations with.
 *
 * <p><b>Why regexes and not the TextMate grammars.</b> Editora already ships a grammar per language and
 * they carry {@code entity.name.*} scopes that name declarations precisely, which looks like the obvious
 * source. It is not usable here: tm4e grammars are <em>not</em> thread-safe and the registry is shared
 * with the editor's own background highlighters, so an indexer walking a project would race them —
 * the same hazard {@code pdf/CodeHtml} documents, where tokenizing on the FX thread deadlocked the suite.
 * Serialising the index behind the highlighters would make it as slow as opening every file. Regexes cost
 * nothing, hold no state, and run on any thread.
 *
 * <p><b>These are heuristics and are meant to under-report.</b> A missing declaration costs the user one
 * fallback to search; an invented one sends them somewhere that does not exist and teaches them not to
 * trust the feature. Every rule here is written to prefer silence over a guess — which is also why a
 * language server always wins where one is running.
 *
 * <p>Each pattern must expose a group named {@code n} holding the declared identifier.
 */
final class DeclarationRules {

    private DeclarationRules() {}

    /**
     * One pattern, what a match of it declares, and whether the line still has to <em>look</em> like a
     * signature to count.
     *
     * <p>That last part is only needed where the pattern has no declaring keyword to anchor on — Java and
     * C, where {@code foo(bar);} and a method signature are the same shape. Everywhere else the keyword
     * ({@code def}, {@code fn}, {@code func}, {@code function}) is the discriminator, and demanding a
     * signature shape on top of it just rejects real declarations: Python's {@code def render(self):}
     * ends in a colon and Ruby's {@code def run!} ends in the name itself.
     */
    record Rule(Pattern pattern, SymbolKind kind, boolean needsSignatureShape) {}

    /** Identifiers that a call-shaped pattern would otherwise mistake for a declaration. */
    static final java.util.Set<String> CONTROL_KEYWORDS = java.util.Set.of(
            "if",
            "for",
            "while",
            "switch",
            "catch",
            "return",
            "new",
            "do",
            "else",
            "try",
            "synchronized",
            "assert",
            "throw",
            "super",
            "this",
            "match",
            "when",
            "with",
            "case",
            "select",
            "defer",
            "go");

    private static Rule rule(String regex, SymbolKind kind) {
        return new Rule(Pattern.compile(regex), kind, false);
    }

    /** A rule whose pattern has no declaring keyword, so the line's shape must confirm it. */
    private static Rule signatureRule(String regex, SymbolKind kind) {
        return new Rule(Pattern.compile(regex), kind, true);
    }

    /** An identifier as most C-family languages spell it. */
    private static final String ID = "[A-Za-z_$][A-Za-z0-9_$]*";

    /**
     * A Java/C-family member declaration. Conservative by construction: the line must look like a
     * signature — an optional modifier run, a return type, the name, then an open paren — and the caller
     * additionally requires the line to end like a declaration rather than like a call. Without that
     * second half every {@code foo(bar);} in a method body reads as a declaration of {@code foo}.
     */
    private static final String JAVA_MEMBER = "^\\s*(?:@" + ID + "(?:\\([^)]*\\))?\\s+)*"
            + "(?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|default|transient|volatile)\\s+)*"
            + "(?:<[^>]*>\\s*)?"
            + "[A-Za-z_$][A-Za-z0-9_$.<>\\[\\], ?]*\\s+"
            + "(?<n>" + ID + ")\\s*\\(";

    /**
     * A constructor — the one member with no return type, so {@link #JAVA_MEMBER} cannot find it. An
     * access modifier and a capitalised name are required, which is what keeps this off every call in a
     * method body; a package-private constructor is missed, and that is the intended trade.
     */
    private static final String JAVA_CTOR = "^\\s*(?:@" + ID + "(?:\\([^)]*\\))?\\s+)*"
            + "(?:(?:public|protected|private)\\s+)+(?<n>[A-Z][A-Za-z0-9_$]*)\\s*\\(";

    private static final List<Rule> JAVA = List.of(
            rule("^\\s*package\\s+(?<n>[\\w.]+)", SymbolKind.MODULE),
            rule("(?:^|\\s)(?:class|record)\\s+(?<n>" + ID + ")", SymbolKind.TYPE),
            rule("(?:^|\\s)interface\\s+(?<n>" + ID + ")", SymbolKind.INTERFACE),
            rule("(?:^|\\s)enum\\s+(?<n>" + ID + ")", SymbolKind.ENUM),
            signatureRule(JAVA_MEMBER, SymbolKind.METHOD),
            signatureRule(JAVA_CTOR, SymbolKind.METHOD),
            // A field: a typed name that is assigned or declared, with a modifier to keep it off local
            // variables inside method bodies (which brace depth alone cannot distinguish cheaply).
            rule(
                    "^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)+"
                            + "[A-Za-z_$][A-Za-z0-9_$.<>\\[\\], ?]*\\s+(?<n>" + ID + ")\\s*(?:=|;)",
                    SymbolKind.FIELD));

    private static final List<Rule> KOTLIN = List.of(
            rule("^\\s*package\\s+(?<n>[\\w.]+)", SymbolKind.MODULE),
            rule("(?:^|\\s)(?:class|object)\\s+(?<n>" + ID + ")", SymbolKind.TYPE),
            rule("(?:^|\\s)interface\\s+(?<n>" + ID + ")", SymbolKind.INTERFACE),
            rule("(?:^|\\s)enum\\s+class\\s+(?<n>" + ID + ")", SymbolKind.ENUM),
            rule("(?:^|\\s)fun\\s+(?:<[^>]*>\\s*)?(?:" + ID + "\\.)?(?<n>" + ID + ")", SymbolKind.METHOD),
            rule("^\\s*(?:va[lr])\\s+(?<n>" + ID + ")", SymbolKind.FIELD));

    private static final List<Rule> PYTHON = List.of(
            rule("^\\s*class\\s+(?<n>\\w+)", SymbolKind.TYPE),
            rule("^\\s*(?:async\\s+)?def\\s+(?<n>\\w+)", SymbolKind.METHOD),
            rule("^(?<n>[A-Z][A-Z0-9_]*)\\s*(?::[^=]+)?=", SymbolKind.VARIABLE));

    private static final List<Rule> JS = List.of(
            rule("(?:^|\\s)class\\s+(?<n>" + ID + ")", SymbolKind.TYPE),
            rule("(?:^|\\s)interface\\s+(?<n>" + ID + ")", SymbolKind.INTERFACE),
            rule("(?:^|\\s)enum\\s+(?<n>" + ID + ")", SymbolKind.ENUM),
            rule("(?:^|\\s)type\\s+(?<n>" + ID + ")\\s*=", SymbolKind.TYPE),
            rule("(?:^|\\s)(?:async\\s+)?function\\s*\\*?\\s*(?<n>" + ID + ")", SymbolKind.FUNCTION),
            // `const foo = () => …` / `const foo = function …` — the modern declaration form.
            rule(
                    "^\\s*(?:export\\s+)?(?:const|let|var)\\s+(?<n>" + ID
                            + ")\\s*=\\s*(?:async\\s*)?(?:function\\b|\\([^)]*\\)\\s*=>|" + ID + "\\s*=>)",
                    SymbolKind.FUNCTION),
            rule("^\\s*(?:export\\s+)?(?:const|let|var)\\s+(?<n>" + ID + ")\\s*=", SymbolKind.VARIABLE));

    private static final List<Rule> GO = List.of(
            rule("^\\s*package\\s+(?<n>\\w+)", SymbolKind.MODULE),
            // A method carries its receiver in parens before the name.
            rule("^\\s*func\\s+\\([^)]*\\)\\s*(?<n>\\w+)", SymbolKind.METHOD),
            rule("^\\s*func\\s+(?<n>\\w+)", SymbolKind.FUNCTION),
            rule("(?:^|\\s)type\\s+(?<n>\\w+)\\s+interface\\b", SymbolKind.INTERFACE),
            rule("(?:^|\\s)type\\s+(?<n>\\w+)", SymbolKind.TYPE),
            rule("^\\s*(?:const|var)\\s+(?<n>\\w+)", SymbolKind.VARIABLE));

    private static final List<Rule> RUST = List.of(
            rule("^\\s*mod\\s+(?<n>\\w+)", SymbolKind.MODULE),
            rule("(?:^|\\s)struct\\s+(?<n>\\w+)", SymbolKind.TYPE),
            rule("(?:^|\\s)enum\\s+(?<n>\\w+)", SymbolKind.ENUM),
            rule("(?:^|\\s)trait\\s+(?<n>\\w+)", SymbolKind.INTERFACE),
            rule("(?:^|\\s)type\\s+(?<n>\\w+)", SymbolKind.TYPE),
            rule("(?:^|\\s)fn\\s+(?<n>\\w+)", SymbolKind.FUNCTION),
            rule("^\\s*(?:pub\\s+)?(?:const|static)\\s+(?<n>\\w+)", SymbolKind.VARIABLE));

    private static final List<Rule> C = List.of(
            rule("(?:^|\\s)(?:struct|union|class)\\s+(?<n>" + ID + ")\\s*[{:]", SymbolKind.TYPE),
            rule("(?:^|\\s)enum\\s+(?:class\\s+)?(?<n>" + ID + ")", SymbolKind.ENUM),
            rule("(?:^|\\s)namespace\\s+(?<n>" + ID + ")", SymbolKind.MODULE),
            rule("^\\s*typedef\\s+.*\\s(?<n>" + ID + ")\\s*;", SymbolKind.TYPE),
            signatureRule(JAVA_MEMBER, SymbolKind.FUNCTION));

    private static final List<Rule> RUBY = List.of(
            rule("^\\s*module\\s+(?<n>\\w+)", SymbolKind.MODULE),
            rule("^\\s*class\\s+(?<n>[\\w:]+)", SymbolKind.TYPE),
            rule("^\\s*def\\s+(?:self\\.)?(?<n>[\\w?!=\\[\\]]+)", SymbolKind.METHOD));

    private static final List<Rule> PHP = List.of(
            rule("^\\s*namespace\\s+(?<n>[\\w\\\\]+)", SymbolKind.MODULE),
            rule("(?:^|\\s)class\\s+(?<n>\\w+)", SymbolKind.TYPE),
            rule("(?:^|\\s)(?:interface|trait)\\s+(?<n>\\w+)", SymbolKind.INTERFACE),
            rule("(?:^|\\s)function\\s+&?\\s*(?<n>\\w+)", SymbolKind.METHOD));

    private static final List<Rule> SHELL = List.of(
            rule("^\\s*(?:function\\s+)?(?<n>[\\w-]+)\\s*\\(\\s*\\)", SymbolKind.FUNCTION),
            rule("^\\s*function\\s+(?<n>[\\w-]+)", SymbolKind.FUNCTION));

    private static final List<Rule> LUA = List.of(
            rule("^\\s*(?:local\\s+)?function\\s+(?<n>[\\w.:]+)", SymbolKind.FUNCTION),
            rule("^\\s*(?<n>[\\w.]+)\\s*=\\s*function", SymbolKind.FUNCTION));

    /** Language id (as {@code editor/LanguageRegistry} reports it) to its rules. */
    private static final Map<String, List<Rule>> BY_LANGUAGE = Map.ofEntries(
            Map.entry("java", JAVA),
            Map.entry("kotlin", KOTLIN),
            Map.entry("python", PYTHON),
            Map.entry("javascript", JS),
            Map.entry("javascriptreact", JS),
            Map.entry("typescript", JS),
            Map.entry("typescriptreact", JS),
            Map.entry("go", GO),
            Map.entry("rust", RUST),
            Map.entry("c", C),
            Map.entry("cpp", C),
            Map.entry("csharp", JAVA),
            Map.entry("ruby", RUBY),
            Map.entry("php", PHP),
            Map.entry("shell", SHELL),
            Map.entry("lua", LUA));

    /** The rules for {@code language}, or an empty list when it has none (the scanner then finds nothing). */
    static List<Rule> forLanguage(String language) {
        return BY_LANGUAGE.getOrDefault(language == null ? "" : language, List.of());
    }

    /** The language ids this scanner knows how to read. */
    static java.util.Set<String> supportedLanguages() {
        return BY_LANGUAGE.keySet();
    }

    /** True when the language's members live inside braces, so a container can be tracked by depth. */
    static boolean braceScoped(String language) {
        return !("python".equals(language) || "ruby".equals(language) || "shell".equals(language));
    }
}
