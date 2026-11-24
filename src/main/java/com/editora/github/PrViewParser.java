package com.editora.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Parses a single {@code gh pr view <n> --json number,title,body,author,baseRefName,headRefName,headRefOid,state,
 * url,additions,deletions} object into a {@link PrDetail}. Same tolerant, {@code readTree}-based approach as
 * {@link PrListParser}; returns {@code null} on bad input. Pure — unit-tested.
 */
public final class PrViewParser {

    private PrViewParser() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * A pull request's detail, for the diff-tab header / (slice 2) the panel tooltip. {@code headRefOid} is the head
     * commit the detail was read at ({@code ""} when not known): a review from the review tab is pinned to it.
     */
    public record PrDetail(
            int number,
            String title,
            String body,
            String authorLogin,
            String baseRefName,
            String headRefName,
            String state,
            String url,
            int additions,
            int deletions,
            String headRefOid) {

        public PrDetail(
                int number,
                String title,
                String body,
                String authorLogin,
                String baseRefName,
                String headRefName,
                String state,
                String url,
                int additions,
                int deletions) {
            this(number, title, body, authorLogin, baseRefName, headRefName, state, url, additions, deletions, "");
        }

        /** This detail without its head commit: a review from a tab showing it is not pinned to a commit. */
        public PrDetail unpinned() {
            return new PrDetail(
                    number, title, body, authorLogin, baseRefName, headRefName, state, url, additions, deletions, "");
        }
    }

    /** The {@code headRefOid} of a {@code gh pr view --json headRefOid} answer, or {@code null} when unreadable. */
    public static @Nullable String headRefOid(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            String oid = n == null || !n.isObject() ? "" : text(n, "headRefOid").strip();
            return oid.isEmpty() ? null : oid;
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses the {@code gh pr view --json …} object, or {@code null} on bad/empty input. */
    public static @Nullable PrDetail parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n == null || !n.isObject()) {
                return null;
            }
            return new PrDetail(
                    n.path("number").asInt(0),
                    text(n, "title"),
                    text(n, "body"),
                    text(n.path("author"), "login"),
                    text(n, "baseRefName"),
                    text(n, "headRefName"),
                    text(n, "state"),
                    text(n, "url"),
                    n.path("additions").asInt(0),
                    n.path("deletions").asInt(0),
                    text(n, "headRefOid"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }
}
