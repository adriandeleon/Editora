package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.editorconfig.EditorConfig;
import org.jspecify.annotations.Nullable;

/**
 * jdtls preferences a project states for itself, read from its own files and added to the {@code java} settings
 * of the session serving it (Java review gaps "import layout" and "formatter profile"):
 *
 * <ul>
 *   <li>IntelliJ's code style in {@code .editorconfig} — {@code ij_java_imports_layout} becomes
 *       {@code java.completion.importOrder}, {@code ij_java_class_count_to_use_import_on_demand} and
 *       {@code ij_java_names_count_to_use_import_on_demand} the organize-imports star thresholds;
 *   <li>an Eclipse formatter profile — the file Spotless formats with ({@code <eclipse><file>} in a pom,
 *       {@code eclipse().configFile(…)} in a Gradle build) or one at a conventional name — becomes
 *       {@code java.format.settings.url}, so Format Document formats as the build does.
 * </ul>
 *
 * Nothing is added for a project that states none of these: jdtls's defaults stand.
 */
public final class JavaProjectSettings {

    private JavaProjectSettings() {}

    /** Formatter profile files looked for at the project root when no build names one. */
    private static final List<String> FORMATTER_NAMES = List.of(
            "eclipse-formatter.xml",
            "eclipse-java-formatter.xml",
            "config/eclipse-formatter.xml",
            "formatter.xml",
            ".settings/eclipse-formatter.xml");

    private static final Pattern POM_SPOTLESS_FILE =
            Pattern.compile("<eclipse>.*?<file>\\s*([^<\\s]+)\\s*</file>", Pattern.DOTALL);
    private static final Pattern GRADLE_SPOTLESS_FILE =
            Pattern.compile("eclipse\\([^)]*\\)\\s*\\.\\s*configFile\\(\\s*(?:file\\()?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern PROFILE_NAME = Pattern.compile("<profile\\b[^>]*\\bname=\"([^\"]+)\"");

    /** The nested {@code java} preferences {@code root} states (empty when it states none). */
    public static Map<String, Object> forRoot(@Nullable Path root) {
        return forRoot(root, null);
    }

    /**
     * As {@link #forRoot(Path)} for a server that names this machine's files by {@code toWire} (one run in a
     * container or WSL), or null for a server on this machine. Such a server cannot read a copy in this machine's
     * temporary folder (fourth review DF2): it is given the project's own profile, by its own name for it.
     */
    public static Map<String, Object> forRoot(
            @Nullable Path root, java.util.function.@Nullable UnaryOperator<String> toWire) {
        if (root == null || com.editora.vfs.Vfs.isRemote(root)) {
            return Map.of();
        }
        Map<String, Object> java = new HashMap<>();
        // A .java file at the root: the sections an IntelliJ-written .editorconfig gives Java files apply.
        Map<String, String> ij = EditorConfig.rawFor(root.resolve("Probe.java"));
        List<String> order = importOrder(ij.get("ij_java_imports_layout"));
        if (order != null) {
            put(java, "completion", "importOrder", order);
        }
        Integer stars = count(ij.get("ij_java_class_count_to_use_import_on_demand"));
        Integer staticStars = count(ij.get("ij_java_names_count_to_use_import_on_demand"));
        if (stars != null || staticStars != null) {
            Map<String, Object> organize = new HashMap<>();
            if (stars != null) {
                organize.put("starThreshold", stars);
            }
            if (staticStars != null) {
                organize.put("staticStarThreshold", staticStars);
            }
            java.put("sources", Map.of("organizeImports", organize));
        }
        Path formatter = formatterProfile(root);
        Path project = root.toAbsolutePath().normalize();
        if (formatter != null) {
            PROFILED.add(project);
            Map<String, Object> settings = new HashMap<>();
            settings.put(
                    "url",
                    toWire != null
                            ? toWire.apply(formatter.toUri().toString())
                            : contentAddressed(formatter).toUri().toString());
            String profile = profileName(formatter);
            if (profile != null) {
                settings.put("profile", profile);
            }
            java.put("format", Map.of("settings", settings));
        } else if (toWire == null && PROFILED.contains(project)) {
            // The project's profile was removed. jdtls ignores an empty url and kept formatting with the old one
            // until a restart (re-review DN4); a profile with no settings puts its own defaults back.
            Path empty = contentAddressed(emptyProfile(), "default");
            if (empty != null) {
                java.put(
                        "format", Map.of("settings", Map.of("url", empty.toUri().toString(), "profile", EMPTY_NAME)));
            }
        }
        return java;
    }

