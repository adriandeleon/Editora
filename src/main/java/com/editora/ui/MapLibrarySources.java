package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * The library classes the Project Map shows as cards (Map code browser gap 5). A definition, a caller or a supertype
 * in the JDK or a dependency is a {@code jdt://contents/<archive>/<package>/<Class>.class?…} class file, which no
 * file system opens: jdtls gives its source ({@code java/classFileContents}, attached or decompiled), as the editor's
 * library tabs show it. The Map writes that source to a read-only {@code <Class>.java} in a folder of its own for this
 * run, so a library class is an ordinary card — highlighted, outlined, its declarations as cards, found in — and
 * remembers which class each file is, so the card says it is library source and its Open opens the editor's library
 * tab rather than the copy. The folder is outside every project, so no card of it is closed for a folder of the map,
 * and it goes when Editora exits.
 */
final class MapLibrarySources {

    private MapLibrarySources() {}

    /** A library class shown on the Map: its {@code jdt://} URI, the project file whose server gave it, and where. */
    record Library(String uri, Path anchor, String where) {}

    private static final String PREFIX = "jdt://contents/";

    private static final Map<Path, Library> FILES = new ConcurrentHashMap<>();
    private static volatile @Nullable Path folder;

    /** The library class {@code file} is the source of, or null for any other file. */
    static @Nullable Library of(@Nullable Path file) {
        return file == null ? null : FILES.get(file.toAbsolutePath().normalize());
    }

    /** Whether {@code uri} is a class file the Map can show: {@code jdt://contents/<archive>/<package>/<Class>.class}. */
    static boolean showable(@Nullable String uri) {
        return parts(uri) != null;
    }

    /**
     * The archive (a jar's file name, or a JDK module), the package and the class's file name of {@code uri}, decoded;
     * null for another URI. Pure.
     */
    static String @Nullable [] parts(@Nullable String uri) {
        if (uri == null || !uri.startsWith(PREFIX)) {
            return null;
        }
        int query = uri.indexOf('?');
        String path = uri.substring(PREFIX.length(), query < 0 ? uri.length() : query);
        String[] segments = path.split("/");
        if (segments.length != 3 || !segments[2].endsWith(".class") || segments[2].length() <= ".class".length()) {
            return null;
        }
        String[] decoded = new String[3];
        for (int i = 0; i < 3; i++) {
            try {
                decoded[i] = java.net.URLDecoder.decode(segments[i].replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException notEncoded) {
                decoded[i] = segments[i];
            }
            if (decoded[i].isBlank()) {
                return null;
            }
        }
        return decoded;
    }

    /**
     * Where {@code uri}'s source goes under {@code base}: {@code <archive>/<package as folders>/<Class>.java}, each
     * part made safe for every file system ({@code java.base/java/util/ArrayList.java}); null for another URI. Pure.
     */
    static @Nullable Path fileFor(Path base, String uri) {
        String[] parts = parts(uri);
        if (parts == null) {
            return null;
        }
        Path file = base.resolve(safe(parts[0]));
        for (String part : parts[1].split("\\.")) {
            if (!part.isEmpty()) {
                file = file.resolve(safe(part));
            }
        }
        String name = parts[2].substring(0, parts[2].length() - ".class".length());
        return file.resolve(safe(name) + ".java");
    }

    /** A path segment with only letters, digits, {@code . _ - $}, never {@code .} or {@code ..}. Pure. */
    static String safe(String part) {
        StringBuilder out = new StringBuilder(part.length());
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == '$' ? c : '_');
        }
        String made = out.toString();
        return made.isEmpty() || made.equals(".") || made.equals("..") ? "_" + made : made;
    }

    /**
     * Writes {@code source}, the text of {@code uri} given by {@code anchor}'s server, to its file, read-only, and
     * remembers it; the file, or null when {@code uri} is no class file or it cannot be written. Blocking: off the FX
     * thread.
     */
    static @Nullable Path write(String uri, Path anchor, String source) {
        String[] parts = parts(uri);
        if (parts == null) {
            return null;
        }
        try {
            Path file = fileFor(folder(), uri);
            if (file == null) {
                return null;
            }
            file = file.toAbsolutePath().normalize();
            byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
            if (!Files.isRegularFile(file) || !java.util.Arrays.equals(Files.readAllBytes(file), bytes)) {
                Files.createDirectories(file.getParent());
                if (Files.exists(file)) {
                    file.toFile().setWritable(true); // its sources were attached since: written again
                }
                Files.write(file, bytes);
            }
            file.toFile().setWritable(false); // a copy: what is edited is the library tab, not this
            FILES.put(file, new Library(uri, anchor, parts[0]));
            return file;
        } catch (IOException | RuntimeException failed) {
            ProjectMapLog.unexpected("writing a library class's source for the Map", failed);
            return null;
        }
    }

    /** This run's folder, made when first needed and removed when Editora exits. */
    private static synchronized Path folder() throws IOException {
        Path made = folder;
        if (made == null) {
            made = Files.createTempDirectory("editora-map-library-")
                    .toAbsolutePath()
                    .normalize();
            folder = made;
            Path removed = made;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> remove(removed), "editora-map-library-cleanup"));
        }
        return made;
    }

    /** Removes {@code base} and everything in it, read-only files included. */
    static void remove(Path base) {
        List<Path> all = new ArrayList<>();
        try (var walk = Files.walk(base)) {
            walk.forEach(all::add);
        } catch (IOException | RuntimeException gone) {
            return;
        }
        for (Path path : all.reversed()) {
            try {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            } catch (IOException | RuntimeException ignored) {
                // left for the system's own temp cleanup
            }
        }
    }
}
