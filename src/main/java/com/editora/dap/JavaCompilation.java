package com.editora.dap;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.process.ProcessRunner;
import com.editora.run.JdkToolchain;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Editora's own compile of a Java file it debugs without a build project — a loose file, a compact source, an
 * extensionless script — and of a remote file on its host: the {@code javac -g} command, the folder the classes
 * go to and how it is removed, and the main class a file declares. {@link DapManager} decides when to compile
 * and owns the folder for its session; this is the how (moved out of it; same logger).
 */
final class JavaCompilation {

    private static final Logger LOG = Logger.getLogger(DapManager.class.getName());

    private JavaCompilation() {}

    /** Compiles with the JDK that will run the debuggee, retaining local variables for the debugger. */
    static ProcessRunner.Result compileStandalone(
            Path file, Path outputDir, String javaExecOverride, Map<String, String> environment) {
        return compileStandalone(file, file, outputDir, javaExecOverride, environment, null, null);
    }

    static ProcessRunner.Result compileStandalone(
            Path original,
            Path source,
            Path outputDir,
            @Nullable String javaExecOverride,
            Map<String, String> environment,
            @Nullable Integer sourceVersion,
            @Nullable Path sourceRoot) {
        List<String> command = new ArrayList<>(List.of(JdkToolchain.compilerForJavaExecutable(javaExecOverride), "-g"));
        if (sourceVersion != null) {
            command.add("--release");
            command.add(sourceVersion.toString());
        }
        if (sourceRoot != null) {
            // Without it javac looks for the package's other classes in the file's own folder, where a
            // packaged class's siblings are not (src/demo/Helper.java would be src/demo/demo/Helper.java).
            command.addAll(List.of("-sourcepath", sourceRoot.toString()));
        }
        command.addAll(List.of("-d", outputDir.toString(), source.toString()));
        return ProcessRunner.runInUserLocale(
                original.getParent(), java.time.Duration.ofSeconds(60), command, environment);
    }

    /**
     * A remote file's compilation on its target: the folder for its classes, the copy of its source when one
     * is compiled instead of the file ({@code null} otherwise), both named as Editora names the target's files
     * ({@link com.editora.process.ExecTarget#toPath}), and how the folder is removed.
     */
    record HostCompilation(Path directory, @Nullable Path source, Runnable removal) {}

    /**
     * A new folder for a remote file's compiled classes: {@code debug-classes/<id>} in its target's cache, with
     * {@code source} (when not {@code null}) written into it. Removed when the session ends, like a local one's
     * temporary folder — on the target, which for a container is the only place it is.
     */
    static HostCompilation hostCompilation(Path file, @Nullable String source) throws java.io.IOException {
        com.editora.process.ExecTarget target = com.editora.process.ExecTargets.forPath(file);
        com.editora.process.TargetCache cache = target.cache();
        if (cache == null) {
            throw new java.io.IOException(tr("debug.error.hostNoCache"));
        }
        String folder = "debug-classes/" + java.util.UUID.randomUUID();
        Path directory = target.toPath(cache.makeDirectories(folder));
        Runnable removal = () -> {
            try {
                cache.delete(folder);
            } catch (java.io.IOException | RuntimeException e) {
                LOG.log(Level.FINE, "Could not remove temporary debug classes", e);
            }
        };
        Path copy = source == null
                ? null
                : target.toPath(cache.putText(folder + "/" + file.getFileName() + ".java", source));
        return new HostCompilation(directory, copy, removal);
    }

    /** {@code java} on a remote file's host: under its configured JDK ({@code jdk.home}), else its PATH's. */
    static String hostJava(Path file) {
        String home = com.editora.process.ExecTargets.remoteTool(file, "jdk.home");
        return home == null || home.isBlank() ? "java" : home.strip().replaceAll("/+$", "") + "/bin/java";
    }

    /** {@code javac} beside a host's {@code java} ({@link #hostJava}). Pure. */
    static String hostJavac(@Nullable String java) {
        return java != null && java.endsWith("/bin/java")
                ? java.substring(0, java.length() - "java".length()) + "javac"
                : "javac";
    }

