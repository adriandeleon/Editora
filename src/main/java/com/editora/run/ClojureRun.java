package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * How Run and Debug start a Clojure file: which of the three tools runs it, in which folder, and its command line.
 *
 * <ul>
 *   <li><b>Babashka</b> ({@code bb <file>}) for a {@code #!/usr/bin/env bb} script, a {@code .bb} file, and a file
 *       in a project whose only marker is {@code bb.edn}. A native program: it has no JVM to debug.</li>
 *   <li><b>Leiningen</b> ({@code lein run -m <ns>}) in a project with {@code project.clj} and no
 *       {@code deps.edn}; a file without {@code -main} runs as a script through {@code -m clojure.main}.</li>
 *   <li><b>The Clojure CLI</b> ({@code clojure -M -m <ns>} or {@code clojure -M <file>}) everywhere else — a
 *       {@code deps.edn} project, or a file in no project at all.</li>
 * </ul>
 *
 * <p>A namespace with a {@code -main} runs through {@code -m} when its name matches its path ({@code my-app.core}
 * in {@code …/my_app/core.clj}), so the tool finds it on the classpath; any other file runs as a script, its
 * top-level forms in order. A standalone file's folder is put on the classpath ({@code -Sdeps {:paths ["."]}}).
 *
 * <p>The CLI writes an uncaught exception's stack trace to a temporary file and prints only a summary (since
 * Clojure 1.10.1) unless {@code clojure.main.report=stderr}; Run sets it, so the trace is in the console where
 * its links work.
 *
 * <p>Pure: the disk is reached only through the predicate a caller passes.
 */
public final class ClojureRun {

    private ClojureRun() {}

    /** The tool that runs a Clojure file. */
    public enum Tool {
        CLI("clojure"),
        LEIN("lein"),
        BB("bb");

        private final String command;

        Tool(String command) {
            this.command = command;
        }

        /** The executable's name ({@code clojure}, {@code lein}, {@code bb}). */
        public String command() {
            return command;
        }

        /** The tool whose executable is {@code command}, or null. */
        public static @Nullable Tool of(@Nullable String command) {
            for (Tool t : values()) {
                if (t.command.equals(command)) {
                    return t;
                }
            }
            return null;
        }
    }

    /**
     * How one file runs.
     *
     * @param tool the tool
     * @param directory where it runs: the project folder, else the classpath root of a {@code -main} namespace,
     *     else the file's folder
     * @param mainNamespace the namespace run through {@code -m}, or null to run the file as a script
     * @param inProject whether {@code directory} holds the tool's project file (else a standalone file)
     * @param extraPath for the Clojure CLI in a project, the namespace's classpath root relative to the project
     *     when it is not the default {@code src} ({@code scripts}); null otherwise
     */
    public record Plan(
            Tool tool,
            @Nullable Path directory,
            @Nullable String mainNamespace,
            boolean inProject,
            @Nullable String extraPath) {}

    /** The alias Run adds to a {@code deps.edn} project to put a namespace's folder on the classpath. */
    public static final String EXTRA_PATH_ALIAS = ":editora/run";

    /** The project files, nearest first, that root a Clojure project. */
    public static final List<String> PROJECT_FILES = List.of("deps.edn", "project.clj", "bb.edn");

    /** JVM option: an uncaught exception's whole trace on stderr, not in a temporary file. */
    public static final String REPORT_TO_STDERR = "-Dclojure.main.report=stderr";

    /**
     * JVM option for a debug session: the compiler keeps a function's locals alive to its end, so the Debug panel
     * can show them (by default a local is cleared after its last use, and reads as null at a later line).
     */
    public static final String KEEP_LOCALS = "-Dclojure.compiler.disable-locals-clearing=true";

    /** {@code (ns name …)}, skipping metadata such as {@code ^:no-doc} or {@code ^{:doc "…"}}. */
    private static final Pattern NS = Pattern.compile(
            "(?m)^\\s*\\(\\s*ns\\s+(?:\\^(?:\\{[^}]*}|\\S+)\\s+)*([A-Za-z*+!_?<>=$%&'][\\w.*+!?<>=$%&'-]*)");

