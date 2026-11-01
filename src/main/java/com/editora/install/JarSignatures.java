package com.editora.install;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Takes the signature files out of the jars in the pom.xml server's bundle (XML/pom review 4, A31).
 *
 * <p>Eclipse signs {@code org.eclipse.lemminx-*.jar} (and lsp4j) in the lemminx-maven zip, and that lemminx jar
 * adds its own classes to Jing's {@code com.thaiopensource.relaxng.pattern} package, whose other classes come from
 * the unsigned {@code jing-*.jar}. The JVM refuses to load one package from two signers, so lemminx's RELAX NG
 * plugin failed to start — "signer information does not match" in the log, no completion from a {@code .rng} — in
 * the pom server and in XML's Java fallback, which run on these jars. vscode-xml does not see this because its
 * lemminx is one unsigned jar. With the {@code META-INF/*.SF}, {@code *.RSA}, {@code *.DSA} and {@code *.EC} files
 * gone a jar is unsigned and loads like any other; nothing else in it changes.
 *
 * <p>An install does this after its downloads are checked, before the server's smoke test; a bundle installed
 * before is rewritten once, when a server is next started from it, and {@value #MARKER} records that it was.
 */
public final class JarSignatures {

    private static final Logger LOG = Logger.getLogger(JarSignatures.class.getName());

    private JarSignatures() {}

    /**
     * The file that says a folder's jars carry no signatures any more: a dot file, not a jar, so a {@code <dir>/*}
     * classpath does not pick it up.
     */
    public static final String MARKER = ".editora-unsigned";

    /** Whether {@code name} is a jar signature file: {@code META-INF/<name>.SF|.RSA|.DSA|.EC}. Pure. */
    static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }

    /** Whether {@code jar} holds a signature file. */
    static boolean signed(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (isSignatureFile(entries.nextElement().getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Rewrites {@code jar} without its signature files, every other entry as it was and in its order; does nothing to
     * a jar that has none. The copy is written beside it under a name no classpath picks up, then moved over it, so
     * a server starting meanwhile sees the old jar or the new one, never half of it. Returns whether it rewrote.
     */
    static boolean unsign(Path jar) throws IOException {
        if (!signed(jar)) {
            return false;
        }
        Path dir = jar.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, jar.getFileName() + ".", ".unsigning");
        try {
            try (ZipFile zip = new ZipFile(jar.toFile());
                    OutputStream file = Files.newOutputStream(tmp);
                    ZipOutputStream out = new ZipOutputStream(file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (isSignatureFile(entry.getName())) {
                        continue;
                    }
                    ZipEntry copy = new ZipEntry(entry);
                    if (copy.getMethod() != ZipEntry.STORED) {
                        copy.setCompressedSize(-1); // deflated again here: the old size would not match
                    }
                    out.putNextEntry(copy);
                    try (InputStream in = zip.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                }
            }
            try {
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Unsigns every jar directly in {@code dir} that is signed ({@link #unsign}) and writes {@link #MARKER}; does
     * nothing once the marker is there. A jar that cannot be read is left as it is (the server reports it); one that
     * cannot be replaced — open in a running server on Windows — leaves the marker unwritten, so the next start tries
     * again. Returns whether the folder is done.
     */
    public static synchronized boolean ensureUnsigned(Path dir) {
        if (Files.isRegularFile(dir.resolve(MARKER))) {
            return true;
        }
        if (!Files.isDirectory(dir)) {
            return false;
        }
        List<Path> jars;
        try (Stream<Path> list = Files.list(dir)) {
            jars = list.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        List<String> rewritten = new ArrayList<>();
        for (Path jar : jars) {
            try {
                if (unsign(jar)) {
                    rewritten.add(jar.getFileName().toString());
                }
            } catch (java.util.zip.ZipException e) {
                LOG.log(Level.FINE, "Not a readable jar, left as it is: " + jar, e);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not remove the signature from " + jar + " (tried again next start)", e);
                return false;
            }
        }
        try {
            Files.writeString(
                    dir.resolve(MARKER),
                    "# Written by Editora: these jars' signature files were removed (RELAX NG needs it).\n"
                            + "unsigned=" + String.join(",", rewritten) + "\n");
        } catch (IOException e) {
            return false; // read-only: done for this run, tried again next start
        }
        if (!rewritten.isEmpty()) {
            LOG.info("Removed the jar signatures that kept lemminx's RELAX NG plugin from loading: " + rewritten);
        }
        return true;
    }

    /**
     * {@link #ensureUnsigned} for each folder of Editora's own pom.xml server bundle ({@code …/plugins/lsp/maven})
     * that {@code argv}'s {@code -cp} names, as a whole ({@code <dir>/*}) or jar by jar (XML's Java fallback). Any
     * other folder is the user's and is left alone. Called before a local lemminx starts.
     */
    public static void repairBundleOf(List<String> argv) {
        for (Path dir : bundleFolders(argv)) {
            ensureUnsigned(dir);
        }
    }

    /** The {@code plugins/lsp/maven} folders {@code argv}'s classpath loads jars from. Pure. */
    static Set<Path> bundleFolders(List<String> argv) {
        Set<Path> folders = new LinkedHashSet<>();
        if (argv == null) {
            return folders;
        }
        Path bundle = Path.of(InstallCatalog.LEMMINX_MAVEN_DIR);
        for (int i = 1; i + 1 < argv.size(); i++) {
            String a = argv.get(i);
            if (!a.equals("-cp") && !a.equals("-classpath") && !a.equals("--class-path")) {
                continue;
            }
            for (String entry : argv.get(i + 1).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                String e = entry.strip();
                try {
                    Path folder = e.endsWith("*")
                            ? Path.of(e.substring(0, e.length() - 1))
                            : e.endsWith(".jar") ? Path.of(e).getParent() : null;
                    if (folder != null
                            && folder.isAbsolute()
                            && folder.normalize().endsWith(bundle)) {
                        folders.add(folder.normalize());
                    }
                } catch (InvalidPathException ignored) {
                    // not a path: nothing of Editora's
                }
            }
        }
        return folders;
    }
}
