package probes;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import lib.Repo;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Run the Java typing study on disposable copies and an immutable compiled runtime.
 *
 * <p>Run {@code mvn test} first to compile the probe and produce Surefire's dependency classpath. The source
 * projects are read only. Output and disposable copies remain under {@code --output}.
 *
 * <pre>
 * java scripts/probes/JavaTypingStudy.java \
 *   --maven-project /path/to/Editora --gradle-project /path/to/RichTextFX \
 *   --jdtls /path/to/jdtls --java-home /path/to/jdk25 \
 *   --gradle-java-home /path/to/jdk21 --output /tmp/java-typing-study --seconds 1800 \
 *   [--projects maven,gradle|maven|gradle] [--typing-mode events|macro]
 * </pre>
 *
 * The study's output goes to {@code study.log} in the output directory, and this program exits with the
 * study's exit code.
 */
public final class JavaTypingStudy {

    private static final String USAGE =
            """
            usage: java scripts/probes/JavaTypingStudy.java --maven-project MAVEN_PROJECT
                   --gradle-project GRADLE_PROJECT --jdtls JDTLS --java-home JAVA_HOME
                   --gradle-java-home GRADLE_JAVA_HOME --output OUTPUT [--seconds SECONDS]
                   [--projects {maven,gradle,maven,gradle}] [--typing-mode {events,macro}]

            Run the Java typing study on disposable copies and an immutable compiled runtime.

            Run `mvn test` first to compile the probe and produce Surefire's dependency classpath.
            The source projects are read only. Output and disposable copies remain under --output.
            """;

    private static final List<String> REQUIRED =
            List.of("maven-project", "gradle-project", "jdtls", "java-home", "gradle-java-home", "output");
    private static final Map<String, List<String>> CHOICES =
            Map.of("projects", List.of("maven,gradle", "maven", "gradle"), "typing-mode", List.of("events", "macro"));

    /** Skipped wherever they are in a project copy. */
    private static final Set<String> IGNORED =
            Set.of(".git", ".gradle", ".idea", ".settings", "__pycache__", ".codex", ".editora");

    /** Skipped too, unless they are inside a {@code src} directory: build output and bulky tool folders. */
    private static final Set<String> IGNORED_OUTSIDE_SRC =
            Set.of("target", "build", "node_modules", "artifacts", "dist");

    private JavaTypingStudy() {}

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = parse(argv);
        int seconds;
        try {
            seconds = Integer.parseInt(args.get("seconds"));
        } catch (NumberFormatException e) {
            throw usageError("argument --seconds: invalid int value: '" + args.get("seconds") + "'");
        }
        Path repo = Repo.root().toRealPath();
        Path out = Path.of(args.get("output")).toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        try {
            Files.createDirectory(out); // a fresh directory every time: a study never mixes with an older one
        } catch (java.nio.file.FileAlreadyExistsException e) {
            exit("--output already exists: " + out);
        }
        out = out.toRealPath();

        for (String kind : List.of("maven", "gradle")) {
            Path source = Path.of(args.get(kind + "-project")).toRealPath();
            if (out.startsWith(source)) {
                exit("--output must be outside both source projects");
            }
            copyProject(source, out.resolve(kind));
            Files.writeString(out.resolve(kind).resolve(".editora-disposable-probe"),
                    "Disposable Java typing study copy.\n");
        }

        List<Path> reports;
        Path reportDir = repo.resolve("target/surefire-reports");
        if (Files.isDirectory(reportDir)) {
            try (Stream<Path> files = Files.list(reportDir)) {
                reports = files.filter(p -> {
                            String name = p.getFileName().toString();
                            return name.startsWith("TEST-") && name.endsWith(".xml");
                        })
                        .sorted()
                        .toList();
            }
        } else {
            reports = List.of();
        }
        if (reports.isEmpty()) {
            exit("Run mvn test first (missing Surefire dependency classpath).");
        }
        String classpath = surefireClasspath(reports.get(0));
        for (String name : List.of("classes", "test-classes")) {
            Path old = repo.resolve("target").resolve(name);
            Path copy = out.resolve("runtime").resolve(name);
            copyTree(old, copy);
            classpath = classpath.replace(old.toString(), copy.toString());
        }

