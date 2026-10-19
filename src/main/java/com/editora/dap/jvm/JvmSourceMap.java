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
     * closures' plumbing), which sits between nearly every two calls a script makes.
     */
    public static List<String> stepFilters(String language) {
        List<String> filters = new ArrayList<>(
                List.of("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "org.codehaus.groovy.*", "groovy.lang.*"));
        if ("groovy".equals(language == null ? "" : language.toLowerCase(Locale.ROOT))) {
            // groovyjarjar* is the parser, ASM and picocli Groovy bundles under renamed packages.
            filters.addAll(List.of("groovyjarjar*", "org.apache.groovy.*", "groovy.runtime.*"));
        }
        return List.copyOf(filters);
    }

    /**
     * The name a frame shows: {@code Script.run} — the simple class name and the method, with Groovy's
     * generated closure class read as its enclosing class ({@code Script$_run_closure1.doCall} →
     * {@code Script.run { closure }}).
     */
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
