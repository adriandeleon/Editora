package com.editora.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * The grammars Editora ships for the XML servers, and the OASIS catalog that maps their URLs to the shipped copies
 * (XML/pom review 2, D12). Every pom names {@code http(s)://maven.apache.org/xsd/maven-4.0.0.xsd}; with schema
 * downloads off (an untrusted folder, a loose pom) lemminx could not fetch it, so each pom showed two errors on line 2
 * and had no element completion. The catalog is handed to both lemminx servers before the user's own catalogs.
 *
 * <p>The files are written under {@code <config>/cache/schemas/}: beside lemminx's own cache (which Doctor's Clear
 * empties) rather than in it, and under {@code cache/}, which settings export skips. Blocking I/O, but small and only
 * when a file is missing or differs: called on a settings apply.
 */
public final class XmlBundledSchemas {

    private static final Logger LOG = Logger.getLogger(XmlBundledSchemas.class.getName());

    private XmlBundledSchemas() {}

    /** Where the bundled grammars live in the module. */
    private static final String RESOURCE_DIR = "/com/editora/lsp/schemas/";

    /** The catalog's file name in {@link #dir}. */
    public static final String CATALOG = "catalog.xml";

    /**
     * Each shipped schema → the namespace it defines and the locations a document names it by: the current
     * {@code xsd/} URLs over http and https, and for 4.0.0 the old {@code maven-v4_0_0.xsd} many poms still carry.
     */
    static final Map<String, List<String>> SCHEMAS = schemas();

    private static Map<String, List<String>> schemas() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(
                "maven-4.0.0.xsd",
                List.of(
                        "http://maven.apache.org/POM/4.0.0",
                        "http://maven.apache.org/xsd/maven-4.0.0.xsd",
                        "https://maven.apache.org/xsd/maven-4.0.0.xsd",
                        "http://maven.apache.org/maven-v4_0_0.xsd",
                        "https://maven.apache.org/maven-v4_0_0.xsd"));
        m.put(
                "maven-4.1.0.xsd",
                List.of(
                        "http://maven.apache.org/POM/4.1.0",
                        "http://maven.apache.org/xsd/maven-4.1.0.xsd",
                        "https://maven.apache.org/xsd/maven-4.1.0.xsd"));
        return java.util.Collections.unmodifiableMap(m);
    }

    /** The folder the schemas and their catalog are written to, under {@code configDir}. */
    public static Path dir(Path configDir) {
        return configDir.resolve("cache").resolve("schemas");
    }

    /**
     * The catalog beside lemminx's work dir ({@link XmlServerSettings#cacheDir}, {@code <config>/cache/lemminx}),
     * when it has been written there; else null.
     */
    static @Nullable Path catalogBeside(@Nullable Path workDir) {
        Path cache = workDir == null ? null : workDir.getParent();
        if (cache == null) {
            return null;
        }
        Path catalog = cache.resolve("schemas").resolve(CATALOG);
        return Files.isRegularFile(catalog) ? catalog : null;
    }

    /**
     * Pure: the catalog's text. The namespace is a {@code uri} entry, which Xerces looks up first, so a pom naming
     * any other location for that namespace gets the shipped schema too; each location is a {@code system} and a
     * {@code uri} entry (lemminx resolves a schema location through either).
     */
    static String catalogXml() {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<!-- Written by Editora: its bundled grammars. Rewritten when it differs; do not edit. -->\n");
        out.append("<catalog xmlns=\"urn:oasis:names:tc:entity:xmlns:xml:catalog\">\n");
        SCHEMAS.forEach((file, names) -> {
            for (String name : names) {
                if (name.endsWith(".xsd")) {
                    out.append("  <system systemId=\"")
                            .append(name)
                            .append("\" uri=\"")
                            .append(file)
                            .append("\"/>\n");
                }
                out.append("  <uri name=\"")
                        .append(name)
                        .append("\" uri=\"")
                        .append(file)
                        .append("\"/>\n");
            }
        });
        out.append("</catalog>\n");
        return out.toString();
    }

    /**
     * Writes the shipped schemas and their catalog under {@link #dir}({@code configDir}) where one is missing or
     * differs; returns the catalog, or null when it could not be written (the servers then run without it).
     */
    public static @Nullable Path ensure(@Nullable Path configDir) {
        if (configDir == null) {
            return null;
        }
        Path dir = dir(configDir);
        try {
            Files.createDirectories(dir);
            for (String file : SCHEMAS.keySet()) {
                writeIfDifferent(dir.resolve(file), resource(file));
            }
            Path catalog = dir.resolve(CATALOG);
            writeIfDifferent(catalog, catalogXml().getBytes(StandardCharsets.UTF_8));
            return catalog;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not write the bundled XML schemas to " + dir, e);
            return null;
        }
    }

    /** The bytes of a shipped schema, read once. */
    private static byte[] resource(String file) throws IOException {
        byte[] known = CACHE.get(file);
        if (known != null) {
            return known;
        }
        try (InputStream in = XmlBundledSchemas.class.getResourceAsStream(RESOURCE_DIR + file)) {
            if (in == null) {
                throw new IOException("missing resource " + RESOURCE_DIR + file);
            }
            byte[] data = in.readAllBytes();
            CACHE.put(file, data);
            return data;
        }
    }

    private static final Map<String, byte[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Files compared byte for byte with what is shipped in this run: later applies look at the size only. */
    private static final java.util.Set<Path> VERIFIED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void writeIfDifferent(Path target, byte[] data) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) == data.length) {
            if (VERIFIED.contains(target) || Arrays.equals(Files.readAllBytes(target), data)) {
                VERIFIED.add(target);
                return;
            }
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        VERIFIED.add(target);
    }
}
