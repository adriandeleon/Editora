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
     */
    public static boolean compiledFrom(String classSourcePath, Path file) {
        if (classSourcePath == null || classSourcePath.isBlank() || file == null) {
            return false;
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

    /**
     * Whether a language lets a file's package differ from its folder. Scala does ({@code package demo} in
     * {@code src/Main.scala} is ordinary, and Scala CLI projects are flat), so its classes are matched by file
     * name alone — the program's sources are the ones on its command line, where two files of one name are rare.
     * Groovy, like Java, keeps them together in practice, and is matched by the whole path.
     */
    public static boolean packagesFollowFolders(String language) {
        return !"scala".equals(language == null ? "" : language.toLowerCase(Locale.ROOT));
    }

    /**
     * As {@link #compiledFrom(String, Path)}, for {@code language}: by file name alone where its packages need
     * not follow folders ({@link #packagesFollowFolders}).
     */
    public static boolean compiledFrom(String classSourcePath, Path file, String language) {
        if (packagesFollowFolders(language)) {
            return compiledFrom(classSourcePath, file);
        }
        if (classSourcePath == null || classSourcePath.isBlank() || file == null) {
            return false;
        }
        String normalized = classSourcePath.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.equals(sourceName(file));
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
        return resolve(classSourcePath, knownFiles, roots, exists, "");
    }

    /**
     * As {@link #resolve(String, List, List, java.util.function.Predicate)}, for {@code language}: where its
     * packages need not follow folders, a known file matches by name, and a root is also searched for the bare
     * file name ({@code demo/Main.scala} found as {@code <root>/Main.scala}).
     */
    public static Path resolve(
            String classSourcePath,
            List<Path> knownFiles,
            List<Path> roots,
            java.util.function.Predicate<Path> exists,
            String language) {
        if (classSourcePath == null || classSourcePath.isBlank()) {
            return null;
        }
        if (knownFiles != null) {
            for (Path known : knownFiles) {
                if (compiledFrom(classSourcePath, known, language)) {
                    return known;
                }
            }
        }
        if (!packagesFollowFolders(language) && roots != null) {
            String relative = classSourcePath.replace('\\', '/');
            String name = relative.substring(relative.lastIndexOf('/') + 1);
            for (Path root : roots) {
                if (root == null) {
                    continue;
                }
                Path candidate = root.resolve(relative).normalize();
                if (exists.test(candidate)) {
                    return candidate;
                }
                Path flat = root.resolve(name).normalize();
                if (exists.test(flat)) {
                    return flat;
                }
            }
            return null;
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
     * closures' plumbing), which sits between nearly every two calls a script makes.
     */
    public static List<String> stepFilters(String language) {
        List<String> filters = new ArrayList<>(
                List.of("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "org.codehaus.groovy.*", "groovy.lang.*"));
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        if ("groovy".equals(lang)) {
            // groovyjarjar* is the parser, ASM and picocli Groovy bundles under renamed packages.
            filters.addAll(List.of("groovyjarjar*", "org.apache.groovy.*", "groovy.runtime.*"));
        } else if ("scala".equals(lang)) {
            // The standard library between two lines of a program: boxing and lazy-val plumbing
            // (scala.runtime), the collections a map or foreach passes through, Predef's conversions, function
            // and option wrappers; dotty.* is Scala 3's own runtime support.
            filters.addAll(List.of(
                    "scala.runtime.*",
                    "scala.collection.*",
                    "scala.Predef*",
                    "scala.Function*",
                    "scala.PartialFunction*",
                    "scala.Option*",
                    "scala.Some*",
                    "scala.util.*",
                    "scala.math.*",
                    "scala.LowPriorityImplicits*",
                    "scala.reflect.*",
                    "dotty.*"));
        }
        return List.copyOf(filters);
    }

    /**
     * The name a frame shows: {@code Script.run} — the simple class name and the method, with Groovy's
     * generated closure class read as its enclosing class ({@code Script$_run_closure1.doCall} →
     * {@code Script.run { closure }}).
     */
    public static String frameName(String className, String methodName, String language) {
        if (!"scala".equals(language == null ? "" : language.toLowerCase(Locale.ROOT))) {
            return frameName(className, methodName);
        }
        String cls = className == null ? "" : className;
        int dot = cls.lastIndexOf('.');
        String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
        // Scala CLI's script wrapper (hello$_), Scala 3's top-level definitions (Main$package$), an object's
        // module class (Util$): the name the source uses.
        if (simple.endsWith("$_")) {
            simple = simple.substring(0, simple.length() - 2);
        }
        if (simple.endsWith("$")) {
            simple = simple.substring(0, simple.length() - 1);
        }
        if (simple.endsWith("$package")) {
            simple = simple.substring(0, simple.length() - "$package".length());
        }
        String method = methodName == null ? "" : methodName;
        int anon = method.indexOf("$anonfun");
        if (anon >= 0) {
            // A lambda: Scala 3's greet$$anonfun$1 / $init$$$anonfun$1, Scala 2's $anonfun$greet$1.
            String enclosing = method.substring(0, anon).replaceAll("\\$+$", "");
            if (enclosing.isEmpty()) {
                String rest = method.substring(anon + "$anonfun".length()).replaceAll("^\\$", "");
                enclosing = rest.replaceAll("\\$\\d+$", "").replaceAll("^\\d+$", "");
            }
            if (enclosing.equals("$init") || enclosing.equals("<init>")) {
                return simple + " { lambda }";
            }
            return simple + (enclosing.isEmpty() ? "" : "." + enclosing) + " { lambda }";
        }
        if (method.equals("<init>") || method.equals("<clinit>")) {
            return simple; // an object's or a script's body runs in its initialiser
        }
        return simple + "." + method;
    }

    /**
     * Whether an instance field is compiler plumbing rather than the program's state — a Scala lazy val's
     * initialisation bitmap ({@code bitmap$0}, {@code 0bitmap$1}). Hidden from the Variables view.
     */
    public static boolean hiddenField(String name, String language) {
        return name != null
                && "scala".equals(language == null ? "" : language.toLowerCase(Locale.ROOT))
                && name.contains("bitmap$");
    }

    public static String frameName(String className, String methodName) {
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
}
