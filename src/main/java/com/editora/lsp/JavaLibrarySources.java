package com.editora.lsp;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * jdtls's library sources: the settings that download every dependency's sources at import
 * ({@code java.maven.downloadSources}, {@code java.eclipse.downloadSources} for Gradle), its
 * {@code java.project.resolveSourceAttachment} and {@code updateSourceAttachment} commands, and its
 * {@code SourceInvalidated} event, sent when a library's sources were attached later (a download that outlasted
 * the class file's first read, or one the user chose). Pure.
 */
public final class JavaLibrarySources {

    private JavaLibrarySources() {}

    /** The command that says which sources a class file's library has. */
    public static final String RESOLVE = "java.project.resolveSourceAttachment";

    /** The command that attaches a sources archive to a class file's library. */
    public static final String UPDATE = "java.project.updateSourceAttachment";

    /** jdtls's {@code EventType.SourceInvalidated}, sent as its number. */
    static final int SOURCE_INVALIDATED = 500;

    private static final Gson GSON = new Gson();

    /** Puts the download-at-import switches into jdtls's {@code java} settings object. */
    static void into(Map<String, Object> java, boolean download) {
        java.put("maven", merged(java.get("maven"), download));
        java.put("eclipse", merged(java.get("eclipse"), download));
    }

    private static Map<String, Object> merged(@Nullable Object existing, boolean download) {
        Map<String, Object> out = new HashMap<>();
        if (existing instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        out.put("downloadSources", download);
        return out;
    }

    /**
     * jdtls's {@code initializationOptions} with the download switches in {@code settings.java}, so the first import
     * already honours them; {@code options} as they are for another server or shape.
     */
    @SuppressWarnings("unchecked")
    static @Nullable Object withDownloadSources(@Nullable Object options, boolean download) {
        if (!(options instanceof Map<?, ?> opts) || !(opts.get("settings") instanceof Map<?, ?> settings)) {
            return options;
        }
        Map<String, Object> java = new HashMap<>();
        if (settings.get("java") instanceof Map<?, ?> j) {
            j.forEach((k, v) -> java.put(String.valueOf(k), v));
        }
        into(java, download);
        Map<String, Object> newSettings = new HashMap<>((Map<String, Object>) settings);
        newSettings.put("java", java);
        Map<String, Object> out = new HashMap<>((Map<String, Object>) opts);
        out.put("settings", newSettings);
        return out;
    }

    /**
     * The library paths (jars) a {@code language/eventNotification} says got sources ({@code SourceInvalidated},
     * {@code data.affectedRootPaths}' keys); empty for any other event.
     */
    static Set<String> invalidatedRoots(@Nullable Object eventType, @Nullable Object data) {
        boolean sources = eventType instanceof Number n
                ? n.intValue() == SOURCE_INVALIDATED
                : "SourceInvalidated".equals(String.valueOf(eventType))
                        || String.valueOf(SOURCE_INVALIDATED).equals(String.valueOf(eventType));
        if (!sources || data == null) {
            return Set.of();
        }
        JsonElement json = data instanceof JsonElement e ? e : GSON.toJsonTree(data);
        if (!json.isJsonObject() || !(json.getAsJsonObject().get("affectedRootPaths") instanceof JsonObject roots)) {
            return Set.of();
        }
        return new LinkedHashSet<>(roots.keySet());
    }

    /**
     * The archive a {@code jdt://contents/<archive>/<package>/<Class>.class?…} URI's class is in: its file name
     * ({@code guava-33.3.1-jre.jar}), or null for another URI.
     */
    public static @Nullable String archiveName(@Nullable String classFileUri) {
        String prefix = "jdt://contents/";
        if (classFileUri == null || !classFileUri.startsWith(prefix)) {
            return null;
        }
        int end = classFileUri.indexOf('/', prefix.length());
        return end <= prefix.length() ? null : classFileUri.substring(prefix.length(), end);
    }

    /** Whether the class at {@code classFileUri} is in one of {@code roots}, the paths of the libraries that changed. */
    public static boolean inAny(String classFileUri, Set<String> roots) {
        String name = archiveName(classFileUri);
        String query = decodedQuery(classFileUri);
        return name != null
                && roots.stream()
                        .map(r -> r.replace('\\', '/'))
                        .anyMatch(r -> r.endsWith("/" + name)
                                || r.equals(name)
                                || (!r.isEmpty() && query.contains(r))); // a JDK module's jrt-fs.jar (LS2)
    }

    /**
     * Whether a repository may have the sources of {@code classFileUri}'s library: a Maven artifact's URI names its
     * coordinates; a {@code lib/} or system jar, or a JDK module, has nothing to download (LS4).
     */
    public static boolean downloadable(String classFileUri) {
        return decodedQuery(classFileUri).contains("maven.groupId");
    }

    /** The part of a {@code jdt://} URI after its {@code ?}, decoded, with jdtls's {@code \\/} as {@code /}. */
    private static String decodedQuery(String classFileUri) {
        int q = classFileUri.indexOf('?');
        if (q < 0) {
            return "";
        }
        String raw = classFileUri.substring(q + 1);
        try {
            raw = java.net.URLDecoder.decode(raw.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // not percent-encoded as expected: read as it is
        }
        return raw.replace("\\/", "/").replace('\\', '/');
    }

    /** {@link #RESOLVE}'s and {@link #UPDATE}'s one argument: the class file and, to attach, the sources archive. */
    public static List<Object> request(String classFileUri, @Nullable String jarPath, @Nullable String sourcesPath) {
        JsonObject request = new JsonObject();
        request.addProperty("classFileUri", classFileUri);
        if (sourcesPath != null) {
            JsonObject attributes = new JsonObject();
            attributes.addProperty("jarPath", jarPath);
            attributes.addProperty("sourceAttachmentPath", sourcesPath);
            request.add("attributes", attributes);
        }
        return List.of(request);
    }

    /** What {@link #RESOLVE} or {@link #UPDATE} answered: an error, the library's jar and its sources (null: none). */
    public record Attachment(
            @Nullable String error,
            @Nullable String jarPath,
            @Nullable String sourcesPath) {
        /** Whether the library has sources, at a path that is there. */
        public boolean attached() {
            return sourcesPath != null
                    && !sourcesPath.isBlank()
                    && java.nio.file.Files.exists(java.nio.file.Path.of(sourcesPath));
        }
    }

    /** {@code result} (a command's answer, as JSON or a map) as an {@link Attachment}; null when it is none. */
    public static @Nullable Attachment attachment(@Nullable Object result) {
        if (result == null) {
            return null;
        }
        JsonElement json = result instanceof JsonElement e ? e : GSON.toJsonTree(result);
        if (!json.isJsonObject()) {
            return null;
        }
        JsonObject o = json.getAsJsonObject();
        JsonObject a = o.get("attributes") instanceof JsonObject attributes ? attributes : new JsonObject();
        return new Attachment(text(o, "errorMessage"), text(a, "jarPath"), text(a, "sourceAttachmentPath"));
    }

    private static @Nullable String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
