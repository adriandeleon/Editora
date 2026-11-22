package com.editora.snippet;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.editora.config.migration.UserFileVersion;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jspecify.annotations.Nullable;

/**
 * A snippet file ({@code snippets/<lang>.json}, a plugin's, a bundled one) as Jackson reads and writes it (PLAT-14,
 * Pauta 6.2.0): its snippets by name, in file order, and the format version it carries ({@code schemaVersion}, a
 * number beside the snippets). Each snippet is kept as its JSON value, so one that is not an object (a mistake the
 * snippet loader reports) is written back unchanged; {@link SnippetJson} is the shape of one that is.
 *
 * <p>Read as VS Code reads its snippet files: {@code //} and block comments and trailing commas are accepted. The
 * comments are not kept: a file the editor saves is written whole, from this object.
 */
@JsonDeserialize(using = UserSnippetFile.Reader.class)
@JsonPropertyOrder({UserFileVersion.KEY})
final class UserSnippetFile {

    /** The mapper every snippet file is read and written with: lenient, as VS Code's own snippet files need. */
    static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    /** Two-space indents, {@code "name": value}, one array element per line, and {@code \n} on every system. */
    private static final ObjectWriter WRITER = MAPPER.writer(new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"))
            .withSeparators(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                    .withObjectEmptySeparator("")
                    .withArrayEmptySeparator("")));

    /** The text is valid JSONC but its top level is not an object (an array, a string, …). */
    static final class NotAnObjectException extends IOException {
        private static final long serialVersionUID = 1L;

        NotAnObjectException() {
            super("not an object");
        }
    }

    /** The {@code schemaVersion} member's value, or null when the file has none. */
    @JsonProperty(UserFileVersion.KEY)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private @Nullable Long schemaVersion;

    private final LinkedHashMap<String, JsonNode> snippets = new LinkedHashMap<>();

    /** 1-based line of each snippet's name in the text it was read from (none for one added since). */
    private final Map<String, Integer> lines = new LinkedHashMap<>();

    /** The snippets by name, in file order (the version member is not one of them). */
    @JsonAnyGetter
    Map<String, JsonNode> snippets() {
        return snippets;
    }

    /** The version the file carries: its {@code schemaVersion}, else the format before versions were written. */
    int version() {
        Long v = schemaVersion;
        return v == null ? UserFileVersion.BEFORE_VERSIONS : (int) Math.min(v, Integer.MAX_VALUE);
    }

    /** Whether the file carries a version member. */
    boolean hasVersion() {
        return schemaVersion != null;
    }

    /** Sets the version member ({@code null}: none). */
    void setVersion(@Nullable Long version) {
        schemaVersion = version;
    }

    /** The 1-based line {@code name} stood on in the text read, or 0. */
    int lineOf(String name) {
        return lines.getOrDefault(name, 0);
    }

    @Nullable
    JsonNode get(String name) {
        return snippets.get(name);
    }

    /**
     * Puts {@code value} under {@code name}: in the place of {@code oldName} when the file has it (a rename keeps
     * its place), else where {@code name} already is, else at the end.
     */
    void put(@Nullable String oldName, String name, JsonNode value) {
        if (oldName == null || oldName.equals(name) || !snippets.containsKey(oldName)) {
            snippets.put(name, value);
            return;
        }
        LinkedHashMap<String, JsonNode> copy = new LinkedHashMap<>();
        snippets.forEach((k, v) -> {
            if (k.equals(oldName)) {
                copy.put(name, value);
            } else if (!k.equals(name)) {
                copy.put(k, v);
            }
        });
        snippets.clear();
        snippets.putAll(copy);
    }

    /** Removes the snippet called {@code name}; whether there was one. */
    boolean remove(String name) {
        return snippets.remove(name) != null;
    }

    /**
     * Reads a snippet file's text. Empty text, text of nothing but comments, and the literal {@code null} are a file
     * without snippets.
     *
     * @throws NotAnObjectException when the text is JSON but not an object
     * @throws IOException when it is not JSON (a {@link com.fasterxml.jackson.core.JsonProcessingException}, which
     *     says where)
     */
    static UserSnippetFile parse(@Nullable String text) throws IOException {
        try (JsonParser p = MAPPER.createParser(text == null ? "" : text)) {
            JsonToken first = p.nextToken();
            if (first == null || first == JsonToken.VALUE_NULL) {
                return new UserSnippetFile();
            }
            if (first != JsonToken.START_OBJECT) {
                throw new NotAnObjectException();
            }
            UserSnippetFile file = MAPPER.readValue(p, UserSnippetFile.class);
            p.nextToken(); // garbage after the object is a syntax error like any other
            return file;
        }
    }

    /** The file's text as the editor writes it, ending in a line break. */
    String text() throws IOException {
        return WRITER.writeValueAsString(this) + "\n";
    }

    /** Whether {@code name} with {@code value} is the version marker rather than a snippet: a whole number ≥ 0. */
    static boolean isVersion(String name, JsonNode value) {
        return UserFileVersion.KEY.equals(name)
                && value.isIntegralNumber()
                && value.bigIntegerValue().signum() >= 0;
    }

    /** Reads the object member by member, keeping where each name stood. */
    static final class Reader extends StdDeserializer<UserSnippetFile> {
        private static final long serialVersionUID = 1L;

        Reader() {
            super(UserSnippetFile.class);
        }

        @Override
        public UserSnippetFile deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() != JsonToken.START_OBJECT) {
                throw new NotAnObjectException();
            }
            UserSnippetFile file = new UserSnippetFile();
            for (JsonToken t = p.nextToken(); t == JsonToken.FIELD_NAME; t = p.nextToken()) {
                String name = p.currentName();
                int line = p.currentTokenLocation().getLineNr();
                p.nextToken();
                JsonNode value = ctxt.readTree(p);
                if (isVersion(name, value)) {
                    file.schemaVersion = value.canConvertToLong() ? value.longValue() : Long.MAX_VALUE;
                    continue;
                }
                file.snippets.put(name, value); // a name given twice: its last value, as JSON readers keep
                file.lines.put(name, line);
            }
            return file;
        }
    }
}
