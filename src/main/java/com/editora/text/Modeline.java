package com.editora.text;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Reads the language a file names for itself when its name says nothing: a first-line shebang ({@link Shebang}),
 * else a vim or emacs modeline. Pure.
 *
 * <ul>
 *   <li>vim: {@code # vim: ft=sh}, {@code # vim: set filetype=bash :}, {@code vi:syntax=zsh} in the first or
 *       last five lines (vim's own default).
 *   <li>emacs: {@code # -*- mode: sh -*-} or {@code # -*- sh -*-} on the first line, or the second when the first
 *       is a shebang.
 * </ul>
 *
 * <p>When neither names one, a ShellCheck {@code # shell=} directive in the first lines makes the file shell
 * ({@code # shellcheck shell=bash} heads many a sourced {@code *.inc} library), and a file that opens with an XML
 * declaration ({@code <?xml version="1.0"?>}) is XML.
 *
 * <p>The answer is a {@link Shebang.Result} — a representative extension — so the caller resolves it exactly as
 * it resolves a shebang, through {@code LanguageRegistry.forFileName("shebang." + extension)}.
 */
public final class Modeline {

    private Modeline() {}

    /** How many lines at each end vim looks at ({@code 'modelines'} defaults to 5). */
    private static final int VIM_LINES = 5;

    private static final Pattern VIM = Pattern.compile("(?:^|\\s)(?:vim?|ex):\\s*(?:set?\\s+)?(.*)");
    private static final Pattern VIM_FILETYPE = Pattern.compile("(?:^|[\\s:])(?:ft|filetype|syn|syntax)=([\\w.+-]+)");
    private static final Pattern EMACS = Pattern.compile("-\\*-\\s*(.*?)\\s*-\\*-");
    private static final Pattern EMACS_MODE = Pattern.compile("(?:^|;)\\s*mode\\s*:\\s*([\\w+-]+)");

    /** Mode / filetype names (lower case, any {@code -mode} suffix removed) → representative extension. */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("sh", "sh"),
            Map.entry("bash", "sh"),
            Map.entry("zsh", "sh"),
            Map.entry("ksh", "sh"),
            Map.entry("shell", "sh"),
            Map.entry("shell-script", "sh"),
            Map.entry("python", "py"),
            Map.entry("ruby", "rb"),
            Map.entry("javascript", "js"),
            Map.entry("js", "js"),
            Map.entry("typescript", "ts"),
            Map.entry("typescriptreact", "tsx"),
            Map.entry("tsx", "tsx"),
            Map.entry("typescript-tsx", "tsx"), // emacs typescript-tsx-mode
            Map.entry("javascriptreact", "jsx"),
            Map.entry("jsx", "jsx"),
            Map.entry("js2", "js"), // emacs js2-mode
            Map.entry("js2-jsx", "jsx"),
            Map.entry("rjsx", "jsx"), // emacs rjsx-mode
            Map.entry("json", "json"),
            Map.entry("yaml", "yaml"),
            Map.entry("xml", "xml"),
            Map.entry("html", "html"),
            Map.entry("css", "css"),
            Map.entry("markdown", "md"),
            Map.entry("java", "java"),
            Map.entry("c", "c"),
            Map.entry("cpp", "cpp"),
            Map.entry("c++", "cpp"),
            Map.entry("go", "go"),
            Map.entry("rust", "rs"),
            Map.entry("lua", "lua"),
            Map.entry("php", "php"),
            Map.entry("sql", "sql"),
            Map.entry("make", "mk"),
            Map.entry("makefile", "mk"),
            Map.entry("dockerfile", "dockerfile"),
            Map.entry("toml", "toml"),
            Map.entry("dosini", "ini"),
            Map.entry("conf", "ini"),
            Map.entry("ini", "ini"),
            Map.entry("groovy", "groovy"),
            Map.entry("kotlin", "kt"),
            Map.entry("scala", "scala"),
            Map.entry("clojure", "clj"),
            Map.entry("clojurescript", "cljs"),
            Map.entry("ps1", "ps1"),
            Map.entry("powershell", "ps1"));

    /**
     * The language {@code head} (the start of the file) and {@code tail} (its end; may overlap {@code head}) name
     * for the file: a shebang wins, then a modeline. {@code null} when they name nothing Editora knows.
     */
    public static Shebang.@Nullable Result detect(String head, String tail) {
        return detect(head, tail, null);
    }

    /**
     * As {@link #detect(String, String)} for the file {@code fileName} (a name or path; null when unsaved). A
     * {@code .txt} file is plain text by its own name, so its content is not sniffed for XML (as in VS Code).
     */
    public static Shebang.@Nullable Result detect(String head, String tail, @Nullable Object fileName) {
        List<String> first =
                head == null ? List.of() : head.lines().limit(VIM_LINES).toList();
        if (!first.isEmpty() && first.get(0).startsWith("#!")) {
            Shebang.Result shebang = Shebang.parse(first.get(0));
            if (shebang != null) {
                return shebang;
            }
        }
        String ext = emacs(first);
        if (ext == null) {
            ext = vim(first);
        }
        if (ext == null && ShellDialect.directiveShell(head) != null) {
            ext = "sh"; // ShellCheck's `# shellcheck shell=bash`: a sourced library (*.inc) says so (Bash review A15)
        }
        if (ext == null && tail != null) {
            List<String> all = tail.lines().toList();
            ext = vim(all.subList(Math.max(0, all.size() - VIM_LINES), all.size()));
        }
        if (ext == null && startsWithXmlDeclaration(head) && !namedAsText(fileName)) {
            ext = "xml"; // an AndroidManifest-style file with no telling extension (XML review A8)
        }
        if (ext == null && startsWithHtml(head) && !namedAsText(fileName)) {
            ext = "html"; // a saved page, CGI output or template with no extension, as VS Code sniffs it (HTML A13)
        }
        return ext == null ? null : new Shebang.Result(ext, null);
    }

    /** Whether {@code fileName} ends in {@code .txt} or {@code .text}: a name that says "plain text". */
    private static boolean namedAsText(@Nullable Object fileName) {
        String name = fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".text");
    }

    /**
     * Whether {@code head} opens with an XML declaration ({@code <?xml} followed by white space or {@code ?>}), after
     * an optional byte-order mark and blank space — the content sniff IntelliJ and VS Code apply to a file whose
     * name says nothing. Pure.
     */
    public static boolean startsWithXmlDeclaration(@Nullable String head) {
        if (head == null) {
            return false;
        }
        int i = 0;
        int n = head.length();
        if (i < n && head.charAt(i) == '\uFEFF') {
            i++;
        }
        while (i < n && Character.isWhitespace(head.charAt(i))) {
            i++;
        }
        if (!head.startsWith("<?xml", i) || i + 5 >= n) {
            return false;
        }
        char after = head.charAt(i + 5);
        return Character.isWhitespace(after) || after == '?';
    }

    /**
     * Whether {@code head} opens with {@code <!doctype html} or an {@code <html>} tag (either case, then white space or
     * {@code >}), after an optional byte-order mark, blank space and leading {@code <!-- … -->} comments. Pure.
     */
    public static boolean startsWithHtml(@Nullable String head) {
        if (head == null) {
            return false;
        }
        int i = 0;
        int n = head.length();
        if (i < n && head.charAt(i) == '\uFEFF') {
            i++;
        }
        while (true) {
            while (i < n && Character.isWhitespace(head.charAt(i))) {
                i++;
            }
            if (!head.startsWith("<!--", i)) {
                break;
            }
            int end = head.indexOf("-->", i + 4);
            if (end < 0) {
                return false;
            }
            i = end + 3;
        }
        return tagAt(head, i, "<!doctype html") || tagAt(head, i, "<html");
    }

    /** Whether {@code text} has {@code word} at {@code i}, ignoring case, followed by white space or {@code >}. */
    private static boolean tagAt(String text, int i, String word) {
        int end = i + word.length();
        if (end >= text.length() || !text.regionMatches(true, i, word, 0, word.length())) {
            return false;
        }
        char after = text.charAt(end);
        return Character.isWhitespace(after) || after == '>';
    }

    /** The extension an emacs {@code -*- … -*-} line names: the first line, or the second after a shebang. */
    private static @Nullable String emacs(List<String> first) {
        int limit = Math.min(first.size(), !first.isEmpty() && first.get(0).startsWith("#!") ? 2 : 1);
        for (int i = 0; i < limit; i++) {
            Matcher m = EMACS.matcher(first.get(i));
            if (m.find()) {
                String body = m.group(1);
                String mode;
                if (body.contains(":")) {
                    Matcher mm = EMACS_MODE.matcher(body);
                    mode = mm.find() ? mm.group(1) : null;
                } else {
                    mode = body.strip();
                }
                if (mode != null) {
                    return extensionFor(mode);
                }
            }
        }
        return null;
    }

    /** The extension a vim modeline among {@code lines} names, or {@code null}. */
    private static @Nullable String vim(List<String> lines) {
        for (String line : lines) {
            Matcher m = VIM.matcher(line);
            if (m.find()) {
                Matcher ft = VIM_FILETYPE.matcher(m.group(1));
                if (ft.find()) {
                    String ext = extensionFor(ft.group(1));
                    if (ext != null) {
                        return ext;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String extensionFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith("-mode")) {
            n = n.substring(0, n.length() - "-mode".length());
        }
        if (n.endsWith("-ts")) {
            n = n.substring(0, n.length() - "-ts".length()); // emacs 29 tree-sitter modes: bash-ts-mode
        }
        return NAMES.get(n);
    }
}