    private static byte[] emptyProfile() {
        return EMPTY_PROFILE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The project folders a formatter profile was pushed for: one removed later is replaced by jdtls's defaults. */
    private static final java.util.Set<Path> PROFILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static final String EMPTY_NAME = "Editora default";

    private static final String EMPTY_PROFILE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<profiles version=\"1\"><profile kind=\"CodeFormatterProfile\" name=\"" + EMPTY_NAME
            + "\" version=\"1\"/></profiles>\n";

    /**
     * A copy of {@code profile} named by its content, or {@code profile} itself when it cannot be made. jdtls
     * reads a formatter profile again only when {@code java.format.settings.url} changes, so an edited profile at
     * the same path kept formatting the old way until a restart (third review AN5); the same content keeps its
     * name, so an unchanged profile is not re-read.
     */
    static Path contentAddressed(Path profile) {
        try {
            Path copy = contentAddressed(java.nio.file.Files.readAllBytes(profile), profile.toString());
            return copy != null ? copy : profile;
        } catch (java.io.IOException | RuntimeException e) {
            return profile;
        }
    }

    /**
     * The copy of {@code bytes} in this process's own folder, or null when it cannot be made. The folder is private
     * to this user and process and goes when it exits, and a copy is checked before it is used: one in a shared
     * temporary folder could belong to another user, unreadable or planted, and they piled up (fourth review DF2).
     * The copy {@code source} had before is deleted when its content changes.
     */
    static @Nullable Path contentAddressed(byte[] bytes, String source) {
        try {
            String hash = java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                    .substring(0, 16);
            Path dir = copiesDir();
            Path copy = dir.resolve(hash + ".xml");
            if (!java.nio.file.Files.exists(copy)
                    || !java.util.Arrays.equals(java.nio.file.Files.readAllBytes(copy), bytes)) {
                Path part = java.nio.file.Files.createTempFile(dir, hash, ".part");
                java.nio.file.Files.write(part, bytes);
                java.nio.file.Files.move(
                        part,
                        copy,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                copy.toFile().deleteOnExit();
            }
            Path before = COPY_OF.put(source, copy);
            if (before != null && !before.equals(copy) && !COPY_OF.containsValue(before)) {
                java.nio.file.Files.deleteIfExists(before);
            }
            return copy;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }

    /** Each profile's current copy, by the profile's path. */
    private static final Map<String, Path> COPY_OF = new java.util.concurrent.ConcurrentHashMap<>();

    private static @Nullable Path copies;

    /** This process's folder of profile copies: owner-only, under a name no one can guess, deleted at exit. */
    static synchronized Path copiesDir() throws java.io.IOException {
        Path dir = copies;
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
            dir = java.nio.file.Files.createTempDirectory("editora-formatter-profiles-");
            dir.toFile().deleteOnExit(); // registered first, so deleted after the copies in it
            copies = dir;
        }
        return dir;
    }

    /** jdtls 1.63's own import order and on-demand thresholds ({@code Preferences}), for a key a project dropped. */
    static final List<String> JDTLS_IMPORT_ORDER = List.of("java", "javax", "org", "com");

    static final int JDTLS_STAR_THRESHOLD = 99;

    /**
     * {@code project} with jdtls's defaults for the import keys it leaves out. A push without a key keeps the
     * value jdtls last had, so a layout removed from {@code .editorconfig} stayed in force until a restart (re-review
     * DN4); the defaults put it back. (A formatter profile removed cannot be taken back this way: jdtls ignores an
     * empty {@code java.format.settings.url}.)
     */
    static Map<String, Object> withJdtlsDefaults(Map<String, Object> project) {
        Map<String, Object> out = new HashMap<>(project);
        Map<String, Object> defaults = new HashMap<>();
        put(defaults, "completion", "importOrder", JDTLS_IMPORT_ORDER);
        defaults.put(
                "sources",
                Map.of(
                        "organizeImports",
                        Map.of("starThreshold", JDTLS_STAR_THRESHOLD, "staticStarThreshold", JDTLS_STAR_THRESHOLD)));
        return merge(defaults, out);
    }

    /** Whether a change to {@code file} can change what {@link #forRoot} reads: its style or build files. */
    public static boolean isStyleSource(java.nio.file.Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        return name.equals(".editorconfig")
                || name.equals("pom.xml")
                || name.startsWith("build.gradle")
                || name.endsWith(".xml") // a formatter profile can be named anything
                || name.endsWith(".prefs");
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> java, String group, String key, Object value) {
        ((Map<String, Object>) java.computeIfAbsent(group, k -> new HashMap<String, Object>())).put(key, value);
    }

    /**
     * IntelliJ's {@code imports_layout} as jdtls's {@code importOrder}: {@code *,|,javax.**,java.**,|,$*} gives
     * {@code ["", "javax", "java", "#"]} — {@code |} (a blank line) dropped, {@code *} every other import,
     * {@code $} a static group. Null for a missing or empty value. Pure.
     */
    static @Nullable List<String> importOrder(@Nullable String layout) {
        if (layout == null || layout.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String raw : layout.split(",")) {
            String entry = raw.strip();
            if (entry.isEmpty() || entry.equals("|")) {
                continue;
            }
            boolean isStatic = entry.startsWith("$");
            String name = isStatic ? entry.substring(1) : entry;
            if (name.endsWith(".**")) {
                name = name.substring(0, name.length() - 3);
            } else if (name.endsWith(".*")) {
                name = name.substring(0, name.length() - 2);
            }
            if (name.equals("*")) {
                name = "";
            }
            out.add(isStatic ? "#" + name : name);
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    private static @Nullable Integer count(@Nullable String value) {
        try {
            int n = value == null ? -1 : Integer.parseInt(value.strip());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The Eclipse formatter profile {@code root}'s build or root folder names, or null. */
    static @Nullable Path formatterProfile(Path root) {
        for (String build : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            String text = read(root.resolve(build));
            if (text == null) {
                continue;
            }
            Matcher m = (build.equals("pom.xml") ? POM_SPOTLESS_FILE : GRADLE_SPOTLESS_FILE).matcher(text);
            if (m.find()) {
                String name = m.group(1).replace("${project.basedir}/", "").replace("${basedir}/", "");
                Path file = root.resolve(name).normalize();
                if (isEclipseProfile(file)) {
                    return file;
                }
            }
        }
        for (String name : FORMATTER_NAMES) {
            Path file = root.resolve(name);
            if (isEclipseProfile(file)) {
                return file;
            }
        }
        return null;
    }

    /** Whether {@code file} is an Eclipse formatter export (a {@code <profiles>} of JDT formatter settings). */
    private static boolean isEclipseProfile(Path file) {
        String text = read(file);
        return text != null && text.contains("<profiles") && text.contains("org.eclipse.jdt.core.formatter");
    }

    private static @Nullable String profileName(Path file) {
        String text = read(file);
        Matcher m = text == null ? null : PROFILE_NAME.matcher(text);
        return m != null && m.find() ? m.group(1) : null;
    }

    private static @Nullable String read(Path file) {
        try {
            return Files.isRegularFile(file) && Files.size(file) < 2_000_000 ? Files.readString(file) : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** {@code base} with {@code extra} merged in, nested maps merged key by key, {@code extra} winning. Pure. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> out = new HashMap<>(base);
        for (Map.Entry<String, Object> e : extra.entrySet()) {
            Object mine = out.get(e.getKey());
            out.put(
                    e.getKey(),
                    mine instanceof Map<?, ?> m && e.getValue() instanceof Map<?, ?> x
                            ? merge((Map<String, Object>) m, (Map<String, Object>) x)
                            : e.getValue());
        }
        return out;
    }
}
