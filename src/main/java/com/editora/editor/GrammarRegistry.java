package com.editora.editor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
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
        mapExtensions("source.ini", "ini", "cfg", "conf", "properties", "toml");
        mapExtensions("source.sql", "sql", "ddl", "dml");
    }

    private void mapExtensions(String scope, String... extensions) {
        for (String ext : extensions) {
            extensionToScope.put(ext, scope);
        }
    }
}
