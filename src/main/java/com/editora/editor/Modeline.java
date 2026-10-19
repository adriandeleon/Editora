package com.editora.editor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public static Shebang.Result detect(String head, String tail) {
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
        if (ext == null && tail != null) {
            List<String> all = tail.lines().toList();
            ext = vim(all.subList(Math.max(0, all.size() - VIM_LINES), all.size()));
        }
        return ext == null ? null : new Shebang.Result(ext, null);
    }

    /** The extension an emacs {@code -*- … -*-} line names: the first line, or the second after a shebang. */
    private static String emacs(List<String> first) {
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
    private static String vim(List<String> lines) {
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

    private static String extensionFor(String name) {
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
