package com.editora.editor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IRegistryOptions;
import org.eclipse.tm4e.core.registry.Registry;

/**
 * Maps file extensions to bundled TextMate grammars and tokenizes through the Eclipse tm4e engine.
 *
 * <p>Grammars live as {@code .tmLanguage.json} resources under {@code /com/editora/grammars/}. A
 * single shared {@link Registry} resolves a grammar (and any grammars it embeds, e.g. C inside C++)
 * by scope name via {@link IRegistryOptions#getGrammarSource(String)}. Loaded grammars are cached;
 * a scope that fails to load is remembered so we don't retry it on every keystroke.
 */
public final class GrammarRegistry {

    private static final String RESOURCE_DIR = "/com/editora/grammars/";
    private static final GrammarRegistry INSTANCE = new GrammarRegistry();

    /** TextMate scope name -> grammar resource base name (without {@code .tmLanguage.json}). */
    private final Map<String, String> scopeToResource = new HashMap<>();
    /** Lower-case file extension -> TextMate scope name. */
    private final Map<String, String> extensionToScope = new HashMap<>();
    /** Language name (== resource base name, e.g. {@code "java"}) -> TextMate scope name. */
    private final Map<String, String> languageNameToScope = new HashMap<>();

    private final Registry registry;
    private final Map<String, IGrammar> grammarCache = new HashMap<>();
    private final Set<String> failedScopes = new HashSet<>();

    private GrammarRegistry() {
        // tm4e logs verbose WARNING/SEVERE messages for grammar quirks it can tolerate or that we
        // handle ourselves (see TextMateHighlighter): the Oniguruma backend warns for nearly every
        // regex using Oniguruma-specific syntax (e.g. an unescaped ']'), and the rule parser logs
        // each unparseable rule/capture. Silence these to keep the console usable.
        Logger.getLogger("org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp").setLevel(Level.OFF);
        Logger.getLogger("org.eclipse.tm4e.core.internal.grammar.raw.RawCaptures").setLevel(Level.OFF);
        registerGrammars();
        // The resource base name doubles as the language name (see LanguageRegistry), so the
        // inverse of scopeToResource resolves a language name to its scope.
        scopeToResource.forEach((scope, resource) -> languageNameToScope.put(resource, scope));
        registry = new Registry(new IRegistryOptions() {
            @Override
            public IGrammarSource getGrammarSource(String scopeName) {
                String resource = scopeToResource.get(scopeName);
                if (resource == null) {
                    return null;
                }
                return IGrammarSource.fromResource(GrammarRegistry.class,
                        RESOURCE_DIR + resource + ".tmLanguage.json");
            }
        });
    }

    public static GrammarRegistry shared() {
        return INSTANCE;
    }

    /** The grammar for {@code fileName}'s extension, or {@code null} if none is bundled. */
    public synchronized IGrammar forFileName(String fileName) {
        String scope = scopeForFileName(fileName);
        return scope == null ? null : grammarForScope(scope);
    }

    /** Whether {@code fileName}'s extension maps to a bundled grammar at all (regardless of load state). */
    public synchronized boolean hasGrammarFor(String fileName) {
        return scopeForFileName(fileName) != null;
    }

    /**
     * The grammar for {@code fileName} <em>only if already loaded</em> — never triggers a (possibly slow)
     * Oniguruma compile. Returns {@code null} when not bundled <em>or</em> not yet loaded, so a caller can
     * apply a cached grammar instantly and otherwise load it off-thread (see {@code hasGrammarFor}).
     */
    public synchronized IGrammar cachedForFileName(String fileName) {
        String scope = scopeForFileName(fileName);
        return scope == null ? null : grammarCache.get(scope);
    }

    /**
     * The grammar for a language name (e.g. {@code "java"}, as produced by {@link LanguageRegistry}),
     * or {@code null} if no grammar is bundled for it.
     */
    public synchronized IGrammar forLanguageName(String name) {
        if (name == null) {
            return null;
        }
        String scope = languageNameToScope.get(name.toLowerCase(Locale.ROOT));
        return scope == null ? null : grammarForScope(scope);
    }

    /** Language names that have a bundled grammar, sorted alphabetically. */
    public synchronized Set<String> availableLanguageNames() {
        return new TreeSet<>(languageNameToScope.keySet());
    }

    private IGrammar grammarForScope(String scope) {
        if (grammarCache.containsKey(scope)) {
            return grammarCache.get(scope);
        }
        if (failedScopes.contains(scope)) {
            return null;
        }
        try {
            IGrammar grammar = registry.loadGrammar(scope);
            grammarCache.put(scope, grammar);
            return grammar;
        } catch (Exception | LinkageError e) {
            // Malformed grammar, missing resource, or engine error — fall back to no highlighting.
            failedScopes.add(scope);
            return null;
        }
    }

