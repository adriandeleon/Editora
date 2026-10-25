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
        if (formatter != null) {
            Map<String, Object> settings = new HashMap<>();
            settings.put("url", formatter.toUri().toString());
            String profile = profileName(formatter);
            if (profile != null) {
                settings.put("profile", profile);
            }
            java.put("format", Map.of("settings", settings));
        }
        return java;
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
