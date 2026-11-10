package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Which project file uses which (Map code browser gap 14), read from the files' own import lines — no language server,
 * no build: {@code import}/{@code package} in Java, Kotlin, Groovy and Scala; {@code using}/{@code namespace} in C#;
 * {@code use}/{@code namespace}/{@code require} in PHP; {@code import}/{@code from … import} in Python; relative
 * {@code import}/{@code export … from}/{@code require()} in JavaScript and TypeScript (and Vue and Svelte files);
 * {@code import} in Go, against the project's {@code go.mod} modules; {@code mod}/{@code use crate::…} in Rust;
 * {@code #include "…"} in C, C++ and Objective-C; {@code require_relative}/{@code require} in Ruby; {@code import} in
 * Dart. A name a file uses from its own package (or namespace), or from a package it imports whole, counts when the
 * file names one of that package's types. Imports of anything outside the project are left out. Pure.
 */
final class ProjectMapImports {

    private ProjectMapImports() {}

    /** The languages read. */
    enum Lang {
        JAVA,
        KOTLIN,
        GROOVY,
        SCALA,
        CSHARP,
        PHP,
        PYTHON,
        SCRIPT,
        GO,
        RUST,
        C,
        RUBY,
        DART
    }

    /** What an import line names. */
    enum Kind {
        /** A type by its full name (dots between the parts), or a member of one. */
        TYPE,
        /** A package (or namespace) whole: its types the file names. */
        PACKAGE,
        /** A path relative to the file, as written. */
        PATH,
        /** A Python module ({@code level} dots before it), and maybe {@code names} from it. */
        PYTHON,
        /** A Go package's import path. */
        GO,
        /** A Rust {@code mod name;}. */
        RUST_MOD,
        /** A Rust {@code use} path ({@code ::} between the parts). */
        RUST_USE,
        /** A C include; {@code level} 1 for {@code "…"}, 0 for {@code <…>}. */
        INCLUDE,
        /** A Ruby {@code require} or a Dart {@code package:} import: found under some {@code lib/}. */
        LIBRARY
    }

    /** One import: what it names, on which 0-based line, and the line itself (trimmed, cut at 160 characters). */
    record Spec(Kind kind, String target, int level, List<String> names, int line, String text) {
        Spec {
            names = List.copyOf(names);
        }
    }

    /**
     * What one file says: its language, its package or namespace (dots between the parts; empty for none), the types it
     * declares, the capitalized names it uses with the first line of each (languages with packages only), and its
     * imports.
     */
    record Parsed(Lang lang, String pkg, List<String> declared, Map<String, Integer> names, List<Spec> specs) {
        Parsed {
            declared = List.copyOf(declared);
            names = Map.copyOf(names);
            specs = List.copyOf(specs);
        }
    }

    /** That {@code from} uses {@code to}, first at its 0-based {@code line}, which reads {@code text}. */
    record Edge(String from, String to, int line, String text) {}

    private static final int MAX_TEXT = 160;

    /** The language of a root-relative path, or null for none read. */
    static @Nullable Lang languageOf(String rel) {
        int dot = rel.lastIndexOf('.');
        if (dot < 0 || dot < rel.lastIndexOf('/')) {
            return null;
        }
        return switch (rel.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "java" -> Lang.JAVA;
            case "kt", "kts" -> Lang.KOTLIN;
            case "groovy" -> Lang.GROOVY;
            case "scala", "sc" -> Lang.SCALA;
            case "cs" -> Lang.CSHARP;
            case "php" -> Lang.PHP;
            case "py", "pyi" -> Lang.PYTHON;
            case "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "vue", "svelte" -> Lang.SCRIPT;
            case "go" -> Lang.GO;
            case "rs" -> Lang.RUST;
            case "c", "h", "cc", "cpp", "cxx", "hpp", "hh", "hxx", "m", "mm", "ipp", "inl" -> Lang.C;
            case "rb" -> Lang.RUBY;
            case "dart" -> Lang.DART;
            default -> null;
        };
    }

    // --- reading one file -----------------------------------------------------------------------------------------

    private static final Pattern JVM_PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)");
    private static final Pattern JVM_IMPORT = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*|\\._)?\\s*(?:;|\\s+as\\s+\\w+|\\s*(?://.*)?$)");
    private static final Pattern SCALA_GROUP = Pattern.compile("^\\s*import\\s+([\\w.]+)\\.\\{([^}]*)}");
    /**
     * A top-level type declared: at the start of its line, after modifiers. Indented ones are left out (nested types,
     * and the text of a test's source in a string), but for C#'s, inside a {@code namespace} block.
     */
    private static final Pattern DECLARED = Pattern.compile(
            "^(\\s*)(?:(?:public|private|protected|internal|static|final"
                    + "|abstract|sealed|non-sealed|open|data|inner|enum|annotation|value|partial|readonly|export|case|implicit"
                    + "|strictfp|unsafe)\\s+)*(?:class|interface|enum|record|object|trait|struct|@interface)\\s+([A-Z][A-Za-z0-9_]*)");

    private static final Pattern CAPITALIZED = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");
    private static final Pattern CS_NAMESPACE = Pattern.compile("^\\s*namespace\\s+([\\w.]+)");
    private static final Pattern CS_ALIAS = Pattern.compile("^\\s*(?:global\\s+)?using\\s+\\w+\\s*=\\s*([\\w.]+)\\s*;");
    private static final Pattern CS_USING = Pattern.compile("^\\s*(?:global\\s+)?using\\s+(static\\s+)?([\\w.]+)\\s*;");
    private static final Pattern PHP_NAMESPACE = Pattern.compile("^\\s*namespace\\s+([\\w\\\\]+)\\s*[;{]");
    private static final Pattern PHP_USE =
            Pattern.compile("^\\s*use\\s+(?:function\\s+|const\\s+)?\\\\?([\\w\\\\]+)(?:\\s+as\\s+\\w+)?\\s*;");
    private static final Pattern PHP_GROUP = Pattern.compile("^\\s*use\\s+\\\\?([\\w\\\\]+)\\\\\\{([^}]*)}");
    private static final Pattern PHP_REQUIRE = Pattern.compile(
            "\\b(?:require|include)(?:_once)?\\s*\\(?\\s*(?:__DIR__\\s*\\.\\s*)?['\"]([^'\"]+\\.php)['\"]");
    private static final Pattern PY_IMPORT = Pattern.compile("^import\\s+([\\w., ]+)");
    private static final Pattern PY_FROM = Pattern.compile("^from\\s+(\\.*)([\\w.]*)\\s+import\\s+(.+)");
    private static final Pattern SCRIPT_FROM =
            Pattern.compile("\\b(?:import|export)\\s[^'\"`;]{0,600}?\\bfrom\\s*['\"]([^'\"\\n]+)['\"]");
    private static final Pattern SCRIPT_BARE = Pattern.compile("\\bimport\\s*['\"]([^'\"\\n]+)['\"]");
    private static final Pattern SCRIPT_CALL =
            Pattern.compile("\\b(?:require|import)\\s*\\(\\s*['\"]([^'\"\\n]+)['\"]\\s*\\)");
    private static final Pattern GO_SINGLE = Pattern.compile("^\\s*import\\s+(?:[\\w.]+\\s+)?\"([^\"]+)\"");
    private static final Pattern GO_IN_BLOCK = Pattern.compile("^\\s*(?:[\\w.]+\\s+)?\"([^\"]+)\"");
    private static final Pattern RUST_MOD_LINE =
            Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?mod\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern RUST_USE_LINE = Pattern.compile("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?use\\s+([^;]+);");
    private static final Pattern C_INCLUDE = Pattern.compile("^\\s*#\\s*(?:include|import)\\s*([<\"])([^>\"]+)[>\"]");
    private static final Pattern RUBY_RELATIVE =
            Pattern.compile("^\\s*require_relative\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern RUBY_REQUIRE = Pattern.compile("^\\s*require\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern DART_IMPORT = Pattern.compile("^\\s*(?:import|export|part)\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern GO_MODULE = Pattern.compile("(?m)^\\s*module\\s+(\\S+)");

    /** What the file at {@code rel} says, from its {@code text}; null when its language is not read. */
    static @Nullable Parsed parse(String rel, String text) {
        Lang lang = languageOf(rel);
        if (lang == null) {
            return null;
        }
        return switch (lang) {
            case JAVA, KOTLIN, GROOVY, SCALA -> parseJvm(lang, text);
            case CSHARP -> parseCsharp(text);
            case PHP -> parsePhp(text);
            case PYTHON -> parsePython(text);
            case SCRIPT -> parseScript(text);
            case GO -> parseGo(text);
            case RUST -> parseRust(text);
            case C ->
                parseLines(
                        lang,
                        text,
                        C_INCLUDE,
                        (m, line, specs) -> specs.add(spec(
                                Kind.INCLUDE,
                                m.group(2).trim(),
                                "\"".equals(m.group(1)) ? 1 : 0,
                                List.of(),
                                line,
                                m.group())));
            case RUBY -> parseRuby(text);
            case DART ->
                parseLines(lang, text, DART_IMPORT, (m, line, specs) -> {
                    String target = m.group(1);
                    if (target.startsWith("package:")) {
                        int slash = target.indexOf('/');
                        if (slash > 0) {
                            specs.add(spec(Kind.LIBRARY, target.substring(slash + 1), 0, List.of(), line, m.group()));
                        }
                    } else if (!target.contains(":")) {
                        specs.add(spec(Kind.PATH, target, 0, List.of(), line, m.group()));
                    }
                });
        };
    }

    /** The module path a {@code go.mod}'s text declares, or null. */
    static @Nullable String goModule(String text) {
        Matcher m = GO_MODULE.matcher(text);
        return m.find() ? m.group(1).replace("\"", "") : null;
    }

    private interface LineRule {
        void add(Matcher matcher, int line, List<Spec> specs);
    }

    private static Parsed parseLines(Lang lang, String text, Pattern pattern, LineRule rule) {
        List<Spec> specs = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = pattern.matcher(lines[i]);
            if (m.find()) {
                rule.add(m, i, specs);
            }
        }
        return new Parsed(lang, "", List.of(), Map.of(), specs);
    }

    private static Spec spec(Kind kind, String target, int level, List<String> names, int line, String text) {
        String trimmed = text.strip();
        return new Spec(
                kind,
                target,
                level,
                names,
                line,
                trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) + "…" : trimmed);
    }

    /** Whether a line is (most likely) only a comment. */
    private static boolean commentLine(String line) {
        String t = line.stripLeading();
        return t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("#!");
    }

    private static Parsed parseJvm(Lang lang, String text) {
        String pkg = "";
        List<Spec> specs = new ArrayList<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        Set<String> declared = new java.util.LinkedHashSet<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (commentLine(line)) {
                continue;
            }
            Matcher m = JVM_PACKAGE.matcher(line);
            if (m.find()) {
                if (pkg.isEmpty()) {
                    pkg = m.group(1);
                }
                continue;
            }
            Matcher group = SCALA_GROUP.matcher(line);
            if (group.find()) {
                for (String part : group.group(2).split(",")) {
                    String name = part.split("=>")[0].strip();
                    if (name.equals("_") || name.equals("*")) {
                        specs.add(spec(Kind.PACKAGE, group.group(1), 0, List.of(), i, line));
                    } else if (!name.isEmpty()) {
                        specs.add(spec(Kind.TYPE, group.group(1) + "." + name, 0, List.of(), i, line));
                    }
                }
                continue;
            }
            m = JVM_IMPORT.matcher(line);
            if (m.find()) {
                boolean whole = m.group(3) != null;
                specs.add(spec(whole ? Kind.PACKAGE : Kind.TYPE, m.group(2), 0, List.of(), i, line));
                continue;
            }
            declaredAndNames(line, i, 0, declared, names);
        }
        return new Parsed(lang, pkg, List.copyOf(declared), names, specs);
    }

    private static void declaredAndNames(
            String line, int i, int indent, Set<String> declared, Map<String, Integer> names) {
        Matcher d = DECLARED.matcher(line);
        if (d.find() && d.group(1).replace("\t", "    ").length() <= indent) {
            declared.add(d.group(2));
        }
        Matcher n = CAPITALIZED.matcher(line);
        while (n.find()) {
            names.putIfAbsent(n.group(1), i);
        }
    }

    private static Parsed parseCsharp(String text) {
        String pkg = "";
        List<Spec> specs = new ArrayList<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        Set<String> declared = new java.util.LinkedHashSet<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (commentLine(line)) {
                continue;
            }
            Matcher m = CS_NAMESPACE.matcher(line);
            if (m.find() && pkg.isEmpty()) {
                pkg = m.group(1); // a type may follow on the same line
            }
            m = CS_ALIAS.matcher(line);
            if (m.find()) {
                specs.add(spec(Kind.TYPE, m.group(1), 0, List.of(), i, line));
                continue;
            }
            m = CS_USING.matcher(line);
            if (m.find()) {
                specs.add(spec(m.group(1) != null ? Kind.TYPE : Kind.PACKAGE, m.group(2), 0, List.of(), i, line));
                continue;
            }
            declaredAndNames(line, i, 4, declared, names);
        }
        return new Parsed(Lang.CSHARP, pkg, List.copyOf(declared), names, specs);
    }

    private static Parsed parsePhp(String text) {
        String pkg = "";
        List<Spec> specs = new ArrayList<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        Set<String> declared = new java.util.LinkedHashSet<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (commentLine(line)) {
                continue;
            }
            Matcher m = PHP_NAMESPACE.matcher(line);
            if (m.find() && pkg.isEmpty()) {
                pkg = m.group(1).replace('\\', '.');
            }
            m = PHP_GROUP.matcher(line);
            if (m.find()) {
                String base = m.group(1).replace('\\', '.');
                for (String part : m.group(2).split(",")) {
                    String name = part.strip().split("\\s+")[0].replace('\\', '.');
                    if (!name.isEmpty()) {
                        specs.add(spec(Kind.TYPE, base + "." + name, 0, List.of(), i, line));
                    }
                }
                continue;
            }
            m = PHP_USE.matcher(line);
            if (m.find()) {
                specs.add(spec(Kind.TYPE, m.group(1).replace('\\', '.'), 0, List.of(), i, line));
                continue;
            }
            m = PHP_REQUIRE.matcher(line);
            while (m.find()) {
                String path = m.group(1);
                specs.add(spec(Kind.PATH, path.startsWith("/") ? "." + path : path, 0, List.of(), i, line));
            }
            declaredAndNames(line, i, 0, declared, names);
        }
        return new Parsed(Lang.PHP, pkg, List.copyOf(declared), names, specs);
    }

    private static Parsed parsePython(String text) {
        List<Spec> specs = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            Matcher m = PY_FROM.matcher(line);
            if (m.find()) {
                List<String> imported = new ArrayList<>();
                for (String part : m.group(3).replace("(", "").replace(")", "").split(",")) {
                    String name = part.strip().split("\\s+")[0];
                    if (name.matches("\\w+")) {
                        imported.add(name);
                    }
                }
                specs.add(spec(Kind.PYTHON, m.group(2), m.group(1).length(), imported, i, line));
                continue;
            }
            m = PY_IMPORT.matcher(line);
            if (m.find()) {
                for (String part : m.group(1).split(",")) {
                    String module = part.strip().split("\\s+")[0];
                    if (!module.isEmpty()) {
                        specs.add(spec(Kind.PYTHON, module, 0, List.of(), i, line));
                    }
                }
            }
        }
        return new Parsed(Lang.PYTHON, "", List.of(), Map.of(), specs);
    }

    private static Parsed parseScript(String text) {
        List<Spec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] lineStarts = lineStarts(text);
        for (Pattern pattern : List.of(SCRIPT_FROM, SCRIPT_BARE, SCRIPT_CALL)) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                String target = m.group(1);
                if (!(target.startsWith(".") || target.startsWith("@/") || target.startsWith("~/"))
                        || !seen.add(target + "@" + m.start(1))) {
                    continue;
                }
                int line = lineOf(lineStarts, m.start(1));
                specs.add(spec(Kind.PATH, target, 0, List.of(), line, lineText(text, lineStarts, line)));
            }
        }
        specs.sort(java.util.Comparator.comparingInt(Spec::line));
        return new Parsed(Lang.SCRIPT, "", List.of(), Map.of(), specs);
    }

    private static Parsed parseGo(String text) {
        List<Spec> specs = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        boolean block = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (block) {
                if (line.strip().startsWith(")")) {
                    block = false;
                    continue;
                }
                Matcher m = GO_IN_BLOCK.matcher(line);
                if (m.find()) {
                    specs.add(spec(Kind.GO, m.group(1), 0, List.of(), i, line));
                }
                continue;
            }
            if (line.matches("^\\s*import\\s*\\(\\s*$")) {
                block = true;
                continue;
            }
            Matcher m = GO_SINGLE.matcher(line);
            if (m.find()) {
                specs.add(spec(Kind.GO, m.group(1), 0, List.of(), i, line));
            }
        }
        return new Parsed(Lang.GO, "", List.of(), Map.of(), specs);
    }

    private static Parsed parseRust(String text) {
        List<Spec> specs = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (commentLine(line)) {
                continue;
            }
            Matcher m = RUST_MOD_LINE.matcher(line);
            if (m.find()) {
                specs.add(spec(Kind.RUST_MOD, m.group(1), 0, List.of(), i, line));
                continue;
            }
            m = RUST_USE_LINE.matcher(line);
            if (m.find()) {
                String tree = m.group(1).replaceAll("\\s+as\\s+\\w+", "").replaceAll("\\s+", "");
                for (String path : rustPaths(tree)) {
                    specs.add(spec(Kind.RUST_USE, path, 0, List.of(), i, line));
                }
            }
        }
        return new Parsed(Lang.RUST, "", List.of(), Map.of(), specs);
    }

    /** A {@code use} tree's paths, one level of braces opened: {@code a::{b, c::D}} → {@code a::b}, {@code a::c::D}. */
    static List<String> rustPaths(String tree) {
        String plain = tree;
        int brace = plain.indexOf('{');
        if (brace < 0) {
            return List.of(stripGlob(plain));
        }
        String base = plain.substring(0, brace);
        int close = plain.lastIndexOf('}');
        String inner = close > brace ? plain.substring(brace + 1, close) : plain.substring(brace + 1);
        List<String> paths = new ArrayList<>();
        int depth = 0;
        StringBuilder part = new StringBuilder();
        for (char c : (inner + ",").toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                String p = part.toString();
                int nested = p.indexOf('{');
                p = nested >= 0 ? p.substring(0, nested) : p;
                if (!p.isEmpty()) {
                    paths.add(stripGlob(p.equals("self") ? base : base + p));
                }
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        return paths;
    }

    private static String stripGlob(String path) {
        String p = path.endsWith("::*") ? path.substring(0, path.length() - 3) : path;
        return p.endsWith("::") ? p.substring(0, p.length() - 2) : p;
    }

    private static Parsed parseRuby(String text) {
        List<Spec> specs = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = RUBY_RELATIVE.matcher(lines[i]);
            if (m.find()) {
                String target = m.group(1);
                specs.add(spec(Kind.PATH, target.endsWith(".rb") ? target : target + ".rb", 0, List.of(), i, lines[i]));
                continue;
            }
            m = RUBY_REQUIRE.matcher(lines[i]);
            if (m.find()) {
                String target = m.group(1);
                specs.add(spec(
                        Kind.LIBRARY, target.endsWith(".rb") ? target : target + ".rb", 0, List.of(), i, lines[i]));
            }
        }
        return new Parsed(Lang.RUBY, "", List.of(), Map.of(), specs);
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int lineOf(int[] starts, int offset) {
        int found = java.util.Arrays.binarySearch(starts, offset);
        return found >= 0 ? found : -found - 2;
    }

    private static String lineText(String text, int[] starts, int line) {
        int end = line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
        return text.substring(starts[line], Math.max(starts[line], end));
    }

    // --- resolving ------------------------------------------------------------------------------------------------

    /**
     * Every edge between the project's files: {@code parsed} maps each read file's root-relative path to what it says,
     * {@code goModules} each {@code go.mod}'s folder ({@code ""} for the root) to the module path it declares.
     */
    static List<Edge> resolve(Map<String, Parsed> parsed, Map<String, String> goModules) {
        Resolver resolver = new Resolver(parsed, goModules);
        List<Edge> edges = new ArrayList<>();
        List<String> files = new ArrayList<>(parsed.keySet());
        files.sort(null);
        for (String from : files) {
            edges.addAll(resolver.edgesOf(from));
        }
        return edges;
    }

    /** The lookups built once over every read file. */
    private static final class Resolver {
        private final Map<String, Parsed> parsed;
        private final Set<String> files;
        /** A type's full name (dots) → its file. */
        private final Map<String, String> types = new HashMap<>();
        /** A package → its types' simple names → their files. */
        private final Map<String, Map<String, String>> packages = new HashMap<>();
        /** A Python module's dotted tail → the files it could be. */
        private final Map<String, List<String>> pyModules = new HashMap<>();
        /** A file's name → its paths. */
        private final Map<String, List<String>> byName = new HashMap<>();
        /** A folder → its Go files, tests left out. */
        private final Map<String, List<String>> goFolders = new HashMap<>();

        private final Map<String, String> goModules;

        Resolver(Map<String, Parsed> parsed, Map<String, String> goModules) {
            this.parsed = parsed;
            this.files = parsed.keySet();
            this.goModules = goModules;
            for (Map.Entry<String, Parsed> entry : parsed.entrySet()) {
                String rel = entry.getKey();
                Parsed p = entry.getValue();
                byName.computeIfAbsent(fileName(rel), k -> new ArrayList<>()).add(rel);
                switch (p.lang()) {
                    case JAVA, KOTLIN, GROOVY, SCALA, CSHARP, PHP -> {
                        Set<String> declared = new java.util.LinkedHashSet<>(p.declared());
                        String stem = stem(fileName(rel));
                        if (!stem.isEmpty() && Character.isUpperCase(stem.charAt(0))) {
                            declared.add(stem);
                        }
                        Map<String, String> members = packages.computeIfAbsent(p.pkg(), k -> new HashMap<>());
                        for (String name : declared) {
                            types.putIfAbsent(p.pkg().isEmpty() ? name : p.pkg() + "." + name, rel);
                            members.putIfAbsent(name, rel);
                        }
                    }
                    case PYTHON -> {
                        String module = rel.substring(0, rel.lastIndexOf('.')).replace('/', '.');
                        if (module.endsWith(".__init__")) {
                            module = module.substring(0, module.length() - ".__init__".length());
                        }
                        String[] parts = module.split("\\.");
                        for (int i = parts.length - 1; i >= 0; i--) {
                            String tail = String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
                            pyModules
                                    .computeIfAbsent(tail, k -> new ArrayList<>())
                                    .add(rel);
                        }
                    }
                    case GO -> {
                        if (!rel.endsWith("_test.go")) {
                            goFolders
                                    .computeIfAbsent(folderOf(rel), k -> new ArrayList<>())
                                    .add(rel);
                        }
                    }
                    default -> {}
                }
            }
        }

        List<Edge> edgesOf(String from) {
            Parsed p = parsed.get(from);
            if (p == null) {
                return List.of();
            }
            Map<String, Edge> found = new LinkedHashMap<>();
            for (Spec spec : p.specs()) {
                for (String to : targets(from, p, spec)) {
                    if (!to.equals(from)) {
                        found.putIfAbsent(to, new Edge(from, to, spec.line(), spec.text()));
                    }
                }
            }
            switch (p.lang()) {
                case JAVA, KOTLIN, GROOVY, SCALA, CSHARP, PHP -> {
                    // A type of its own package (or namespace), named without an import.
                    namedFrom(from, p, p.pkg(), found);
                }
                default -> {}
            }
            return new ArrayList<>(found.values());
        }

        private void namedFrom(String from, Parsed p, String pkg, Map<String, Edge> found) {
            Map<String, String> members = packages.get(pkg);
            if (members == null) {
                return;
            }
            for (Map.Entry<String, Integer> name : p.names().entrySet()) {
                String to = members.get(name.getKey());
                if (to != null && !to.equals(from) && !found.containsKey(to)) {
                    found.put(to, new Edge(from, to, name.getValue(), name.getKey()));
                }
            }
        }

        private List<String> targets(String from, Parsed p, Spec spec) {
            String dir = folderOf(from);
            return switch (spec.kind()) {
                case TYPE -> {
                    String name = spec.target();
                    while (name.indexOf('.') > 0) {
                        String to = types.get(name);
                        if (to != null) {
                            yield List.of(to);
                        }
                        name = name.substring(0, name.lastIndexOf('.'));
                    }
                    yield List.of();
                }
                case PACKAGE -> {
                    Map<String, Edge> named = new LinkedHashMap<>();
                    namedFrom(from, p, spec.target(), named);
                    yield new ArrayList<>(named.keySet());
                }
                case PATH -> {
                    String target = spec.target();
                    if (target.startsWith("@/") || target.startsWith("~/")) {
                        yield aliased(dir, target.substring(2), p.lang());
                    }
                    String to = withExtensions(join(dir, target), p.lang());
                    yield to == null ? List.of() : List.of(to);
                }
                case PYTHON -> python(dir, spec);
                case GO -> go(spec.target());
                case RUST_MOD -> {
                    String base = rustSelf(from);
                    String to = firstFile(base + "/" + spec.target() + ".rs", base + "/" + spec.target() + "/mod.rs");
                    yield to == null ? List.of() : List.of(to);
                }
                case RUST_USE -> rust(from, spec.target());
                case INCLUDE -> include(dir, spec);
                case LIBRARY -> {
                    String to = nearest(dir, suffixed("lib/" + spec.target()));
                    yield to == null ? List.of() : List.of(to);
                }
            };
        }

        private List<String> aliased(String dir, String rest, Lang lang) {
            for (String base = dir; ; base = folderOf(base)) {
                String to = withExtensions(join(base, "src/" + rest), lang);
                if (to != null) {
                    return List.of(to);
                }
                if (base.isEmpty()) {
                    return List.of();
                }
            }
        }

        private @Nullable String withExtensions(@Nullable String path, Lang lang) {
            if (path == null) {
                return null;
            }
            if (files.contains(path)) {
                return path;
            }
            if (lang != Lang.SCRIPT) {
                return null;
            }
            String stem = path.matches(".*\\.[mc]?js$") ? path.substring(0, path.lastIndexOf('.')) : path;
            for (String ext :
                    List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", ".cts", ".vue", ".svelte")) {
                if (files.contains(stem + ext)) {
                    return stem + ext;
                }
            }
            for (String ext : List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs")) {
                String index = (path.isEmpty() ? "" : path + "/") + "index" + ext;
                if (files.contains(index)) {
                    return index;
                }
            }
            return null;
        }

        private List<String> python(String dir, Spec spec) {
            String module = spec.target();
            if (spec.level() > 0) {
                String base = dir;
                for (int i = 1; i < spec.level(); i++) {
                    if (base.isEmpty()) {
                        return List.of();
                    }
                    base = folderOf(base);
                }
                String folder = module.isEmpty() ? base : join(base, module.replace('.', '/'));
                if (folder == null) {
                    return List.of();
                }
                List<String> found = new ArrayList<>();
                for (String name : spec.names()) {
                    String to = pyFile(join(folder, name));
                    if (to != null) {
                        found.add(to);
                    }
                }
                if (found.isEmpty()) {
                    String to = pyFile(folder);
                    if (to != null) {
                        found.add(to);
                    }
                }
                return found;
            }
            List<String> found = new ArrayList<>();
            for (String name : spec.names()) {
                String to = pyModule(dir, module + "." + name);
                if (to != null) {
                    found.add(to);
                }
            }
            if (found.isEmpty()) {
                String to = pyModule(dir, module);
                if (to != null) {
                    found.add(to);
                }
            }
            return found;
        }

        private @Nullable String pyFile(@Nullable String path) {
            return path == null ? null : firstFile(path + ".py", path + ".pyi", path + "/__init__.py");
        }

        /**
         * The file of the absolute module {@code module} imported from the folder {@code dir}: of those whose path ends
         * with it, one whose folder above the module's own is {@code dir} or a folder above it (the root, a
         * {@code src} folder, the script's own folder — where Python looks), the nearest. So {@code import logging}
         * does not become a project's own {@code tools/logging.py}.
         */
        private @Nullable String pyModule(String dir, String module) {
            List<String> candidates = pyModules.get(module);
            if (candidates == null) {
                return null;
            }
            int depth = module.split("\\.").length;
            List<String> reachable = new ArrayList<>();
            for (String candidate : candidates) {
                String top = candidate.endsWith("/__init__.py") ? folderOf(candidate) : candidate;
                for (int i = 0; i < depth; i++) {
                    top = folderOf(top);
                }
                if (top.isEmpty() || dir.equals(top) || dir.startsWith(top + "/")) {
                    reachable.add(candidate);
                }
            }
            return nearest(dir, reachable);
        }

        private List<String> go(String importPath) {
            String best = null;
            String bestModule = "";
            for (Map.Entry<String, String> module : goModules.entrySet()) {
                String path = module.getValue();
                if ((importPath.equals(path) || importPath.startsWith(path + "/"))
                        && path.length() > bestModule.length()) {
                    best = module.getKey();
                    bestModule = path;
                }
            }
            if (best == null) {
                return List.of();
            }
            String rest = importPath.substring(bestModule.length());
            String folder = rest.isEmpty() ? best : (best.isEmpty() ? rest.substring(1) : best + rest);
            return goFolders.getOrDefault(folder, List.of());
        }

        private List<String> rust(String from, String path) {
            String[] segments = path.split("::"); // never empty: "" splits to [""]
            int start = 1;
            String up = folderOf(rustSelf(from));
            while (segments[0].equals("super") && start < segments.length && segments[start].equals("super")) {
                up = folderOf(up);
                start++;
            }
            String base = switch (segments[0]) {
                case "crate" -> rustCrate(from);
                case "self" -> rustSelf(from);
                case "super" -> up;
                default -> null; // another crate, or std
            };
            if (base == null) {
                return List.of();
            }
            String match = null;
            String current = base;
            for (int i = start; i < segments.length; i++) {
                String next = current.isEmpty() ? segments[i] : current + "/" + segments[i];
                String to = firstFile(next + ".rs", next + "/mod.rs");
                if (to == null) {
                    break;
                }
                match = to;
                current = next;
            }
            if (match == null && (segments[0].equals("super") || segments[0].equals("crate"))) {
                match = firstFile(
                        base + ".rs",
                        base + "/mod.rs",
                        (base.isEmpty() ? "" : base + "/") + "lib.rs",
                        (base.isEmpty() ? "" : base + "/") + "main.rs");
            }
            return match == null ? List.of() : List.of(match);
        }

        /** The folder a Rust file's own submodules live in. */
        private static String rustSelf(String from) {
            String name = fileName(from);
            String dir = folderOf(from);
            if (name.equals("mod.rs") || name.equals("lib.rs") || name.equals("main.rs")) {
                return dir;
            }
            String stem = stem(name);
            return dir.isEmpty() ? stem : dir + "/" + stem;
        }

        /** The {@code src} folder of the crate holding {@code from} (where its {@code lib.rs} or {@code main.rs} is). */
        private @Nullable String rustCrate(String from) {
            for (String dir = folderOf(from); ; dir = folderOf(dir)) {
                String prefix = dir.isEmpty() ? "" : dir + "/";
                if (files.contains(prefix + "lib.rs") || files.contains(prefix + "main.rs")) {
                    return dir;
                }
                if (dir.isEmpty()) {
                    return null;
                }
            }
        }

        private List<String> include(String dir, Spec spec) {
            String target = spec.target();
            if (spec.level() == 1) {
                String local = join(dir, target);
                if (local != null && files.contains(local)) {
                    return List.of(local);
                }
            }
            List<String> candidates = suffixed(target);
            if (candidates.isEmpty() || spec.level() == 0 && !target.contains("/") && candidates.size() > 1) {
                return List.of();
            }
            String to = nearest(dir, candidates);
            return to == null ? List.of() : List.of(to);
        }

        /** The files whose path is {@code tail} or ends with {@code /tail}. */
        private List<String> suffixed(String tail) {
            String clean = tail.startsWith("./") ? tail.substring(2) : tail;
            List<String> named = byName.get(fileName(clean));
            if (named == null) {
                return List.of();
            }
            List<String> found = new ArrayList<>();
            for (String rel : named) {
                if (rel.equals(clean) || rel.endsWith("/" + clean)) {
                    found.add(rel);
                }
            }
            return found;
        }

        private @Nullable String firstFile(String... candidates) {
            for (String candidate : candidates) {
                if (files.contains(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /** Of {@code candidates}, the one nearest to the folder {@code dir}: the longest shared folder, then the shortest. */
    static @Nullable String nearest(String dir, List<String> candidates) {
        String best = null;
        int bestShared = -1;
        for (String candidate : candidates) {
            int shared = sharedSegments(dir, folderOf(candidate));
            if (shared > bestShared || shared == bestShared && best != null && candidate.length() < best.length()) {
                best = candidate;
                bestShared = shared;
            }
        }
        return best;
    }

    private static int sharedSegments(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        String[] x = a.split("/");
        String[] y = b.split("/");
        int n = 0;
        while (n < x.length && n < y.length && x[n].equals(y[n])) {
            n++;
        }
        return n;
    }

    /** {@code rel} under the folder {@code dir}, {@code .} and {@code ..} worked out; null when it leaves the root. */
    static @Nullable String join(String dir, String rel) {
        List<String> parts = new ArrayList<>();
        if (!rel.startsWith("/") && !dir.isEmpty()) {
            parts.addAll(List.of(dir.split("/")));
        }
        for (String part : rel.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
            } else {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }

    /** The folder of a root-relative path; {@code ""} for the root's own. */
    static String folderOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    static String fileName(String rel) {
        return rel.substring(rel.lastIndexOf('/') + 1);
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
}
