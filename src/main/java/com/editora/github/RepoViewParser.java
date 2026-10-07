package com.editora.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses {@code gh repo view --json nameWithOwner,defaultBranchRef,url} — the repository {@code gh} resolved
 * for a working directory. With a fork's {@code origin} beside an {@code upstream} remote that is the
 * upstream repository, not the fork, which is why the tool window names it. Same tolerant,
 * {@code readTree}-based approach as {@link PrViewParser}; {@code null} on bad input. Pure — unit-tested.
 */
public final class RepoViewParser {

    private RepoViewParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The resolved repository: {@code owner/name}, its default branch ({@code ""} when unknown) and URL. */
    public record RepoInfo(String nameWithOwner, String defaultBranch, String url) {}

    /** Parses the {@code gh repo view --json …} object, or {@code null} on bad/empty input. */
    public static RepoInfo parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n == null || !n.isObject()) {
                return null;
            }
            String name = text(n, "nameWithOwner");
            if (name.isEmpty()) {
                return null;
            }
            return new RepoInfo(name, text(n.path("defaultBranchRef"), "name"), text(n, "url"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }
}
