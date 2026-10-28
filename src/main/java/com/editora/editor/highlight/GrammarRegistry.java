package com.editora.editor.highlight;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.text.ConfigFileType;
import com.editora.text.LanguageRegistry;
import com.editora.text.ShellFiles;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.IRegistryOptions;
import org.eclipse.tm4e.core.registry.Registry;
import org.jspecify.annotations.Nullable;

/**
 * Maps file extensions to bundled TextMate grammars and tokenizes through the Eclipse tm4e engine.
 *
 * <p>Grammars live as {@code .tmLanguage.json} resources under {@code /com/editora/grammars/}. A
 * single shared {@link Registry} resolves a grammar (and any grammars it embeds, e.g. C inside C++)
 * by scope name via {@link IRegistryOptions#getGrammarSource(String)}. Loaded grammars are cached;
 * a scope that fails to load is remembered so we don't retry it on every keystroke.
 *
 * <p><b>Lookups never wait for a load.</b> The name tables are filled in the constructor and only read
 * afterwards, and the grammar cache is a concurrent map, so asking whether a file type has a grammar, or
 * for one that is already loaded, takes no lock — the FX thread asks exactly that when a tab is given its
 * path, and used to stall there for as long as another tab's first grammar of the session took to compile
 * on a pool thread. Only the load itself is serialized, under {@link #loadLock}, and each grammar is still
 * loaded once. Loads are serialized with one another rather than per scope because the tm4e
 * {@link Registry} they share keeps its grammars in plain maps.
 */
public final class GrammarRegistry {

    private static final String RESOURCE_DIR = "/com/editora/grammars/";
    /**
     * tm4e's loggers silenced below, held: JUL keeps a logger only weakly, so one collected before tm4e first asked
     * for it came back at the default level, and 20–64 "character class has ']' without escape" warnings per run
     * reached the Debug Log from the bundled grammars (twelfth review CL5).
     */
    private static final List<Logger> QUIET_LOGGERS = List.of(
            Logger.getLogger("org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp"),
            Logger.getLogger("org.eclipse.tm4e.core.internal.grammar.raw.RawCaptures"));

    // after QUIET_LOGGERS, which its constructor reads
    private static final GrammarRegistry INSTANCE = new GrammarRegistry();

    /** TextMate scope name -> grammar resource base name (without {@code .tmLanguage.json}). */
    private final Map<String, String> scopeToResource = new HashMap<>();
    /** Lower-case file extension -> TextMate scope name. */
    private final Map<String, String> extensionToScope = new HashMap<>();
    /** Language name (== resource base name, e.g. {@code "java"}) -> TextMate scope name. */
    private final Map<String, String> languageNameToScope = new HashMap<>();

    private final Registry registry;
    private final Map<String, IGrammar> grammarCache = new ConcurrentHashMap<>();
    private final Set<String> failedScopes = ConcurrentHashMap.newKeySet();
    /** Held while a grammar is being loaded; never by a lookup of one that already is. */
    final Object loadLock = new Object();

    private GrammarRegistry() {
        // tm4e logs verbose WARNING/SEVERE messages for grammar quirks it can tolerate or that we
        // handle ourselves (see TextMateHighlighter): the Oniguruma backend warns for nearly every
        // regex using Oniguruma-specific syntax (e.g. an unescaped ']'), and the rule parser logs
        // each unparseable rule/capture. Silence these to keep the console usable.
        QUIET_LOGGERS.forEach(l -> l.setLevel(Level.OFF));
        registerGrammars();
        // The resource base name doubles as the language name (see LanguageRegistry), so the
        // inverse of scopeToResource resolves a language name to its scope.
        scopeToResource.forEach((scope, resource) -> languageNameToScope.put(resource, scope));
        languageNameToScope.remove("html-derivative"); // only ever embedded; not a language to pick for a file
        languageNameToScope.remove("javadoc");
        registry = new Registry(new IRegistryOptions() {
            @Override
            public @Nullable IGrammarSource getGrammarSource(String scopeName) {
                String resource = scopeToResource.get(scopeName);
                if (resource == null) {
                    return null;
                }
                return IGrammarSource.fromResource(GrammarRegistry.class, RESOURCE_DIR + resource + ".tmLanguage.json");
            }
        });
    }

    public static GrammarRegistry shared() {
        return INSTANCE;
    }