    /** {@code (defn -main …)} at the start of a line, metadata allowed. */
    private static final Pattern MAIN =
            Pattern.compile("^\\s*\\(defn\\s+(?:\\^(?:\\{[^}]*}|\\S+)\\s+)*-main(?=[\\s\\[(]|$)");

    /** The namespace {@code text} declares, or null. Pure. */
    public static @Nullable String namespace(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = NS.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** The 0-based line of the file's {@code (defn -main}, or -1. Pure. */
    public static int mainLine(String text) {
        if (text == null) {
            return -1;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (MAIN.matcher(lines[i]).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a file named {@code name} is a program Run starts: Clojure ({@code .clj}, {@code .cljc}) and
     * Babashka ({@code .bb}) source, or a file without an extension (a {@code #!} script). Not data
     * ({@code .edn}), a Leiningen {@code project.clj}, or ClojureScript ({@code .cljs}), which no JVM runs. Pure.
     */
    public static boolean runnable(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("project.clj") || lower.equals("build.boot")) {
            return false;
        }
        int dot = lower.lastIndexOf('.');
        if (dot <= 0) {
            return true;
        }
        String ext = lower.substring(dot + 1);
        return ext.equals("clj") || ext.equals("cljc") || ext.equals("bb");
    }

    /** Whether {@code firstLine} is a shebang whose interpreter is Babashka ({@code #!/usr/bin/env bb}). Pure. */
    public static boolean babashkaShebang(String firstLine) {
        if (firstLine == null || !firstLine.startsWith("#!")) {
            return false;
        }
        String[] tokens = firstLine.substring(2).strip().split("\\s+");
        for (String token : tokens) {
            String base = token.substring(token.lastIndexOf('/') + 1);
            if (base.equals("env") || base.startsWith("-") || base.contains("=") || base.isEmpty()) {
                continue; // env, its flags (-S) and its VAR=value assignments
            }
            return base.equals("bb") || base.equals("bb.exe");
        }
        return false;
    }

    /**
     * How {@code file} (whose text is {@code text}) runs; {@code isFile} answers whether a project file exists.
     * The project is the nearest folder, from the file's own up, holding {@code deps.edn}, {@code project.clj}
     * or {@code bb.edn}. Pure.
     */
    public static Plan plan(@Nullable Path file, String text, Predicate<Path> isFile) {
        Path folder = file == null ? null : file.toAbsolutePath().getParent();
        Path project = null;
        for (Path dir = folder; dir != null; dir = dir.getParent()) {
            final Path d = dir;
            if (PROJECT_FILES.stream().anyMatch(name -> isFile.test(d.resolve(name)))) {
                project = dir;
                break;
            }
        }
        String name = file == null || file.getFileName() == null
                ? ""
                : file.getFileName().toString();
        boolean babashka = babashkaShebang(firstLine(text))
                || name.toLowerCase(Locale.ROOT).endsWith(".bb");
        Tool tool;
        if (babashka) {
            tool = Tool.BB;
        } else if (project != null && isFile.test(project.resolve("deps.edn"))) {
            tool = Tool.CLI;
        } else if (project != null && isFile.test(project.resolve("project.clj"))) {
            tool = Tool.LEIN;
        } else if (project != null) {
            tool = Tool.BB; // bb.edn alone: a Babashka project
        } else {
            tool = Tool.CLI;
        }
        boolean inProject = project != null && isFile.test(project.resolve(projectFile(tool)));
        String main = null;
        Path root = null;
        if (mainLine(text) >= 0 && file != null) {
            String ns = namespace(text);
            root = ns == null ? null : classpathRoot(file.toAbsolutePath(), ns);
            main = root == null ? null : ns;
        }
        Path directory = inProject ? project : main != null ? root : folder;
        String extraPath = null;
        if (tool == Tool.CLI
                && inProject
                && project != null
                && main != null
                && root != null
                && !root.equals(project.resolve("src"))) {
            String relative = project.relativize(root).toString().replace('\\', '/');
            extraPath = relative.isEmpty() ? "." : relative;
        }
        return new Plan(tool, directory, main, inProject, extraPath);
    }

    /** The project file that roots {@code tool}'s projects. */
    private static String projectFile(Tool tool) {
        return switch (tool) {
            case CLI -> "deps.edn";
            case LEIN -> "project.clj";
            case BB -> "bb.edn";
        };
    }

    /**
     * The folder {@code namespace} is loaded from when it is {@code file}: {@code /p/src} for {@code my-app.core}
     * in {@code /p/src/my_app/core.clj}; null when the file's path does not match the name.
     */
    static @Nullable Path classpathRoot(Path file, String namespace) {
        String[] parts = namespace.replace('-', '_').split("\\.");
        int n = file.getNameCount();
        if (parts.length > n) {
            return null;
        }
        String last = file.getFileName().toString();
        int dot = last.lastIndexOf('.');
        if (dot <= 0 || !last.substring(0, dot).equals(parts[parts.length - 1])) {
            return null;
        }
        for (int i = 1; i < parts.length; i++) {
            if (!file.getName(n - 1 - i).toString().equals(parts[parts.length - 1 - i])) {
                return null;
            }
        }
        Path root = file;
        for (int i = 0; i < parts.length && root != null; i++) {
            root = root.getParent();
        }
        return root;
    }

    /**
     * The command line for {@code plan}: {@code executable} the tool, {@code fileArg} the file as the machine it
     * runs on names it, {@code jvmOptions} for the program's JVM (the Clojure CLI takes them as {@code -J}
     * arguments; Leiningen's go in {@code JVM_OPTS}, see {@link #jvmOptionsVariable}; Babashka has no JVM).
     * Pure.
     */
    public static List<String> argv(
            Plan plan, String executable, String fileArg, List<String> jvmOptions, List<String> args) {
        List<String> argv = new ArrayList<>();
        argv.add(executable);
        switch (plan.tool()) {
            case CLI -> {
                for (String option : jvmOptions == null ? List.<String>of() : jvmOptions) {
                    argv.add("-J" + option);
                }
                if (!plan.inProject() && plan.mainNamespace() != null) {
                    argv.add("-Sdeps");
                    argv.add("{:paths [\".\"]}"); // the run folder is the namespace's classpath root
                    argv.add("-M");
                } else if (plan.extraPath() != null) {
                    argv.add("-Sdeps");
                    argv.add("{:aliases {" + EXTRA_PATH_ALIAS + " {:extra-paths [" + ednString(plan.extraPath())
                            + "]}}}");
                    argv.add("-M" + EXTRA_PATH_ALIAS);
                } else {
                    argv.add("-M");
                }
                if (plan.mainNamespace() != null) {
                    argv.add("-m");
                    argv.add(plan.mainNamespace());
                } else {
                    argv.add(fileArg);
                }
            }
            case LEIN -> {
                argv.add("run");
                argv.add("-m");
                if (plan.mainNamespace() != null) {
                    argv.add(plan.mainNamespace());
                } else {
                    argv.add("clojure.main");
                    argv.add(fileArg);
                }
            }
            case BB -> argv.add(fileArg);
        }
        if (args != null) {
            argv.addAll(args);
        }
        return List.copyOf(argv);
    }

    /**
     * The environment variable that carries JVM options to the program's JVM: {@code JVM_OPTS} for Leiningen
     * (its own JVM reads {@code LEIN_JVM_OPTS}); null for the Clojure CLI (options are arguments) and Babashka.
     */
    public static @Nullable String jvmOptionsVariable(Tool tool) {
        return tool == Tool.LEIN ? "JVM_OPTS" : null;
    }

    /** The prefix that makes a JVM option a Clojure CLI argument ({@code -J}); null for the other tools. */
    public static @Nullable String jvmOptionPrefix(Tool tool) {
        return tool == Tool.CLI ? "-J" : null;
    }

    /** Whether the program runs on a JVM a debugger can attach to: not Babashka, a native program. */
    public static boolean debuggable(Tool tool) {
        return tool != Tool.BB;
    }

    /** {@code value} as an EDN string literal. */
    private static String ednString(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int nl = text.indexOf('\n');
        return nl >= 0 ? text.substring(0, nl) : text;
    }
}
