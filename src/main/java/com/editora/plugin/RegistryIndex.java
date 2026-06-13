package com.editora.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A remote plugin registry's parsed {@code index.json}: a schema version + a flat list of
 * {@link RegistryEntry}. Curated and hosted by the user (e.g. a {@code raw.githubusercontent.com} file or
 * GitHub Pages); Editora fetches it over HTTPS and lets the user install entries. Lenient Jackson POJO.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegistryIndex {

    /** Index format version (currently 1); reserved for future migrations. */
    public int schemaVersion = 1;

    public List<RegistryEntry> plugins = new ArrayList<>();

    /** Parses an {@code index.json} stream. Pure (no I/O beyond the stream) — unit-tested. */
    public static RegistryIndex parse(ObjectMapper mapper, InputStream in) throws IOException {
        RegistryIndex idx = mapper.readValue(in, RegistryIndex.class);
        if (idx.plugins == null) {
            idx.plugins = new ArrayList<>();
        }
        // Drop entries with no id (malformed) so callers never see a half-formed row.
        idx.plugins.removeIf(e -> e == null || e.id == null || e.id.isBlank());
        // Defensive: cap displayed strings so a hostile registry can't bloat the UI with megabyte fields.
        for (RegistryEntry e : idx.plugins) {
            e.id = cap(e.id, 100);
            e.name = cap(e.name, 120);
            e.version = cap(e.version, 40);
            e.description = cap(e.description, 400);
            e.author = cap(e.author, 120);
            e.homepage = cap(e.homepage, 500);
            e.download = cap(e.download, 1000);
            e.sha256 = cap(e.sha256, 128);
            e.minEditoraVersion = cap(e.minEditoraVersion, 40);
        }
        return idx;
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