    /** The grammar for {@code fileName}'s extension, or {@code null} if none is bundled. */
    public @Nullable IGrammar forFileName(String fileName) {
        String scope = scopeForFileName(fileName);
        return scope == null ? null : grammarForScope(scope);
    }

    /** Whether {@code fileName}'s extension maps to a bundled grammar at all (regardless of load state). */
    public boolean hasGrammarFor(String fileName) {
        return scopeForFileName(fileName) != null;
    }

    /**
     * The grammar for {@code fileName} <em>only if already loaded</em> — never triggers a (possibly slow)
     * Oniguruma compile. Returns {@code null} when not bundled <em>or</em> not yet loaded, so a caller can
     * apply a cached grammar instantly and otherwise load it off-thread (see {@code hasGrammarFor}).
     */
    public @Nullable IGrammar cachedForFileName(String fileName) {
        String scope = scopeForFileName(fileName);
        return scope == null ? null : grammarCache.get(scope);
    }

    /**
     * The grammar for a language name (e.g. {@code "java"}, as produced by {@link LanguageRegistry}),
     * or {@code null} if no grammar is bundled for it.
     */
    public @Nullable IGrammar forLanguageName(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String scope = languageNameToScope.getOrDefault(lower, LANGUAGE_ALIASES.get(lower));
        return scope == null ? null : grammarForScope(scope);
    }

    /**
     * Languages with no grammar file of their own, highlighted by another language's grammar: JavaScript reuses
     * TSX (see the extension table). They are languages a person picks, so {@link #pickableLanguageNames} lists
     * them — without them a .js buffer's status-bar picker showed Plain Text and offered only TypeScript.
     */
    private static final Map<String, String> LANGUAGE_ALIASES =
            Map.of("javascript", "source.tsx", "javascriptreact", "source.tsx");

    /** Language names that have a bundled grammar file (the grammar's resource name), sorted alphabetically. */
    public Set<String> availableLanguageNames() {
        return new TreeSet<>(languageNameToScope.keySet());
    }

    /** {@link #availableLanguageNames} plus the languages that reuse another's grammar: what a picker offers. */
    public Set<String> pickableLanguageNames() {
        Set<String> names = availableLanguageNames();
        names.addAll(LANGUAGE_ALIASES.keySet());
        return names;
    }

    private @Nullable IGrammar grammarForScope(String scope) {
        IGrammar cached = grammarCache.get(scope);
        if (cached != null || failedScopes.contains(scope)) {
            return cached;
        }
        synchronized (loadLock) {
            cached = grammarCache.get(scope); // loaded while this thread waited for the lock
            if (cached != null || failedScopes.contains(scope)) {
                return cached;
            }
            try {
                IGrammar grammar = registry.loadGrammar(scope);
                if (grammar != null) {
                    grammarCache.put(scope, grammar);
                    return grammar;
                }
            } catch (Exception | LinkageError e) {
                // Malformed grammar, missing resource, or engine error — fall back to no highlighting.
            }
            failedScopes.add(scope);
            return null;
        }
    }

