package com.editora.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The parsed archetype catalog on disk — {@code <config>/cache/archetype-catalog.json} — so Maven Central's
 * 18 MB download happens at most once a day across every window and session, not once per window per
 * "Load full catalog…".
 *
 * <p>What is cached is the <em>parsed</em> result (a few thousand records, ~400 KB), never the XML, plus the
 * response's validators ({@code ETag}, {@code Last-Modified}) so a refresh after {@link #MAX_AGE} can be a
 * conditional GET that costs one round trip when nothing changed — the file on Central changes about monthly.
 *
 * <p>Pure file I/O over the tree model ({@code readTree}/{@code ObjectNode}; no reflection, so no Jackson
 * {@code opens}). A missing or unreadable cache is simply "no cache"; a write failure is the caller's to
 * report or ignore (the catalog was still loaded).
 */
public final class ArchetypeCatalogCache {

    /** How long a cached catalog is used without even asking Central whether it changed. */
    public static final Duration MAX_AGE = Duration.ofDays(1);

    private static final int FORMAT = 1;

    private ArchetypeCatalogCache() {}

    /**
     * One cached catalog.
     *
     * @param archetypes the parsed rows (newest per key)
     * @param etag the response's {@code ETag}, or "" when it sent none
     * @param lastModified the response's {@code Last-Modified}, or "" when it sent none
     * @param fetchedAt epoch millis of the download (or of the last {@code 304 Not Modified} revalidation)
     */
    public record Entry(List<MavenArchetype> archetypes, String etag, String lastModified, long fetchedAt) {

        public Entry {
            archetypes = archetypes == null ? List.of() : List.copyOf(archetypes);
            etag = etag == null ? "" : etag;
            lastModified = lastModified == null ? "" : lastModified;
        }

        /** Younger than {@link #MAX_AGE} at {@code now}: used as is, no request made. */
        public boolean isFresh(long now) {
            return now >= fetchedAt && now - fetchedAt < MAX_AGE.toMillis();
        }

        /** Whether a conditional request can be made at all. */
        public boolean hasValidators() {
            return !etag.isEmpty() || !lastModified.isEmpty();
        }

        /** The same rows and validators, revalidated at {@code now}. */
        public Entry revalidated(long now) {
            return new Entry(archetypes, etag, lastModified, now);
        }
    }

    /** Where the cache lives for a config folder: {@code <config>/cache/archetype-catalog.json}. */
    public static Path file(Path configDir) {
        return configDir == null ? null : configDir.resolve("cache").resolve("archetype-catalog.json");
    }

    /** The cached entry, or null when there is none or it cannot be read (then it is as if there were none). */
    public static Entry read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject() || root.path("format").asInt(0) != FORMAT) {
                return null;
            }
            List<MavenArchetype> rows = new ArrayList<>();
            for (JsonNode n : root.path("archetypes")) {
                String groupId = n.path("groupId").asText("");
                String artifactId = n.path("artifactId").asText("");
                String version = n.path("version").asText("");
                if (groupId.isBlank() || artifactId.isBlank() || version.isBlank()) {
                    continue;
                }
                rows.add(new MavenArchetype(
                        groupId,
                        artifactId,
                        version,
                        n.path("description").asText(""),
                        n.path("repository").asText(""),
                        false));
            }
            return new Entry(
                    rows,
                    root.path("etag").asText(""),
                    root.path("lastModified").asText(""),
                    root.path("fetchedAt").asLong(0));
        } catch (IOException | RuntimeException e) {
            return null; // a damaged cache is no cache
        }
    }

    /** Writes {@code entry} atomically (temp file + move), creating the cache folder as needed. */
    public static void write(Path file, Entry entry) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("format", FORMAT);
        root.put("fetchedAt", entry.fetchedAt());
        root.put("etag", entry.etag());
        root.put("lastModified", entry.lastModified());
        ArrayNode rows = root.putArray("archetypes");
        for (MavenArchetype a : entry.archetypes()) {
            ObjectNode n = rows.addObject();
            n.put("groupId", a.groupId());
            n.put("artifactId", a.artifactId());
            n.put("version", a.version());
            if (!a.description().isEmpty()) {
                n.put("description", a.description());
            }
            if (a.hasRepository()) {
                n.put("repository", a.repository());
            }
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
