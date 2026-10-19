package com.editora.dap.jvm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How Editora's JVM debugger ties a source file to the classes compiled from it, for any JVM language.
 *
 * <p>The microsoft java-debug adapter jdtls hosts finds a breakpoint's class by parsing the file as Java, so a
 * {@code .groovy}, {@code .kt} or {@code .clj} breakpoint never binds there. The class file says it for every
 * language instead: its {@code SourceFile} attribute names the file ({@code Script.groovy}) and its package
 * gives the folder, which JDI reports together as {@code sourcePaths} ({@code com/acme/Script.groovy}). A
 * breakpoint in {@code /work/src/com/acme/Script.groovy} binds in every loaded class whose source path is a
 * suffix of that path — the script class, its closures, its inner classes.
 *
 * <p>Kotlin does not tie a file's folder to its package: {@code src/App.kt} may declare {@code package com.acme},
 * and its classes then say {@code com/acme/App.kt}. Where the file's own {@code package} line is known
 * ({@link #declaredPackage}), that path counts too.
 *
 * <p>Pure.
 */
public final class JvmSourceMap {

    private JvmSourceMap() {}

    /** The file name a class compiled from {@code file} records as its {@code SourceFile}. */
    public static String sourceName(Path file) {
        Path name = file == null ? null : file.getFileName();
        return name == null ? "" : name.toString();
    }

    /**
     * Whether a class whose source path is {@code classSourcePath} ({@code com/acme/Script.groovy}, separated by
     * {@code /} or the platform's separator) was compiled from {@code file}: the path's segments end the file's.
     * A class in the default package ({@code Script.groovy}) matches any file of that name — the scripts a
     * debug session runs are named on its command line, so two of the same name in one program are rare.
     *
     * <p>An absolute source path is the file's own: Clojure records the path a script was loaded from
     * ({@code clojure -M /work/tally.clj}) rather than a classpath-relative one.
     */
    public static boolean compiledFrom(String classSourcePath, Path file) {
        if (classSourcePath == null || classSourcePath.isBlank() || file == null) {
            return false;
        }
        if (isAbsolute(classSourcePath)) {
            try {
                return Path.of(classSourcePath)
                        .normalize()
                        .equals(file.toAbsolutePath().normalize());
            } catch (java.nio.file.InvalidPathException e) {
                return false;
            }
        }
        String[] parts = classSourcePath.replace('\\', '/').split("/");
        int n = file.getNameCount();
        if (parts.length > n) {
            return false;
        }
        for (int i = 0; i < parts.length; i++) {
            String want = parts[parts.length - 1 - i];
            String have = file.getName(n - 1 - i).toString();
            if (!want.equals(have)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code path} is absolute on any platform: {@code /work/x.clj}, {@code C:\\work\\x.clj}, a UNC path. */
    static boolean isAbsolute(String path) {
        return path.startsWith("/")
                || path.startsWith("\\\\")
                || (path.length() > 2
                        && Character.isLetter(path.charAt(0))
                        && path.charAt(1) == ':'
                        && (path.charAt(2) == '\\' || path.charAt(2) == '/'));
    }

    /**
     * Whether a class whose source path is {@code classSourcePath} was compiled from {@code file}, which declares
     * {@code declaredPackage} ({@code ""} or null when unknown): as {@link #compiledFrom(String, Path)}, or the
     * package's folder and the file's name — a Kotlin file need not sit in its package's folder.
     */
    public static boolean compiledFrom(String classSourcePath, Path file, String declaredPackage) {
        if (compiledFrom(classSourcePath, file)) {
            return true;
        }
        if (declaredPackage == null || declaredPackage.isBlank() || classSourcePath == null) {
            return false;
        }
        String expected = declaredPackage.strip().replace('.', '/') + "/" + sourceName(file);
        return classSourcePath.replace('\\', '/').equals(expected);
    }

    /** {@code package a.b.c} at the start of a line, the name ending the statement (Kotlin's needs no {@code ;}). */
    private static final java.util.regex.Pattern PACKAGE =
            java.util.regex.Pattern.compile("^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)");

    /** The first lines of a file's code that end its header: no {@code package} line comes after one of them. */
    private static final java.util.regex.Pattern DECLARATION = java.util.regex.Pattern.compile(
            "^\\s*(?:import|class|interface|object|enum|record|fun|val|var|def|trait|abstract|public|private"
                    + "|internal|data|sealed|open|final)\\b");

    /**
     * The package {@code text} (a Java, Kotlin, Groovy or Scala source file) declares in its header, or
     * {@code ""} for the default package. File annotations ({@code @file:JvmName}), comments and a shebang may
     * come first.
     */
    public static String declaredPackage(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.split("\\R", 400);
        boolean inComment = false;
        for (int i = 0; i < Math.min(lines.length, 300); i++) {
            String line = lines[i];
            if (inComment) {
                int end = line.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                inComment = false;
                line = line.substring(end + 2);
            }
            String code = line.strip();
            if (code.startsWith("/*")) {
                int end = code.indexOf("*/", 2);
                if (end < 0) {
                    inComment = true;
                    continue;
                }
                code = code.substring(end + 2).strip();
            }
            java.util.regex.Matcher m = PACKAGE.matcher(code);
            if (m.find()) {
                return m.group(1).replaceAll("\\s+", "");
            }
            if (DECLARATION.matcher(code).find()) {
                return "";
            }
        }
        return "";
    }

    /**
     * As {@link #resolve(String, List, List, java.util.function.Predicate)}, matching the known files by their
     * declared packages too ({@link #compiledFrom(String, Path, String)}).
     */
    public static Path resolve(
            String classSourcePath,
            List<Path> knownFiles,
            java.util.function.Function<Path, String> packageOf,
            List<Path> roots,
            java.util.function.Predicate<Path> exists) {
        if (classSourcePath != null && knownFiles != null && packageOf != null) {
            for (Path known : knownFiles) {
                if (compiledFrom(classSourcePath, known, packageOf.apply(known))) {
                    return known;
                }
            }
        }
        return resolve(classSourcePath, knownFiles, roots, exists);
    }

    /**
     * The file a frame's class came from, given its source path ({@code com/acme/Script.groovy}): the first of
     * {@code knownFiles} (the files that have breakpoints) it was compiled from, else that path under the first
     * of {@code roots} where {@code exists} finds it; null when it is nowhere — the frame then shows no source.
     */
    public static Path resolve(
            String classSourcePath,
            List<Path> knownFiles,
            List<Path> roots,
            java.util.function.Predicate<Path> exists) {
        if (classSourcePath == null || classSourcePath.isBlank()) {
            return null;
        }
        if (knownFiles != null) {
            for (Path known : knownFiles) {
                if (compiledFrom(classSourcePath, known)) {
                    return known;
                }
            }
        }
        if (roots != null) {
            String relative = classSourcePath.replace('\\', '/');
            for (Path root : roots) {
                if (root == null) {
                    continue;
                }
                Path candidate = root.resolve(relative).normalize();
                if (exists.test(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Class patterns a step never stops in for {@code language} — the runtime between two lines of the
     * program. JDK internals for every language; for Groovy its dynamic dispatch (call sites, meta classes,
     * closures' plumbing), which sits between nearly every two calls a script makes; for Clojure its runtime
     * ({@code clojure.lang}: vars, functions' {@code invoke} plumbing, persistent collections), {@code clojure.core}'s
     * own functions and {@code clojure.main}, which loads the program.
     */
    public static List<String> stepFilters(String language) {
        List<String> filters = new ArrayList<>(
                List.of("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "org.codehaus.groovy.*", "groovy.lang.*"));
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        if ("groovy".equals(lang)) {
            // groovyjarjar* is the parser, ASM and picocli Groovy bundles under renamed packages.
            filters.addAll(List.of("groovyjarjar*", "org.apache.groovy.*", "groovy.runtime.*"));
        }
        if ("kotlin".equals(lang)) {
            // The standard library (collections' inline helpers are inlined, the rest is library code) and the
            // coroutines machinery between a suspend call and its continuation; the script host of a .kts.
            filters.addAll(List.of("kotlin.*", "kotlinx.coroutines.*", "org.jetbrains.kotlin.*"));
        }
        if ("clojure".equals(lang)) {
            // clojure.core* covers clojure.core$map… and the core libraries (clojure.core.server,
            // clojure.core.protocols).
            filters.addAll(List.of("clojure.lang.*", "clojure.core*", "clojure.main*", "clojure.spec.*"));
        }
        return List.copyOf(filters);
    }

    /**
     * The name a frame shows: {@code Script.run} — the simple class name and the method, with Groovy's
     * generated closure class read as its enclosing class ({@code Script$_run_closure1.doCall} →
     * {@code Script.run { closure }}).
     */
    public static String frameName(String className, String methodName) {
        return frameName(className, methodName, null);
    }

    /** A Kotlin lambda compiled to a method: {@code main$lambda$0}, also {@code main$lambda$0$1} when nested. */
    private static final java.util.regex.Pattern KOTLIN_LAMBDA_METHOD =
            java.util.regex.Pattern.compile("^(.+?)\\$lambda(?:\\$\\d+)+$");

    /** A Kotlin lambda compiled to a class of its own: {@code HelloKt$main$1}, its body {@code invoke}. */
    private static final java.util.regex.Pattern KOTLIN_LAMBDA_CLASS =
            java.util.regex.Pattern.compile("^([^$]+)\\$([^$]+)\\$\\d+$");

    /**
     * As {@link #frameName(String, String)}, knowing the file the class says it came from ({@code sourceName}):
     * a Kotlin file's top-level functions live in {@code <File>Kt}, which reads as the file
     * ({@code HelloKt.main} → {@code Hello.main}), and its lambdas as the function that holds them
     * ({@code HelloKt.main$lambda$0} or {@code HelloKt$main$1.invoke} → {@code Hello.main { lambda }}); a
     * {@code $default} bridge reads as its function. A Clojure function's class ({@code .clj}, {@code .cljc})
     * reads as the function, demunged ({@code my_app.core$greet.invokeStatic} → {@code my-app.core/greet},
     * {@code my_app.core$valid_QMARK_.invoke} → {@code my-app.core/valid?}).
     */
    public static String frameName(String className, String methodName, String sourceName) {
        boolean kotlin = sourceName != null && (sourceName.endsWith(".kt") || sourceName.endsWith(".kts"));
        if (kotlin) {
            return kotlinFrameName(className, methodName, sourceName);
        }
        boolean clojure = sourceName != null && (sourceName.endsWith(".clj") || sourceName.endsWith(".cljc"));
        if (clojure && className != null && CLOJURE_FN_METHODS.contains(methodName)) {
            int dollar = className.indexOf('$');
            if (dollar > 0 && dollar < className.length() - 1) {
                return demunge(className.substring(0, dollar)) + "/"
                        + demunge(className.substring(dollar + 1).replace('$', '/'));
            }
        }
        String cls = className == null ? "" : className;
        int dot = cls.lastIndexOf('.');
        String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
        int closure = simple.indexOf("$_");
        if (closure > 0 && "doCall".equals(methodName)) {
            String outer = simple.substring(0, closure);
            String rest = simple.substring(closure + 2);
            int underscore = rest.indexOf("_closure");
            String enclosing = underscore > 0 ? rest.substring(0, underscore) : rest;
            return outer + "." + enclosing + " { closure }";
        }
        return simple + "." + (methodName == null ? "" : methodName);
    }

    private static String kotlinFrameName(String className, String methodName, String sourceName) {
        String cls = className == null ? "" : className;
        int dot = cls.lastIndexOf('.');
        String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
        String method = methodName == null ? "" : methodName;
        String base = sourceName.substring(0, sourceName.lastIndexOf('.'));
        String fileClass = base + "Kt";
        String lambda = null;
        java.util.regex.Matcher inner = KOTLIN_LAMBDA_CLASS.matcher(simple);
        if (("invoke".equals(method) || "invokeSuspend".equals(method)) && inner.matches()) {
            simple = inner.group(1);
            lambda = inner.group(2);
        } else {
            java.util.regex.Matcher m = KOTLIN_LAMBDA_METHOD.matcher(method);
            if (m.matches()) {
                lambda = m.group(1);
            }
        }
        if (simple.equals(fileClass)) {
            simple = base;
        }
        if (lambda != null) {
            return simple + "." + lambda + " { lambda }";
        }
        if (method.endsWith("$default")) {
            method = method.substring(0, method.length() - "$default".length());
        }
        return simple + "." + method;
    }

    /** The methods through which Clojure calls a function's body. */
    private static final java.util.Set<String> CLOJURE_FN_METHODS =
            java.util.Set.of("invoke", "invokeStatic", "doInvoke", "invokePrim", "applyTo");

    /** Clojure's munged names for the characters a JVM name cannot hold ({@code clojure.lang.Compiler.CHAR_MAP}). */
    private static final List<String[]> MUNGED = List.of(
            new String[] {"_QMARK_", "?"},
            new String[] {"_BANG_", "!"},
            new String[] {"_STAR_", "*"},
            new String[] {"_PLUS_", "+"},
            new String[] {"_GT_", ">"},
            new String[] {"_LT_", "<"},
            new String[] {"_EQ_", "="},
            new String[] {"_SLASH_", "/"},
            new String[] {"_COLON_", ":"},
            new String[] {"_SINGLEQUOTE_", "'"},
            new String[] {"_AMPERSAND_", "&"},
            new String[] {"_PERCENT_", "%"},
            new String[] {"_CARET_", "^"},
            new String[] {"_BAR_", "|"},
            new String[] {"_TILDE_", "~"},
            new String[] {"_AT_", "@"},
            new String[] {"_SHARP_", "#"},
            new String[] {"_DOT_", "."},
            new String[] {"_LBRACK_", "["},
            new String[] {"_RBRACK_", "]"},
            new String[] {"_LBRACE_", "{"},
            new String[] {"_RBRACE_", "}"},
            new String[] {"_DOUBLEQUOTE_", "\""},
            new String[] {"_BSLASH_", "\\"});

    /**
     * A munged Clojure name as written: the character names back to characters, then each remaining {@code _}
     * a {@code -} ({@code valid_QMARK_} → {@code valid?}, {@code my_app} → {@code my-app}). Pure.
     */
    static String demunge(String munged) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        outer:
        while (i < munged.length()) {
            if (munged.charAt(i) == '_') {
                for (String[] m : MUNGED) {
                    if (munged.startsWith(m[0], i)) {
                        out.append(m[1]);
                        i += m[0].length();
                        continue outer;
                    }
                }
                out.append('-');
            } else {
                out.append(munged.charAt(i));
            }
            i++;
        }
        return out.toString();
    }
}
