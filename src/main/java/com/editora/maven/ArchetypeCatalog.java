package com.editora.maven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The archetypes offered by the New Maven Project wizard: a small curated list bundled with the app, plus —
 * on demand — everything in Maven Central's {@code archetype-catalog.xml}.
 *
 * <p>The curated list is read once from {@code resources/com/editora/archetypes/catalog.json} through the
 * module's own classloader, the same idiom as the keymap/snippet/technical-dictionary resources, so no
 * {@code module-info} {@code opens} is required. It is parsed with {@code readTree} rather than bound to a
 * DTO for the same reason — no reflection, no Jackson {@code opens}.
 *
 * <p>Curated entries carry {@code curated = true}; that flag is what lets the wizard skip the consent prompt
 * for archetypes we vetted and require it for anything the user typed or pulled from the remote catalog.
 */
public final class ArchetypeCatalog {

    private static final String RESOURCE = "/com/editora/archetypes/catalog.json";

    private static volatile List<MavenArchetype> curated; // lazily loaded, immutable once set

    private ArchetypeCatalog() {}

    /** The bundled curated archetypes, loaded once. Never null; empty if the resource is missing/malformed. */
    public static List<MavenArchetype> curated() {
        List<MavenArchetype> c = curated;
        if (c == null) {
            synchronized (ArchetypeCatalog.class) {
                c = curated;
                if (c == null) {
                    c = load();
                    curated = c;
                }
            }
        }
        return c;
    }

    /** The archetype the wizard starts on when the user has expressed no preference. */
    public static MavenArchetype defaultArchetype() {
        return curated().stream()
                .filter(a -> "maven-archetype-quickstart".equals(a.artifactId()))
                .findFirst()
                .orElseGet(() -> curated().isEmpty() ? null : curated().get(0));
    }

    /**
     * Curated entries first, then everything from {@code fetched} that the curated list does not already
     * cover (matched on {@code groupId:artifactId}, so a curated pin is not shadowed by the catalog's own
     * — often much older — version of the same archetype).
     */
    public static List<MavenArchetype> merge(List<MavenArchetype> fetched) {
        Map<String, MavenArchetype> byKey = new LinkedHashMap<>();
        for (MavenArchetype a : curated()) {
            byKey.put(a.key(), a);
        }
        if (fetched != null) {
            for (MavenArchetype a : fetched) {
                byKey.putIfAbsent(a.key(), a);
            }
        }
        return List.copyOf(byKey.values());
    }

    private static List<MavenArchetype> load() {
        try (InputStream in = ArchetypeCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            JsonNode root = new ObjectMapper().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            JsonNode list = root.isArray() ? root : root.path("archetypes");
            List<MavenArchetype> out = new ArrayList<>();
            for (JsonNode n : list) {
                String groupId = n.path("groupId").asText("");
                String artifactId = n.path("artifactId").asText("");
                String version = n.path("version").asText("");
                if (groupId.isBlank() || artifactId.isBlank() || version.isBlank()) {
                    continue;
                }
                out.add(new MavenArchetype(
                        groupId,
                        artifactId,
                        version,
                        n.path("description").asText(""),
                        n.path("repository").asText(""),
                        true));
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            return List.of(); // a broken bundled resource must not stop the app
        }
    }
}
