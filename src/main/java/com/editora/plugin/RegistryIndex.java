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
        return idx;
    }
}
