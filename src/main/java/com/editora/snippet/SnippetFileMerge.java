package com.editora.snippet;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * A user snippet file as its entries, for merging two machines' copies of it (settings sync): reads the
 * entries of a file's text, and writes another copy's entries into a text without re-serializing the rest —
 * comments, key order and formatting stay as the user wrote them ({@link JsoncObject}). Pure.
 */
public final class SnippetFileMerge {

    private SnippetFileMerge() {}

    // The same leniency the snippet reader has: VS Code snippet files carry comments and trailing commas.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private static final ObjectWriter WRITER = MAPPER.writer(new DefaultPrettyPrinter()
            .withObjectIndenter(new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n"))
            .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER)));

    /**
     * The entries of a snippet file: name to its value as compact JSON, in file order. Two values are the same
     * entry exactly when these strings are equal. An absent or blank text has none.
     *
     * @throws IOException when the text is not a JSONC object
     */
    public static Map<String, String> entries(String text) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        JsoncObject doc = JsoncObject.parse(text, MAPPER.getFactory());
        for (JsoncObject.Member m : doc.members) {
            JsonNode value = MAPPER.readTree(doc.valueText(m));
            out.put(m.name(), MAPPER.writeValueAsString(value));
        }
        return out;
    }

    /**
     * {@code text} with each of {@code changes} applied: a name mapped to compact JSON replaces that entry
     * where it stands or is appended; a name mapped to {@code null} is removed.
     *
     * @throws IOException when the text is not a JSONC object
     */
    public static String apply(String text, Map<String, String> changes) throws IOException {
        String current = text == null ? "" : text;
        for (Map.Entry<String, String> change : changes.entrySet()) {
            JsoncObject doc = JsoncObject.parse(current, MAPPER.getFactory());
            if (change.getValue() == null) {
                current = doc.without(change.getKey());
            } else {
                String pretty = WRITER.writeValueAsString(MAPPER.readTree(change.getValue()));
                current = doc.with(change.getKey(), MAPPER.writeValueAsString(change.getKey()), pretty);
            }
        }
        return current;
    }
}