    /** {@link #compileStandalone} on a remote file's host: its javac, its paths. */
    static ProcessRunner.Result compileOnHost(
            Path original,
            Path source,
            Path outputDir,
            @Nullable String hostJava,
            Map<String, String> environment,
            @Nullable Integer sourceVersion,
            @Nullable Path sourceRoot) {
        DapPaths paths = DapManager.pathsFor(original);
        List<String> command = new ArrayList<>(List.of(hostJavac(hostJava), "-g"));
        if (sourceVersion != null) {
            command.add("--release");
            command.add(sourceVersion.toString());
        }
        if (sourceRoot != null) {
            command.addAll(List.of("-sourcepath", paths.toAdapter(sourceRoot)));
        }
        command.addAll(List.of("-d", paths.toAdapter(outputDir), paths.toAdapter(source)));
        return ProcessRunner.runInUserLocale(
                original.getParent(), java.time.Duration.ofSeconds(120), command, environment);
    }

    /** Blank the first line without changing later line numbers for breakpoints and stack frames. */
    static String withoutShebang(String source) {
        if (source == null || !source.startsWith("#!")) {
            return source;
        }
        int newline = source.indexOf('\n');
        return newline < 0 ? "" : " ".repeat(newline) + source.substring(newline);
    }

    /** {@code removal} when the folder is a target's, else this machine's folder walked and deleted. */
    static void removeCompilation(Path directory, @Nullable Runnable removal) {
        if (removal != null) {
            removal.run();
        } else {
            removeCompilationDirectory(directory);
        }
    }

    static void removeCompilationDirectory(Path directory) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, java.io.IOException error)
                        throws java.io.IOException {
                    if (error != null) throw error;
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            LOG.log(Level.FINE, "Could not remove temporary debug classes", e);
        }
    }

    /** A compact source's implicit class is named for its file, even when it declares nested types. */
    static String compactMainClassFromFile(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    /** Matches a top-level type declaration, capturing its name (e.g. {@code public final class Foo}). */
    private static final java.util.regex.Pattern TYPE_DECL =
            java.util.regex.Pattern.compile("\\b(?:public\\s+)?(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)*"
                    + "(?:class|record|enum|interface)\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*)"); // not \\w: ASCII-only, cut
    // Café to Caf

    /**
     * Derives the fully-qualified main-class name from a {@code .java} file: its {@code package}
     * declaration plus the <em>declared</em> type name (the first top-level {@code class}/{@code record}/
     * {@code enum}), falling back to the file's base name. Reads the source (not the filename) so a
     * class whose name differs from the file resolves correctly. Pure-ish (one bounded file read).
     */
    static @Nullable String mainClassFromFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        String base = file.getFileName().toString();
        if (base.endsWith(".java")) {
            base = base.substring(0, base.length() - ".java".length());
        }
        if (base.isEmpty()) {
            return null;
        }
        String pkg = "";
        String cls = null;
        java.util.List<String> lines;
        try (java.util.stream.Stream<String> s = java.nio.file.Files.lines(file)) {
            lines = s.limit(500).toList();
        } catch (java.io.IOException | RuntimeException ignored) {
            lines = java.util.List.of();
        }
        for (String raw : lines) {
            String t = raw.strip();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue; // skip comment lines (crude, but enough to avoid most false matches)
            }
            if (pkg.isEmpty() && t.startsWith("package ") && t.endsWith(";")) {
                pkg = t.substring("package ".length(), t.length() - 1).strip();
            }
            if (cls == null) {
                java.util.regex.Matcher m = TYPE_DECL.matcher(t);
                if (m.find()) {
                    cls = m.group(1);
                }
            }
            if (!pkg.isEmpty() && cls != null) {
                break;
            }
        }
        if (cls == null) {
            cls = base; // no type declaration found — best-effort (e.g. a JEP 512 compact source)
        }
        return pkg.isEmpty() ? cls : pkg + "." + cls;
    }
}