        Path javaHome = Path.of(args.get("java-home")).toRealPath();
        List<String> command = new ArrayList<>(List.of(
                javaHome.resolve("bin/java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Xmx2g",
                "-Dglass.platform=Headless",
                "-Dprism.order=sw",
                "-Djava.awt.headless=true",
                "-Deditora.completion.trace=true",
                "-Dlsp.java.probe.command=" + Path.of(args.get("jdtls")).toRealPath(),
                "-Dlsp.java.soak.maven=" + out.resolve("maven"),
                "-Dlsp.java.soak.gradle=" + out.resolve("gradle"),
                "-Dlsp.java.soak.gradleJava=" + Path.of(args.get("gradle-java-home")).toRealPath(),
                "-Dlsp.java.soak.seconds=" + seconds,
                "-Dlsp.java.soak.projects=" + args.get("projects"),
                "-Dlsp.java.soak.typingMode=" + args.get("typing-mode"),
                "-cp",
                classpath,
                "com.editora.ui.JavaTypingSoakProbeTest"));
        var builder = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .redirectOutput(out.resolve("study.log").toFile())
                .redirectInput(ProcessBuilder.Redirect.INHERIT);
        builder.environment().put("JAVA_HOME", javaHome.toString());
        builder.environment().put("PATH", javaHome.resolve("bin") + java.io.File.pathSeparator
                + builder.environment().getOrDefault("PATH", ""));
        System.out.println("Study output: " + out);
        System.out.flush();
        int code = builder.start().waitFor();
        System.out.println("Study exit: " + code);
        System.out.flush();
        System.exit(code);
    }

    /** The flags as {@code name -> value}, with the defaults filled in; prints usage and exits 2 on a mistake. */
    private static Map<String, String> parse(String[] argv) {
        var args = new LinkedHashMap<String, String>();
        args.put("seconds", "1800");
        args.put("projects", "maven,gradle");
        args.put("typing-mode", "events");
        var known = new ArrayList<>(REQUIRED);
        known.addAll(List.of("seconds", "projects", "typing-mode"));
        var given = new ArrayList<String>();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.equals("-h") || arg.equals("--help")) {
                System.out.print(USAGE);
                System.exit(0);
            }
            if (!arg.startsWith("--")) {
                throw usageError("unrecognized arguments: " + arg);
            }
            String name = arg.substring(2);
            String value = null;
            int equals = name.indexOf('=');
            if (equals >= 0) {
                value = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            if (!known.contains(name)) {
                throw usageError("unrecognized arguments: " + arg);
            }
            if (value == null) {
                if (i + 1 >= argv.length || argv[i + 1].startsWith("--")) {
                    throw usageError("argument --" + name + ": expected one argument");
                }
                value = argv[++i];
            }
            List<String> choices = CHOICES.get(name);
            if (choices != null && !choices.contains(value)) {
                throw usageError("argument --" + name + ": invalid choice: '" + value + "' (choose from "
                        + String.join(", ", choices.stream().map(c -> "'" + c + "'").toList()) + ")");
            }
            args.put(name, value);
            given.add(name);
        }
        List<String> missing = REQUIRED.stream()
                .filter(name -> !given.contains(name))
                .map(name -> "--" + name)
                .toList();
        if (!missing.isEmpty()) {
            throw usageError("the following arguments are required: " + String.join(", ", missing));
        }
        return args;
    }

    private static RuntimeException usageError(String message) {
        System.err.print(USAGE.substring(0, USAGE.indexOf("\n\n") + 1));
        System.err.println("JavaTypingStudy: error: " + message);
        System.exit(2);
        return new IllegalStateException(message);
    }

    private static void exit(String message) {
        System.err.println(message);
        System.exit(1);
    }

    /** The {@code java.class.path} property that Surefire recorded in a {@code TEST-*.xml} report. */
    private static String surefireClasspath(Path report) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        List<Element> properties = children(suite, "properties");
        if (!properties.isEmpty()) {
            for (Element property : children(properties.get(0), "property")) {
                if (property.getAttribute("name").equals("java.class.path")) {
                    return property.getAttribute("value");
                }
            }
        }
        exit("No java.class.path property in " + report);
        throw new IllegalStateException();
    }

    private static List<Element> children(Element parent, String tag) {
        var found = new ArrayList<Element>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element child && child.getTagName().equals(tag)) {
                found.add(child);
            }
        }
        return found;
    }

    /**
     * Copies a source project, leaving out symbolic links, the tool folders in {@link #IGNORED}, and the build
     * folders in {@link #IGNORED_OUTSIDE_SRC} unless they are inside a {@code src} directory (where they are
     * packages, not build output).
     */
    private static void copyProject(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && ignored(source, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectory(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isSymbolicLink() && !ignored(source, file)) {
                    Files.copy(file, target.resolve(source.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.setLastModifiedTime(target.resolve(source.relativize(dir).toString()),
                        Files.getLastModifiedTime(dir));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Whether {@code path} (below {@code source}) is left out of a project copy. */
    private static boolean ignored(Path source, Path path) {
        String name = path.getFileName().toString();
        if (IGNORED.contains(name)) {
            return true;
        }
        // Decided by the directory that holds the entry, as shutil.copytree's ignore callback did.
        Path parent = source.relativize(path.getParent());
        for (Path part : parent) {
            if (part.toString().equals("src")) {
                return false;
            }
        }
        return IGNORED_OUTSIDE_SRC.contains(name);
    }

    /** Copies a directory tree as it is, following symbolic links (the compiled classes are a plain tree). */
    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.copy(file, target.resolve(source.relativize(file).toString()),
                                StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