    private String scopeForFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String lower = base.toLowerCase(Locale.ROOT);
        // Dockerfile / Containerfile are extension-less (or carry a tag suffix, e.g. Dockerfile.dev).
        if (lower.equals("dockerfile") || lower.equals("containerfile")
                || lower.startsWith("dockerfile.") || lower.startsWith("containerfile.")) {
            return "source.dockerfile";
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return null;
        }
        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extensionToScope.get(ext);
    }

    private void registerGrammars() {
        // scope name -> resource base name
        scopeToResource.put("source.java", "java");
        scopeToResource.put("text.xml", "xml");
        scopeToResource.put("source.shell", "shell");
        scopeToResource.put("source.powershell", "powershell");
        scopeToResource.put("source.batchfile", "batchfile");
        scopeToResource.put("source.python", "python");
        scopeToResource.put("source.groovy", "groovy");
        scopeToResource.put("source.kotlin", "kotlin");
        scopeToResource.put("source.ruby", "ruby");
        scopeToResource.put("source.c", "c");
        scopeToResource.put("source.cpp", "cpp");
        scopeToResource.put("source.rust", "rust");
        scopeToResource.put("source.go", "go");
        scopeToResource.put("source.cs", "csharp");
        scopeToResource.put("text.html.markdown", "markdown");
        scopeToResource.put("source.json", "json");
        scopeToResource.put("source.css", "css");
        scopeToResource.put("text.html.basic", "html");
        scopeToResource.put("source.yaml", "yaml");
        scopeToResource.put("source.ini", "ini");
        scopeToResource.put("source.sql", "sql");
        scopeToResource.put("source.mermaid", "mermaid");
        scopeToResource.put("source.ts", "typescript");
        scopeToResource.put("source.tsx", "typescriptreact");
        scopeToResource.put("source.php", "php");
        scopeToResource.put("source.lua", "lua");
        scopeToResource.put("source.dockerfile", "dockerfile");
        scopeToResource.put("source.hcl.terraform", "terraform");
        scopeToResource.put("source.toml", "toml");

        // file extension -> scope name
        mapExtensions("source.java", "java");
        mapExtensions("text.xml", "xml", "xsd", "xsl", "xslt", "svg", "fxml", "pom", "rss", "wsdl");
        mapExtensions("source.shell", "sh", "bash", "zsh", "ksh", "bashrc", "zshrc", "profile");
        mapExtensions("source.powershell", "ps1", "psm1", "psd1");
        mapExtensions("source.batchfile", "bat", "cmd");
        mapExtensions("source.python", "py", "pyw", "pyi");
        mapExtensions("source.groovy", "groovy", "gvy", "gradle");
        mapExtensions("source.kotlin", "kt", "kts");
        mapExtensions("source.ruby", "rb", "rake", "gemspec");
        mapExtensions("source.c", "c", "h");
        mapExtensions("source.cpp", "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ipp", "inl");
        mapExtensions("source.rust", "rs");
        mapExtensions("source.go", "go");
        mapExtensions("source.cs", "cs", "csx");
        mapExtensions("text.html.markdown", "md", "markdown", "mdown", "mkd");
        mapExtensions("source.json", "json", "jsonc", "json5");
        mapExtensions("source.css", "css");
        mapExtensions("text.html.basic", "html", "htm", "xhtml");
        mapExtensions("source.yaml", "yaml", "yml");
        mapExtensions("source.ini", "ini", "cfg", "conf", "properties");
        mapExtensions("source.sql", "sql", "ddl", "dml");
        mapExtensions("source.mermaid", "mmd", "mermaid");
        // The TypeScript grammar tokenizes plain JS well, so .js/.mjs/.cjs reuse source.ts and
        // .jsx reuses the TSX (React) grammar — no separate JS grammars to bundle.
        mapExtensions("source.ts", "ts", "mts", "cts", "js", "mjs", "cjs");
        mapExtensions("source.tsx", "tsx", "jsx");
        // PHP: source.php embeds text.html.basic/source.css/source.sql/source.json/text.xml (all bundled).
        mapExtensions("source.php", "php", "phtml", "php3", "php4", "php5", "phps");
        mapExtensions("source.lua", "lua");
        mapExtensions("source.dockerfile", "dockerfile"); // bare "Dockerfile" handled in scopeForFileName
        mapExtensions("source.hcl.terraform", "tf", "tfvars", "hcl");
        mapExtensions("source.toml", "toml", "tml");
    }

    private void mapExtensions(String scope, String... extensions) {
        for (String ext : extensions) {
            extensionToScope.put(ext, scope);
        }
    }
}