    private @Nullable String scopeForFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        // Name/location-determined config files (Dockerfile, dotenv, ssh/git config, hosts, ...).
        String special = ConfigFileType.resolve(fileName);
        if (special != null) {
            return languageNameToScope.get(special);
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return null;
        }
        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        return com.editora.text.LanguageRegistry.isGoModule(base) ? null : extensionToScope.get(ext);
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
        scopeToResource.put("source.scala", "scala");
        scopeToResource.put("source.clojure", "clojure");
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
        // The flavour of HTML other grammars embed: Markdown includes it for raw HTML, and without it every
        // tag in a Markdown file was plain text (and one of the grammar's HTML block rules was dropped at load).
        scopeToResource.put("text.html.derivative", "html-derivative");
        // the Javadoc comment Groovy's grammar includes: without it tm4e warned twice per Gradle build file opened, and
        // a /** */ comment had no documentation scopes (thirteenth review CK4)
        scopeToResource.put("text.html.javadoc", "javadoc");
        scopeToResource.put("source.astro", "astro");
        // Astro embeds JavaScript and stylesheet dialect scopes. Reuse the bundled TypeScript/CSS
        // grammars for aliases we can faithfully tokenize; unsupported preprocessors degrade to plain text.
        scopeToResource.put("source.js", "typescript");
        scopeToResource.put("source.css.less", "css");
        scopeToResource.put("source.css.postcss", "css");
        scopeToResource.put("source.css.scss", "css");
        scopeToResource.put("source.yaml", "yaml");
        scopeToResource.put("source.ini", "ini");
        scopeToResource.put("source.sql", "sql");
        scopeToResource.put("source.mermaid", "mermaid");
        scopeToResource.put("source.markwhen", "markwhen");
        scopeToResource.put("source.dot", "dot");
        scopeToResource.put("source.plantuml", "plantuml");
        scopeToResource.put("source.typst", "typst");
        scopeToResource.put("source.ts", "typescript");
        scopeToResource.put("source.tsx", "typescriptreact");
        scopeToResource.put("source.php", "php");
        scopeToResource.put("source.lua", "lua");
        scopeToResource.put("source.dockerfile", "dockerfile");
        scopeToResource.put("source.hcl.terraform", "terraform");
        scopeToResource.put("source.toml", "toml");
        scopeToResource.put("source.http", "http");
        scopeToResource.put("source.csv", "csv");
        scopeToResource.put("source.systemd", "systemd");
        scopeToResource.put("source.desktop", "desktop");
        scopeToResource.put("source.dotenv", "dotenv");
        scopeToResource.put("source.ssh-config", "ssh-config");
        scopeToResource.put("source.git-config", "git-config");
        scopeToResource.put("text.crontab", "crontab");
        scopeToResource.put("source.Caddyfile", "caddyfile");
        scopeToResource.put("source.hosts", "hosts");
        scopeToResource.put("source.fstab", "fstab");
        scopeToResource.put("source.deb822", "deb822");
        scopeToResource.put("source.apt-sources", "apt-sources");
        scopeToResource.put("source.interfaces", "interfaces");
        scopeToResource.put("source.debian-changelog", "debian-changelog");
        scopeToResource.put("source.log", "log");
        scopeToResource.put("source.ignore", "ignore"); // .gitignore / .dockerignore / .npmignore / …
        scopeToResource.put("source.diff", "diff");
        scopeToResource.put("source.makefile", "makefile");
        scopeToResource.put("source.just", "just");
        scopeToResource.put("source.proto", "proto");
        scopeToResource.put("source.graphql", "graphql");
        scopeToResource.put("source.java-properties", "properties");
        scopeToResource.put("source.gitattributes", "gitattributes");
        scopeToResource.put("source.pip-requirements", "pip-requirements"); // in-house, by file name only

