package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How Run and Debug start a Scala file: on its own through Scala CLI, as part of a Scala CLI project
 * ({@code project.scala}), or through the sbt or Mill build it belongs to — and the program's main class, read
 * off the source.
 *
 * <p>A file of an sbt or Mill build needs that build's classpath, which only the build knows: Run asks it
 * ({@code sbt "runMain demo.hello"}, {@code ./mill core.runMain demo.hello}) and Debug asks Metals, which has
 * imported it. A file of a Gradle or Maven build is run from that build's tool window. Anything else runs
 * through Scala CLI, which reads a script's {@code //> using} directives for its dependencies.
 *
 * <p>Pure: the caller supplies what is on disk ({@code isFile}).
 */
public final class ScalaRun {

    private ScalaRun() {}

    /** The build a Scala file belongs to. */
    public enum Build {
        /** An sbt build ({@code build.sbt}). */
        SBT,
        /** A Mill build ({@code build.mill}, {@code build.mill.yaml}, or 0.x's {@code build.sc}). */
        MILL,
        /** A Scala CLI project folder ({@code project.scala}). */
        SCALA_CLI,
        /** A Gradle or Maven build. */
        OTHER,
        /** None: the file runs on its own. */
        NONE
    }

    /** Where a Scala file belongs: its build and that build's root folder (null for {@link Build#NONE}). */
    public record Owner(Build build, Path root) {}

    private static final List<String> MILL_MARKERS = List.of("build.mill", "build.mill.yaml", "build.sc");
    private static final List<String> OTHER_MARKERS =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");

