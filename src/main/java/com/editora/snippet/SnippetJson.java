package com.editora.snippet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * One snippet of a snippet file, in the VS Code format, as Jackson reads and writes it (PLAT-14, Pauta 6.2.0).
 * {@code prefix}, {@code body} and {@code description} are a string or an array of strings, {@code scope} a
 * comma-separated list of language ids; {@code disabled} is Editora's own, a user entry that switches the snippet of
 * its name off. They are kept as the file wrote them (a string, a list, …) so that a save keeps an array an array.
 * Any other field a VS Code file carries is kept in {@link #other} and written back.
 */
@JsonPropertyOrder({"prefix", "body", "description", "scope", "disabled"})
@JsonInclude(JsonInclude.Include.NON_NULL)
final class SnippetJson {

    public @Nullable Object prefix;
    public @Nullable Object body;
    public @Nullable Object description;
    public @Nullable Object scope;
    public @Nullable Object disabled;

    private final LinkedHashMap<String, @Nullable Object> other = new LinkedHashMap<>();

    /** The fields this class does not name, in file order. */
    @JsonAnyGetter
    Map<String, @Nullable Object> other() {
        return other;
    }

    @JsonAnySetter
    void other(String name, @Nullable Object value) {
        other.put(name, value);
    }

    /** {@code node} as a snippet; a value that is not an object is an empty one. */
    static SnippetJson of(@Nullable JsonNode node) {
        return node != null && node.isObject()
                ? UserSnippetFile.MAPPER.convertValue(node, SnippetJson.class)
                : new SnippetJson();
    }

    /** This snippet as a JSON value. */
    JsonNode toNode() {
        return UserSnippetFile.MAPPER.valueToTree(this);
    }

    /** A copy of this snippet. */
    SnippetJson copy() {
        return of(toNode());
    }

    /** Whether {@code disabled} switches it off: {@code true}, {@code "true"} or a number other than 0. */
    boolean isDisabled() {
        Object d = disabled;
        if (d instanceof Boolean b) {
            return b;
        }
        if (d instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return d instanceof String s && s.strip().equalsIgnoreCase("true");
    }

    /** Nothing in it at all. */
    boolean isEmpty() {
        return prefix == null
                && body == null
                && description == null
                && scope == null
                && disabled == null
                && other.isEmpty();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof SnippetJson s
                && Objects.equals(prefix, s.prefix)
                && Objects.equals(body, s.body)
                && Objects.equals(description, s.description)
                && Objects.equals(scope, s.scope)
                && Objects.equals(disabled, s.disabled)
                && other.equals(s.other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(prefix, body, description, scope, disabled, other);
    }
}