        // file extension -> scope name
        mapExtensions("source.java", "java");
        // XML dialects too, as LanguageRegistry lists them (XML review A8).
        mapExtensions(
                "text.xml",
                "xml",
                "xsd",
                "xsl",
                "xslt",
                "svg",
                "fxml",
                "pom",
                "rss",
                "wsdl",
                "dtd",
                "ent",
                "mod",
                "rng",
                "fo",
                "xspec",
                "sch",
                "csproj",
                "vbproj",
                "fsproj",
                "props",
                "targets",
                "nuspec",
                "resx",
                "xaml",
                "xlf",
                "xliff",
                "plist",
                "xul",
                "kml",
                "gpx",
                "tld",
                "jnlp",
                "xmi",
                "ditamap",
                "dita");
        mapExtensions("source.shell", ShellFiles.EXTENSIONS.toArray(String[]::new)); // the table LanguageRegistry reads
        mapExtensions("source.powershell", "ps1", "psm1", "psd1");
        mapExtensions("source.batchfile", "bat", "cmd");
        mapExtensions("source.python", "py", "pyw", "pyi");
        mapExtensions("source.groovy", "groovy", "gvy", "gradle");
        mapExtensions("source.kotlin", "kt", "kts");
        // Scala sources, Scala CLI / Ammonite scripts (.sc), sbt build files and Mill builds.
        mapExtensions("source.scala", "scala", "sc", "sbt", "mill");
        // Clojure, ClojureScript, shared .cljc (and the older .cljx), EDN data files and Babashka scripts.
        mapExtensions("source.clojure", "clj", "cljs", "cljc", "cljx", "edn", "bb"); // .bb: a Babashka script
        mapExtensions("source.ruby", "rb", "rake", "gemspec");
        mapExtensions("source.c", "c", "h");
        mapExtensions("source.cpp", "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ipp", "inl");
        mapExtensions("source.rust", "rs");
        mapExtensions("source.go", "go");
        mapExtensions("source.cs", "cs", "csx");
        mapExtensions("text.html.markdown", "md", "markdown", "mdown", "mkd", "mkdn", "mdwn", "mdx", "rmd", "qmd");
        mapExtensions("source.json", "json", "jsonc", "json5", "ipynb"); // a Jupyter notebook is JSON
        mapExtensions("source.css", "css");
        mapExtensions("text.html.basic", "html", "htm", "xhtml");
        mapExtensions("source.astro", "astro");
        mapExtensions("source.yaml", "yaml", "yml");
        mapExtensions("source.ini", "ini", "cfg", "conf");
        // Java .properties — its own grammar (Unicode escapes, ':' separator, continuation lines);
        // previously highlighted via the INI grammar.
        mapExtensions("source.java-properties", "properties");
        mapExtensions("source.sql", "sql", "ddl", "dml");
        mapExtensions("source.mermaid", "mmd", "mermaid");
        mapExtensions("source.markwhen", "mw", "markwhen");
        mapExtensions("source.dot", "dot", "gv");
        mapExtensions("source.plantuml", "puml", "plantuml", "pu", "iuml");
        mapExtensions("source.typst", "typ");
        // JavaScript reuses the TSX grammar, as VS Code does (its JavaScript grammar is generated from
        // TypeScriptReact): JSX is common in .js files, and the plain TS grammar reads `<p>` as a type cast,
        // so JSX text such as `Use /* sparingly` commented out the rest of the file. TypeScript keeps source.ts,
        // where `<T>x` is a cast.
        mapExtensions("source.ts", "ts", "mts", "cts");
        mapExtensions("source.tsx", "tsx", "jsx", "js", "mjs", "cjs");
        // PHP: source.php embeds text.html.basic/source.css/source.sql/source.json/text.xml (all bundled).
        mapExtensions("source.php", "php", "phtml", "php3", "php4", "php5", "phps");
        mapExtensions("source.lua", "lua");
        mapExtensions("source.dockerfile", "dockerfile"); // bare "Dockerfile" handled in scopeForFileName
        mapExtensions("source.hcl.terraform", "tf", "tfvars", "hcl");
        mapExtensions("source.toml", "toml", "tml");
        mapExtensions("source.http", "http", "rest");
        mapExtensions("source.csv", "csv", "tsv");
        // systemd unit files — each unit type is its own extension (.service/.socket/.timer/…),
        // plus systemd-networkd's .network/.netdev/.link and nspawn containers.
        mapExtensions(
                "source.systemd",
                "service",
                "socket",
                "device",
                "mount",
                "automount",
                "swap",
                "target",
                "path",
                "timer",
                "slice",
                "scope",
                "network",
                "netdev",
                "link",
                "nspawn");
        // XDG desktop entries (.desktop) and KDE .directory files.
        mapExtensions("source.desktop", "desktop", "directory");
        // Server/application log files.
        mapExtensions("source.log", "log");
        // Unified diffs / patches.
        mapExtensions("source.diff", "diff", "patch");
        // Makefiles — bare "Makefile"/"GNUmakefile" handled by ConfigFileType; these are the extensions.
        mapExtensions("source.makefile", "mk", "mak", "make");
        // just command-runner files — bare "justfile" handled by ConfigFileType.
        mapExtensions("source.just", "just");
        // Protocol Buffers.
        mapExtensions("source.proto", "proto");
        // GraphQL schemas/queries.
        mapExtensions("source.graphql", "graphql", "gql", "graphqls");
        // dotenv (.env / .env.*), Caddyfile, crontab (.cron), git/ssh config, fstab, hosts, Makefile,
        // justfile, and .gitattributes are all name/location-determined and handled by ConfigFileType
        // (see scopeForFileName), not by extension.
    }

    /** Every extension with a bundled grammar, mapped to the grammar's language name (its resource name). */
    public java.util.Map<String, String> extensionLanguages() {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        extensionToScope.forEach((ext, scope) -> out.put(ext, scopeToResource.get(scope)));
        return out;
    }

    private void mapExtensions(String scope, String... extensions) {
        for (String ext : extensions) {
            extensionToScope.put(ext, scope);
        }
    }
}
