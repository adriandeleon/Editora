package com.editora.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Builds real plugin jars for tests: Java sources compiled against Editora's own classes, packed into a jar,
 * so a plugin's classes come from its own class loader — not, as in every older test, from the parent loader
 * with no jar in the plugin folder (which is why nothing caught Windows jar locking, E3).
 */
public final class PluginJars {

    private PluginJars() {}

    /** Compiles {@code sources} (class name → source) and returns the jar holding their classes. */
    public static byte[] jar(Path work, Map<String, String> sources) {
        try {
            Path src = Files.createTempDirectory(work, "src");
            Path out = Files.createTempDirectory(work, "classes");
            List<String> args = new ArrayList<>(List.of(
                    "-d",
                    out.toString(),
                    "-proc:none",
                    "-classpath",
                    editoraClasses() + java.io.File.pathSeparator + work.resolve("lib-classes")));
            for (Map.Entry<String, String> e : sources.entrySet()) {
                Path file = src.resolve(e.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue());
                args.add(file.toString());
            }
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int rc = javac.run(null, null, err, args.toArray(new String[0]));
            if (rc != 0) {
                throw new IllegalStateException("javac failed: " + err.toString(StandardCharsets.UTF_8));
            }
            // Later jars may compile against these classes (a plugin against its lib/ helper).
            copyTree(out, work.resolve("lib-classes"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (JarOutputStream jar = new JarOutputStream(bytes);
                    Stream<Path> files = Files.walk(out)) {
                for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    jar.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                    jar.write(Files.readAllBytes(p));
                    jar.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A plugin {@code .zip}: {@code entries} are name → text or bytes. */
    public static byte[] zip(Map<String, Object> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (Map.Entry<String, Object> e : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(e.getKey()));
                    zip.write(
                            e.getValue() instanceof byte[] b
                                    ? b
                                    : String.valueOf(e.getValue()).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A plugin class that logs {@code message} through its context when it starts. */
    public static String loggingPlugin(String className, String message) {
        int dot = className.lastIndexOf('.');
        return "package " + className.substring(0, dot) + ";\n"
                + "public final class " + className.substring(dot + 1) + " implements com.editora.plugin.Plugin {\n"
                + "  public void start(com.editora.plugin.PluginContext ctx) { ctx.log(\"" + message + "\"); }\n"
                + "}\n";
    }

    private static String editoraClasses() {
        try {
            return Path.of(Plugin.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
