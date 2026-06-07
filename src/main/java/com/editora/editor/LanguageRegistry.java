package com.editora.editor;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a file name to a short language identifier. Syntax highlighting itself is handled by
 * {@link GrammarRegistry} (TextMate grammars via tm4e); this registry only supplies the language
 * <em>name</em> used by the fold engine ({@link FoldRegions}) to pick a folding strategy and by the
 * File Information panel for display. Unknown extensions resolve to {@code "plaintext"}.
 */
public final class LanguageRegistry {

    public static final String PLAINTEXT = "plaintext";

    /** Lower-case file extension -> language name. */
    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            // Markup (fold by element nesting / fenced sections).
            Map.entry("xml", "xml"), Map.entry("xsd", "xml"), Map.entry("xsl", "xml"),
            Map.entry("xslt", "xml"), Map.entry("svg", "xml"), Map.entry("fxml", "xml"),
            Map.entry("pom", "xml"), Map.entry("rss", "xml"), Map.entry("wsdl", "xml"),
            Map.entry("html", "html"), Map.entry("htm", "html"), Map.entry("xhtml", "html"),
            Map.entry("md", "markdown"), Map.entry("markdown", "markdown"),
            Map.entry("mdown", "markdown"), Map.entry("mkd", "markdown"),
            Map.entry("mmd", "mermaid"), Map.entry("mermaid", "mermaid"),
            // Brace-delimited languages (fold by matched {} / []).
            Map.entry("java", "java"),
            // JS/TS — the language name doubles as the LSP languageId (javascript/typescript/…react).
            Map.entry("js", "javascript"), Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
            Map.entry("jsx", "javascriptreact"),
            Map.entry("ts", "typescript"), Map.entry("mts", "typescript"), Map.entry("cts", "typescript"),
            Map.entry("tsx", "typescriptreact"),
            Map.entry("json", "json"), Map.entry("jsonc", "json"), Map.entry("json5", "json"),
            Map.entry("c", "c"), Map.entry("h", "c"),
            Map.entry("cpp", "cpp"), Map.entry("cc", "cpp"), Map.entry("cxx", "cpp"),
            Map.entry("hpp", "cpp"), Map.entry("hh", "cpp"), Map.entry("hxx", "cpp"),
            Map.entry("rs", "rust"), Map.entry("go", "go"),
            Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"),
            Map.entry("groovy", "groovy"), Map.entry("gradle", "groovy"), Map.entry("gvy", "groovy"),
            Map.entry("cs", "csharp"), Map.entry("csx", "csharp"),
            Map.entry("css", "css"),
            // PHP — brace-delimited; the bundled grammar (source.php) embeds HTML/CSS/SQL.
            Map.entry("php", "php"), Map.entry("phtml", "php"), Map.entry("php3", "php"),
            Map.entry("php4", "php"), Map.entry("php5", "php"), Map.entry("phps", "php"),
            // Line/indentation-based languages (no delimiter folding).
            Map.entry("py", "python"), Map.entry("pyw", "python"), Map.entry("pyi", "python"),
            Map.entry("rb", "ruby"), Map.entry("rake", "ruby"), Map.entry("gemspec", "ruby"),
            Map.entry("sh", "shell"), Map.entry("bash", "shell"), Map.entry("zsh", "shell"),
            Map.entry("ps1", "powershell"), Map.entry("psm1", "powershell"),
            Map.entry("bat", "batchfile"), Map.entry("cmd", "batchfile"),
            Map.entry("yaml", "yaml"), Map.entry("yml", "yaml"),
            Map.entry("ini", "ini"), Map.entry("cfg", "ini"), Map.entry("conf", "ini"),
            Map.entry("sql", "sql"), Map.entry("ddl", "sql"), Map.entry("dml", "sql"),
            Map.entry("lua", "lua"),
            Map.entry("dockerfile", "dockerfile"),
            // Terraform / HCL — brace-delimited blocks.
            Map.entry("tf", "terraform"), Map.entry("tfvars", "terraform"), Map.entry("hcl", "terraform"),
            // TOML — its own grammar + the taplo LSP (was previously highlighted via the INI grammar).
            Map.entry("toml", "toml"), Map.entry("tml", "toml"));

    private LanguageRegistry() {
    }

    /** The language name for {@code fileName} (by extension, plus a few extension-less filename rules
     *  like {@code Dockerfile}), or {@code "plaintext"} if unrecognized. */
    public static String forFileName(String fileName) {
        if (fileName == null) {
            return PLAINTEXT;
        }
        // Reduce a path to its last segment so filename rules work regardless of how the caller passes it.
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String lower = base.toLowerCase(Locale.ROOT);
        // Dockerfile / Containerfile are extension-less (or carry a tag suffix, e.g. Dockerfile.dev).
        if (lower.equals("dockerfile") || lower.equals("containerfile")
                || lower.startsWith("dockerfile.") || lower.startsWith("containerfile.")) {
            return "dockerfile";
        }
        int dot = base.lastIndexOf('.');
        if (dot < 0 || dot == base.length() - 1) {
            return PLAINTEXT;
        }
        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BY_EXTENSION.getOrDefault(ext, PLAINTEXT);
    }

    public static String plaintext() {
        return PLAINTEXT;
    }
}
