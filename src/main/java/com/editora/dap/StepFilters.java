package com.editora.dap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * "Don't step into" ({@link StepFilter}): how each debugger is told, and how Editora tells when it was not. Pure.
 *
 * <p>Where a debugger skips code itself, it is told: java-debug through its settings' {@code skipClasses} (with the
 * JDK's, see {@link JavaDebugSettings}), Editora's own JVM adapter (Kotlin, Scala, Groovy, Clojure) through the launch's
 * {@code skipClasses}, debugpy through {@code rules} ({@code {"module": …}} or {@code {"path": …}}, {@code include}
 * false) and js-debug through {@code skipFiles} — {@link #decorate}. Every other debugger (Delve, CodeLLDB, GDB,
 * netcoredbg, rdbg, Xdebug) steps where it steps; a step that ends in filtered code is then stepped out of at once
 * ({@link #matches}), as is one that ends there in a debugger told too late (a filter added during the session).
 *
 * <p>A pattern is a path when it says so — a {@code /} or {@code \} (but Go's qualified names have slashes), a leading
 * {@code **}, or a source file's extension — and is matched against the frame's file as a glob ({@code **} any folders,
 * {@code *} any text in one). Otherwise it names code: a glob over the frame's function ({@code fmt.*}, {@code
 * std::*}), or, made of names and dots ({@code com.shop.util.*}, {@code requests}), a package or module found in the
 * frame's path ({@code /com/shop/util/}, {@code /requests/} or {@code /requests.py}).
 */
public final class StepFilters {

    private StepFilters() {}

    /** The JVM languages Editora's own adapter debugs, which takes the launch's {@code skipClasses}. */
    static final Set<String> JVM_OWN = Set.of("kotlin", "scala", "groovy", "clojure");

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "py", "pyw", "js", "mjs", "cjs", "ts", "mts", "cts", "jsx", "tsx", "go", "rs", "c", "cc", "cpp", "cxx", "h",
            "hh", "hpp", "java", "kt", "kts", "scala", "groovy", "clj", "cljc", "rb", "php", "cs", "fs");

    private static final Pattern DOTTED =
            Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*(\\.\\*)?");

    /** The patterns of the filters that are on for {@code language} (theirs, and every language's). */
    public static List<String> patterns(List<StepFilter> all, @Nullable String language) {
        List<String> out = new ArrayList<>();
        for (StepFilter f : all) {
            if (f != null
                    && f.isEnabled()
                    && !f.getPattern().isBlank()
                    && (f.getLanguage().isEmpty() || f.getLanguage().equals(language))
                    && !out.contains(f.getPattern())) {
                out.add(f.getPattern());
            }
        }
        return List.copyOf(out);
    }

    /** Whether {@code pattern} names files rather than code, in {@code language}. */
    static boolean pathLike(@Nullable String language, String pattern) {
        String p = pattern.strip();
        if (p.startsWith("**") || p.contains("\\")) {
            return true;
        }
        int dot = p.lastIndexOf('.');
        if (dot > 0 && SOURCE_EXTENSIONS.contains(p.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (p.startsWith("/")) {
            return true;
        }
        // Go names a function by its package's import path: github.com/x/y.F is code, not a file.
        return p.contains("/") && !"go".equals(language);
    }

    /**
     * {@code launch} with {@code patterns} told to its debugger, where the debugger takes them at launch: debugpy's
     * {@code rules}, js-debug's {@code skipFiles}, Editora's JVM adapter's {@code skipClasses}; added to what the
     * launch already has. Any other language's launch is returned as it is.
     */
    public static Map<String, Object> decorate(
            @Nullable String language, Map<String, Object> launch, List<String> patterns) {
        if (patterns.isEmpty() || language == null) {
            return launch;
        }
        Map<String, Object> out = new LinkedHashMap<>(launch);
        switch (language) {
            case "python" -> {
                List<Object> rules = new ArrayList<>(list(out.get("rules")));
                for (String p : patterns) {
                    Map<String, Object> rule = new LinkedHashMap<>();
                    if (pathLike(language, p)) {
                        rule.put("path", relativeGlob(p));
                    } else {
                        rule.put("module", p.endsWith(".*") ? p.substring(0, p.length() - 2) : p);
                    }
                    rule.put("include", false);
                    rules.add(rule);
                }
                out.put("rules", rules);
            }
            case "javascript", "typescript" -> {
                List<Object> skip = new ArrayList<>(list(out.get("skipFiles")));
                for (String p : patterns) {
                    String glob = pathLike(language, p) ? relativeGlob(p) : "**/node_modules/" + p + "/**";
                    if (!skip.contains(glob)) {
                        skip.add(glob);
                    }
                }
                out.put("skipFiles", skip);
            }
            default -> {
                if (!JVM_OWN.contains(language)) {
                    return launch;
                }
                List<Object> skip = new ArrayList<>(list(out.get("skipClasses")));
                skip.addAll(classPatterns(language, patterns));
                out.put("skipClasses", skip);
            }
        }
        return out;
    }

    /** The patterns that name classes (not files), as java-debug's {@code skipClasses} takes them. */
    public static List<String> classPatterns(@Nullable String language, List<String> patterns) {
        return patterns.stream().filter(p -> !pathLike(language, p)).toList();
    }

    private static List<?> list(@Nullable Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    /** A path glob as a debugger matches it against absolute paths: {@code util.py} is {@code **}{@code /util.py}. */
    static String relativeGlob(String pattern) {
        String p = pattern.strip().replace('\\', '/');
        return p.startsWith("/") || p.startsWith("**") || p.startsWith("<") || p.matches("[A-Za-z]:/.*")
                ? p
                : "**/" + p;
    }

    /**
     * Whether a frame — {@code name} (its function, as the debugger writes it) in {@code path} (may be null) — is in
     * code {@code pattern} filters, in {@code language}.
     */
    public static boolean matches(
            @Nullable String language, String pattern, @Nullable String name, @Nullable String path) {
        String p = pattern.strip();
        if (p.isEmpty()) {
            return false;
        }
        String file = path == null ? "" : path.replace('\\', '/');
        if (pathLike(language, p)) {
            return !file.isEmpty() && glob(relativeGlob(p), true).matcher(file).matches();
        }
        String n = name == null ? "" : name.strip();
        Pattern code = glob(p, false);
        if (!n.isEmpty()) {
            int paren = n.indexOf('(');
            String bare = paren > 0 ? n.substring(0, paren).strip() : n;
            if (code.matcher(n).matches() || code.matcher(bare).matches()) {
                return true;
            }
        }
        if (!file.isEmpty() && DOTTED.matcher(p).matches()) {
            String core = p.endsWith(".*") ? p.substring(0, p.length() - 2) : p;
            String fragment = "/" + core.replace('.', '/');
            return file.contains(fragment + "/") || file.contains(fragment + ".");
        }
        return false;
    }

    /** Whether a frame is in code any of {@code patterns} filters. */
    public static boolean matchesAny(
            @Nullable String language, List<String> patterns, @Nullable String name, @Nullable String path) {
        for (String p : patterns) {
            if (matches(language, p, name, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code glob} as a regex: {@code *} any text ({@code /} too for a name, not for a path, where {@code **} crosses
     * folders), {@code ?} one character.
     */
    static Pattern glob(String glob, boolean path) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (path && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        i++;
                        re.append("(?:.*/)?"); // **/ — any folders, or none
                    } else {
                        re.append(".*");
                    }
                } else {
                    re.append(path ? "[^/]*" : ".*");
                }
            } else if (c == '?') {
                re.append(path ? "[^/]" : ".");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), path ? 0 : Pattern.CASE_INSENSITIVE);
    }

    private static final Pattern JAVA_PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;?");

    /** The package a JVM source declares ({@code package com.shop;}), or "" — from its text. */
    public static String sourcePackage(@Nullable String source) {
        if (source == null) {
            return "";
        }
        Matcher m = JAVA_PACKAGE.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    /**
     * The pattern "Don't Step Into This" offers for a frame — {@code name} in {@code path}, whose source declares
     * {@code packageName} (JVM; "" when none or unknown): its class for a JVM language, its file for Python and
     * JavaScript (its package under {@code node_modules}), its package for Go and C#, its outer namespace for C++ and
     * Rust ({@code std::*}), else its file or its function.
     */
    public static String suggest(
            @Nullable String language, @Nullable String name, @Nullable String path, String packageName) {
        String n = name == null ? "" : name.strip();
        int paren = n.indexOf('(');
        String bare = paren > 0 ? n.substring(0, paren).strip() : n;
        String file = path == null ? "" : path.replace('\\', '/');
        String fileName = file.isEmpty() ? "" : file.substring(file.lastIndexOf('/') + 1);
        String lang = language == null ? "" : language;
        switch (lang) {
            case "java", "kotlin", "scala", "groovy", "clojure" -> {
                String cls = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : "";
                if (cls.isEmpty()) {
                    int dot = bare.lastIndexOf('.');
                    cls = dot > 0 ? bare.substring(0, dot) : bare;
                }
                return packageName.isBlank() ? cls : packageName + "." + cls;
            }
            case "python" -> {
                return fileName.isEmpty() ? bare : "**/" + fileName;
            }
            case "javascript", "typescript" -> {
                int nm = file.lastIndexOf("/node_modules/");
                if (nm >= 0) {
                    String rest = file.substring(nm + "/node_modules/".length());
                    String[] parts = rest.split("/");
                    String pkg = parts[0].startsWith("@") && parts.length > 1 ? parts[0] + "/" + parts[1] : parts[0];
                    return "**/node_modules/" + pkg + "/**";
                }
                return fileName.isEmpty() ? bare : "**/" + fileName;
            }
            case "go", "csharp" -> {
                // fmt.Println → fmt.*; github.com/x/y.(*T).M → github.com/x/y.*; Shop.Util.Format → Shop.Util.*
                int slash = bare.lastIndexOf('/');
                int dot = bare.indexOf('.', Math.max(0, slash));
                if ("csharp".equals(lang)) {
                    dot = bare.lastIndexOf('.');
                }
                return dot > 0 ? bare.substring(0, dot) + ".*" : bare;
            }
            case "cpp", "rust" -> {
                String plain = bare.startsWith("<") ? bare : bare.replaceAll("<.*>", "");
                int sep = plain.indexOf("::");
                return sep > 0 ? plain.substring(0, sep) + "::*" : (fileName.isEmpty() ? plain : "**/" + fileName);
            }
            default -> {
                return fileName.isEmpty() ? bare : "**/" + fileName;
            }
        }
    }
}
