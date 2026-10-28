package com.editora.text;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a file name to a short language identifier. Syntax highlighting itself is handled by
 * {@code GrammarRegistry} (TextMate grammars via tm4e); this registry only supplies the language
 * <em>name</em> used by the fold engine ({@link FoldRegions}) to pick a folding strategy and by the
 * File Information panel for display. Unknown extensions resolve to {@code "plaintext"}.
 */
public final class LanguageRegistry {

    public static final String PLAINTEXT = "plaintext";

    /** Lower-case file extension -> language name. */
    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            // Markup (fold by element nesting / fenced sections).
            Map.entry("xml", "xml"),
            Map.entry("xsd", "xml"),
            Map.entry("xsl", "xml"),
            Map.entry("xslt", "xml"),
            Map.entry("svg", "xml"),
            Map.entry("fxml", "xml"),
            Map.entry("pom", "xml"),
            Map.entry("rss", "xml"),
            Map.entry("wsdl", "xml"),
            // XML dialects, so they reach the XML grammar and server (XML review A8): DTDs and their entity and
            // module files (lemminx tells a DTD by its URI), RELAX NG, XSLT-FO, XSpec and Schematron, MSBuild and
            // NuGet project files, .NET resources and XAML, XLIFF, property lists, Mozilla XUL, KML and GPX, JSP
            // tag libraries, Java Web Start, XMI and DITA. A go.mod is not a DTD module: see forFileName.
            Map.entry("dtd", "xml"),
            Map.entry("ent", "xml"),
            Map.entry("mod", "xml"),
            Map.entry("rng", "xml"),
            Map.entry("fo", "xml"),
            Map.entry("xspec", "xml"),
            Map.entry("sch", "xml"),
            Map.entry("csproj", "xml"),
            Map.entry("vbproj", "xml"),
            Map.entry("fsproj", "xml"),
            Map.entry("props", "xml"),
            Map.entry("targets", "xml"),
            Map.entry("nuspec", "xml"),
            Map.entry("resx", "xml"),
            Map.entry("xaml", "xml"),
            Map.entry("xlf", "xml"),
            Map.entry("xliff", "xml"),
            Map.entry("plist", "xml"),
            Map.entry("xul", "xml"),
            Map.entry("kml", "xml"),
            Map.entry("gpx", "xml"),
            Map.entry("tld", "xml"),
            Map.entry("jnlp", "xml"),
            Map.entry("xmi", "xml"),
            Map.entry("ditamap", "xml"),
            Map.entry("dita", "xml"),
            Map.entry("html", "html"),
            Map.entry("htm", "html"),
            Map.entry("xhtml", "html"),
            Map.entry("astro", "astro"),
            Map.entry("md", "markdown"),
            Map.entry("markdown", "markdown"),
            Map.entry("mdown", "markdown"),
            Map.entry("mkd", "markdown"),
            // Other spellings of the extension, and Markdown dialects: MDX, R Markdown and Quarto documents are
            // Markdown with extras, and reading them as Markdown (preview, outline, lint) beats plain text.
            Map.entry("mkdn", "markdown"),
            Map.entry("mdwn", "markdown"),
            Map.entry("mdx", "markdown"),
            Map.entry("rmd", "markdown"),
            Map.entry("qmd", "markdown"),
            Map.entry("mmd", "mermaid"),
            Map.entry("mermaid", "mermaid"),
            Map.entry("mw", "markwhen"),
            Map.entry("markwhen", "markwhen"),
            // Diagram-as-code (rendered in the 3-mode preview via an external CLI: dot / plantuml).
            Map.entry("dot", "dot"),
            Map.entry("gv", "dot"),
            Map.entry("puml", "plantuml"),
            Map.entry("plantuml", "plantuml"),
            Map.entry("pu", "plantuml"),
            Map.entry("iuml", "plantuml"),
            // Typst document (rendered to a multi-page image preview via the typst CLI).
            Map.entry("typ", "typst"),
            // Brace-delimited languages (fold by matched {} / []).
            Map.entry("java", "java"),
            // JS/TS — the language name doubles as the LSP languageId (javascript/typescript/…react).
            Map.entry("js", "javascript"),
            Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"),
            Map.entry("jsx", "javascriptreact"),
            Map.entry("ts", "typescript"),
            Map.entry("mts", "typescript"),
            Map.entry("cts", "typescript"),
            Map.entry("tsx", "typescriptreact"),
            Map.entry("json", "json"),
            Map.entry("ipynb", "json"), // Jupyter notebooks: JSON until there is a notebook view
            Map.entry("jsonc", "json"),
            Map.entry("json5", "json"),
            Map.entry("c", "c"),
            Map.entry("h", "c"),
            Map.entry("cpp", "cpp"),
            Map.entry("cc", "cpp"),
            Map.entry("cxx", "cpp"),
            Map.entry("hpp", "cpp"),
            Map.entry("hh", "cpp"),
            Map.entry("hxx", "cpp"),
            Map.entry("c++", "cpp"),
            Map.entry("h++", "cpp"),
            Map.entry("ipp", "cpp"),
            Map.entry("inl", "cpp"),
            Map.entry("rs", "rust"),
            Map.entry("go", "go"),
            Map.entry("kt", "kotlin"),
            Map.entry("kts", "kotlin"),
            Map.entry("groovy", "groovy"),
            Map.entry("gradle", "groovy"),
            Map.entry("gvy", "groovy"),
            Map.entry("scala", "scala"),
            Map.entry("sc", "scala"), // Scala CLI / Ammonite script
            Map.entry("sbt", "scala"),
            Map.entry("mill", "scala"),
            // Clojure is a Lisp: folds by its own paren folder, not the brace one.
            Map.entry("clj", "clojure"),
            Map.entry("cljs", "clojure"),
            Map.entry("cljc", "clojure"),
            Map.entry("cljx", "clojure"),
            Map.entry("edn", "clojure"),
            Map.entry("bb", "clojure"), // Babashka script
            Map.entry("cs", "csharp"),
            Map.entry("csx", "csharp"),
            Map.entry("css", "css"),
            // PHP — brace-delimited; the bundled grammar (source.php) embeds HTML/CSS/SQL.
            Map.entry("php", "php"),
            Map.entry("phtml", "php"),
            Map.entry("php3", "php"),
            Map.entry("php4", "php"),
            Map.entry("php5", "php"),
            Map.entry("phps", "php"),
            // Line/indentation-based languages (no delimiter folding).
            Map.entry("py", "python"),
            Map.entry("pyw", "python"),
            Map.entry("pyi", "python"),
            Map.entry("rb", "ruby"),
            Map.entry("rake", "ruby"),
            Map.entry("gemspec", "ruby"),
            // Shell extensions come from ShellFiles (shared with GrammarRegistry), see forFileName.
            Map.entry("ps1", "powershell"),
            Map.entry("psm1", "powershell"),
            Map.entry("psd1", "powershell"),
            Map.entry("bat", "batchfile"),
            Map.entry("cmd", "batchfile"),
            Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"),
            Map.entry("ini", "ini"),
            Map.entry("cfg", "ini"),
            Map.entry("conf", "ini"),
            Map.entry("sql", "sql"),
            Map.entry("ddl", "sql"),
            Map.entry("dml", "sql"),
            Map.entry("lua", "lua"),
            Map.entry("dockerfile", "dockerfile"),
            // Terraform / HCL — brace-delimited blocks.
            Map.entry("tf", "terraform"),
            Map.entry("tfvars", "terraform"),
            Map.entry("hcl", "terraform"),
            // TOML — its own grammar + the taplo LSP (was previously highlighted via the INI grammar).
            Map.entry("toml", "toml"),
            Map.entry("tml", "toml"),
            // HTTP request files (JetBrains/VS Code REST format).
            Map.entry("http", "http"),
            Map.entry("rest", "http"),
            // Delimiter-separated values — one grammar handles comma/semicolon/tab/pipe.
            Map.entry("csv", "csv"),
            Map.entry("tsv", "csv"),
            // systemd unit files — INI-like; no delimiter folding (like ini).
            Map.entry("service", "systemd"),
            Map.entry("socket", "systemd"),
            Map.entry("device", "systemd"),
            Map.entry("mount", "systemd"),
            Map.entry("automount", "systemd"),
            Map.entry("swap", "systemd"),
            Map.entry("target", "systemd"),
            Map.entry("path", "systemd"),
            Map.entry("timer", "systemd"),
            Map.entry("slice", "systemd"),
            Map.entry("scope", "systemd"),
            Map.entry("network", "systemd"),
            Map.entry("netdev", "systemd"),
            Map.entry("link", "systemd"),
            Map.entry("nspawn", "systemd"),
            // XDG desktop entries / KDE .directory files.
            Map.entry("desktop", "desktop"),
            Map.entry("directory", "desktop"),
            // Java .properties — its own grammar (Unicode escapes, ':' separator, continuations).
            Map.entry("properties", "properties"),
            // Server/application logs — the log viewer skins these (level highlighting, tail-follow).
            Map.entry("log", "log"),
            // Unified diffs / patches.
            Map.entry("diff", "diff"),
            Map.entry("patch", "diff"),
            // Makefiles — bare "Makefile"/"GNUmakefile" handled by ConfigFileType.
            Map.entry("mk", "makefile"),
            Map.entry("mak", "makefile"),
            Map.entry("make", "makefile"),
            // just command-runner files — bare "justfile" handled by ConfigFileType.
            Map.entry("just", "just"),
            // Protocol Buffers (brace-delimited; folds like C-family sources).
            Map.entry("proto", "proto"),
            // GraphQL schemas/queries (brace-delimited).
            Map.entry("graphql", "graphql"),
            Map.entry("gql", "graphql"),
            Map.entry("graphqls", "graphql"));

    private LanguageRegistry() {}

    /** The language name for {@code fileName} (by extension, plus a few extension-less filename rules
     *  like {@code Dockerfile}), or {@code "plaintext"} if unrecognized. */
    public static String forFileName(String fileName) {
        if (fileName == null) {
            return PLAINTEXT;
        }
        // An encrypted file is in the language of the file inside it: notes.md.gpg is Markdown.
        fileName = EncryptedNames.languageName(fileName);
        // Reduce a path to its last segment so filename rules work regardless of how the caller passes it.
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        // Name/location-determined config files (Dockerfile, dotenv, ssh/git config, hosts, ...).
        String special = ConfigFileType.resolve(fileName);
        if (special != null) {
            return special;
        }
        // Rotated and extensionless logs (app.log.1, access_log, syslog): the extension is a number or absent.
        if (com.editora.logviewer.LogFileNames.isLog(base)) {
            return "log";
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return PLAINTEXT;
        }
        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (isGoModule(base)) {
            return PLAINTEXT; // .mod is a DTD module, but Go's go.mod is not XML
        }
        return ShellFiles.isShellExtension(ext) ? "shell" : BY_EXTENSION.getOrDefault(ext, PLAINTEXT);
    }

    /** Whether {@code baseName} is Go's module file, whose {@code .mod} extension otherwise reads as a DTD module. */
    public static boolean isGoModule(String baseName) {
        return baseName != null && baseName.equalsIgnoreCase("go.mod");
    }

    public static String plaintext() {
        return PLAINTEXT;
    }

    /**
     * Extensions XML shares with other formats: a DTD module and a Modula-2 module ({@code .mod}), Schematron and a
     * KiCad schematic ({@code .sch}), MSBuild and Java/INI-style properties ({@code .props}, {@code .targets}),
     * XSL-FO and other {@code .fo} files. Such a file is XML only when its text says so (XML review 2 C13).
     */
    private static final java.util.Set<String> SNIFFED_XML_EXTENSIONS =
            java.util.Set.of("mod", "sch", "props", "targets", "fo");

    /**
     * The language a buffer for {@code file}, now in {@code current}, moves to once its text starts with
     * {@code head}, or null to keep it: a file {@link #xmlBySniff} names goes between XML and plain text by
     * {@link #sniffXml}. Any other language (the user's pick, a shebang's) is kept.
     */
    public static @org.jspecify.annotations.Nullable String sniffedXmlLanguage(
            java.nio.file.Path file, String current, java.util.function.Supplier<String> head) {
        java.nio.file.Path name = file.getFileName();
        if (!(PLAINTEXT.equals(current) || "xml".equals(current)) || name == null || !xmlBySniff(name.toString())) {
            return null; // read only for these few extensions: this runs after every settled edit
        }
        String want = sniffXml(head.get());
        return want == null || want.equals(current) ? null : want;
    }

    /** Whether {@code fileName} maps to XML only by an extension other formats use too (see {@link #sniffXml}). */
    public static boolean xmlBySniff(String fileName) {
        if (fileName == null || !"xml".equals(forFileName(fileName))) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0
                && SNIFFED_XML_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * For a file {@link #xmlBySniff} names: {@code "xml"} when the first non-blank character of {@code head} (the
     * start of its text; a byte-order mark is skipped) is {@code <}, plain text when it is anything else, and null
     * when there is nothing but blanks yet to tell by.
     */
    public static @org.jspecify.annotations.Nullable String sniffXml(CharSequence head) {
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (!Character.isWhitespace(c) && c != '\uFEFF') {
                return c == '<' ? "xml" : PLAINTEXT;
            }
        }
        return null;
    }

    /** Every language name an extension maps to (not the file-name rules of {@link ConfigFileType}). */
    public static java.util.Set<String> names() {
        java.util.Set<String> names = new java.util.TreeSet<>(BY_EXTENSION.values());
        names.add("shell");
        return names;
    }

    /** Every extension mapped to a language (lower case, no dot), the shell ones included. */
    public static java.util.Set<String> extensions() {
        java.util.Set<String> all = new java.util.TreeSet<>(BY_EXTENSION.keySet());
        all.addAll(ShellFiles.EXTENSIONS);
        return all;
    }
}
