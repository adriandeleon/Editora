package com.editora.snippet;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.editora.config.migration.UserFileVersion;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * A user snippet file as its entries, for merging two machines' copies of it (settings sync): reads the entries of
 * a file's text, and writes another copy's entries into it. Read and written through Jackson as a
 * {@link UserSnippetFile} (PLAT-14, Pauta 6.2.0): a merged file keeps its key order, and loses its comments. Pure.
 */
public final class SnippetFileMerge {

    private SnippetFileMerge() {}

    /**
     * The entries of a snippet file: name to its value as compact JSON, in file order, the version marker
     * ({@code schemaVersion}) among them when the file has one. Two values are the same entry exactly when these
     * strings are equal. An absent or blank text has none.
     *
     * @throws IOException when the text is not a JSONC object
     */
    public static Map<String, String> entries(String text) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        UserSnippetFile doc = UserSnippetFile.parse(text);
        for (Map.Entry<String, JsonNode> e : doc.snippets().entrySet()) {
            out.put(e.getKey(), UserSnippetFile.MAPPER.writeValueAsString(e.getValue()));
        }
        if (doc.hasVersion()) {
            out.put(UserFileVersion.KEY, String.valueOf(doc.version()));
        }
        return out;
    }

    /**
     * {@code text} with each of {@code changes} applied: a name mapped to compact JSON replaces that entry where it
     * stands or is appended; a name mapped to {@code null} is removed. Written whole, through Jackson.
     *
     * @throws IOException when the text is not a JSONC object
     */
    public static String apply(@Nullable String text, Map<String, String> changes) throws IOException {
        UserSnippetFile doc = UserSnippetFile.parse(text == null ? "" : text);
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String name = change.getKey();
            JsonNode value = change.getValue() == null ? null : UserSnippetFile.MAPPER.readTree(change.getValue());
            if (value != null && UserSnippetFile.isVersion(name, value)) {
                doc.setVersion(value.canConvertToLong() ? value.longValue() : Long.MAX_VALUE);
            } else if (value == null) {
                if (UserFileVersion.KEY.equals(name) && doc.get(name) == null) {
                    doc.setVersion(null);
                } else {
                    doc.remove(name);
                }
            } else {
                doc.put(name, name, value);
            }
        }
        return doc.text();
    }
}