    /**
     * The build {@code file} belongs to, looking in its folder and each one above it up to {@code top}
     * (inclusive; null: to the filesystem root). The nearest marker wins, except that sbt's root is the
     * <em>highest</em> folder with a {@code build.sbt}: an sbt subproject may have a {@code build.sbt} of its
     * own, and sbt runs from the root. A file outside every build belongs to none.
     */
    public static Owner ownerOf(Path file, Path top, Predicate<Path> isFile) {
        if (file == null || file.getParent() == null) {
            return new Owner(Build.NONE, null);
        }
        Path stop = top == null ? null : top.toAbsolutePath().normalize();
        Path start = file.toAbsolutePath().normalize().getParent();
        if (stop != null && !start.startsWith(stop)) {
            stop = null; // a file outside the project: look up to the root
        }
        Path sbtRoot = null;
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (isFile.test(dir.resolve("build.sbt"))) {
                sbtRoot = dir; // keep going: a higher build.sbt is the build's root
            } else if (sbtRoot == null) {
                Path here = dir;
                Build other = MILL_MARKERS.stream().anyMatch(m -> isFile.test(here.resolve(m)))
                        ? Build.MILL
                        : isFile.test(here.resolve("project.scala"))
                                ? Build.SCALA_CLI
                                : OTHER_MARKERS.stream().anyMatch(m -> isFile.test(here.resolve(m)))
                                        ? Build.OTHER
                                        : null;
                if (other != null) {
                    return new Owner(other, dir);
                }
            }
            if (dir.equals(stop)) {
                break;
            }
        }
        return sbtRoot != null ? new Owner(Build.SBT, sbtRoot) : new Owner(Build.NONE, null);
    }

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([A-Za-z_][\\w.]*)\\s*(?:\\{|$|;)");
    /** {@code @main def hello(…)} (Scala 3): the program is a class named after the method. */
    private static final Pattern MAIN_ANNOTATION = Pattern.compile("^\\s*@main\\s+def\\s+([A-Za-z_][\\w]*|`[^`]+`)");
    /** {@code object App extends App} / {@code … extends App with …} (Scala 2 style, still compiled by Scala 3). */
    private static final Pattern APP_OBJECT =
            Pattern.compile("^\\s*(?:case\\s+)?object\\s+([A-Za-z_][\\w]*)\\s+extends\\s+(?:scala\\.)?App\\b");
    /** {@code object Main} — a candidate holder of a {@code def main}. */
    private static final Pattern OBJECT = Pattern.compile("^\\s*(?:case\\s+)?object\\s+([A-Za-z_][\\w]*)\\b");
    /** {@code def main(args: Array[String])}. */
    private static final Pattern MAIN_METHOD =
            Pattern.compile("^\\s*(?:override\\s+)?def\\s+main\\s*\\(\\s*\\w+\\s*:\\s*Array\\s*\\[\\s*String\\s*\\]");

    /** A program entry point: its 0-based line and the class the JVM starts ({@code demo.hello}). */
    public record Main(int line, String mainClass) {}

    /**
     * The program entry points in {@code text}, in order: {@code @main def}s, objects that extend {@code App},
     * and objects with a {@code def main(args: Array[String])}. Names are qualified by the file's (first)
     * package clause.
     */
    public static List<Main> mains(String text) {
        List<Main> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        String pkg = "";
        String object = null;
        int objectLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = PACKAGE.matcher(line);
            if (m.find()) {
                pkg = pkg.isEmpty() ? m.group(1) : pkg + "." + m.group(1);
                continue;
            }
            if ((m = MAIN_ANNOTATION.matcher(line)).find()) {
                out.add(new Main(i, qualify(pkg, unquote(m.group(1)))));
            } else if ((m = APP_OBJECT.matcher(line)).find()) {
                out.add(new Main(i, qualify(pkg, m.group(1))));
                object = null;
            } else if ((m = OBJECT.matcher(line)).find()) {
                object = m.group(1);
                objectLine = i;
            } else if (object != null && MAIN_METHOD.matcher(line).find()) {
                out.add(new Main(objectLine, qualify(pkg, object)));
                object = null;
            }
        }
        return out;
    }

    private static String qualify(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /** A back-quoted name ({@code `my app`}) as the class it compiles to is named. */
    private static String unquote(String name) {
        return name.startsWith("`") && name.endsWith("`") && name.length() > 1
                ? name.substring(1, name.length() - 1)
                : name;
    }

    /**
     * The gutter Run line of a Scala file: its first entry point, else line 0 for a {@code .sc} script (it runs
     * from the top), else -1 — a library file is not a program. Build definitions ({@code .sbt}, {@code .mill},
     * {@code build.sc}) never run.
     */
    public static int runLine(String name, String text) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".sbt") || lower.endsWith(".mill") || lower.equals("build.sc")) {
            return -1;
        }
        List<Main> mains = mains(text);
        if (!mains.isEmpty()) {
            return mains.get(0).line();
        }
        return lower.endsWith(".sc") ? 0 : -1;
    }

    /** The main class Run starts for {@code text}: its first entry point's, or null when it has none. */
    public static String mainClass(String text) {
        List<Main> mains = mains(text);
        return mains.isEmpty() ? null : mains.get(0).mainClass();
    }

    /**
     * What Scala CLI is given to run: the file alone, or — for a file of a Scala CLI project — the project
     * folder (all its sources and the {@code project.scala} directives) with the file's main class.
     */
    public static List<String> cliInputs(String file, Owner owner, String projectFolder, String mainClass) {
        if (owner != null && owner.build() == Build.SCALA_CLI && projectFolder != null) {
            List<String> inputs = new ArrayList<>();
            if (mainClass != null) {
                inputs.add("--main-class");
                inputs.add(mainClass);
            }
            inputs.add(projectFolder);
            return List.copyOf(inputs);
        }
        return List.of(file);
    }

    /**
     * The sbt command that runs {@code mainClass} with {@code args}: one batch command,
     * {@code runMain demo.hello a "b c"} (sbt splits it at spaces, honouring double quotes); plain {@code run}
     * when the main class is not known (sbt then picks it, or asks).
     */
    public static List<String> sbtRunTask(String mainClass, List<String> args) {
        StringBuilder command = new StringBuilder(mainClass == null ? "run" : "runMain " + mainClass);
        for (String a : args == null ? List.<String>of() : args) {
            command.append(' ').append(sbtQuote(a));
        }
        return List.of(command.toString());
    }

    private static String sbtQuote(String arg) {
        if (!arg.isEmpty() && arg.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\\')) {
            return arg;
        }
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The Mill task that runs {@code mainClass} of {@code module} with {@code args}:
     * {@code core.runMain demo.hello args…} — {@code runMain} alone for the root module, {@code core.run args…}
     * when the main class is not known.
     */
    public static List<String> millRunTask(String module, String mainClass, List<String> args) {
        String prefix = module == null || module.isEmpty() ? "" : module + ".";
        List<String> task = new ArrayList<>();
        if (mainClass == null) {
            task.add(prefix + "run");
        } else {
            task.add(prefix + "runMain");
            task.add(mainClass);
        }
        if (args != null) {
            task.addAll(args);
        }
        return List.copyOf(task);
    }

    /**
     * The Mill module a source file belongs to, by Mill's layout: the folders between the build root and the
     * module's {@code src} folder, joined by dots ({@code core/src/demo/Main.scala} → {@code core},
     * {@code core/test/src/…} → {@code core.test}); empty for a file of the root module ({@code src/…}).
     */
    public static String millModule(Path root, Path file) {
        if (root == null || file == null) {
            return "";
        }
        Path relative;
        try {
            relative = root.toAbsolutePath()
                    .normalize()
                    .relativize(file.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return "";
        }
        List<String> module = new ArrayList<>();
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            String segment = relative.getName(i).toString();
            if (segment.equals("src") || segment.equals("resources")) {
                return String.join(".", module);
            }
            module.add(segment);
        }
        return "";
    }
}
