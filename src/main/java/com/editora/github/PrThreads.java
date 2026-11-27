package com.editora.github;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * A pull request's review threads on GitHub: the GraphQL calls that read them, reply to one, and resolve or unresolve
 * one, through {@code gh api graphql}, and the parsing of the answer. Pure: it builds {@link GhInvocation}s and reads
 * JSON; {@link GitHubService} runs them. A reply's body goes on standard input ({@code -F body=@-}), never as an
 * argument.
 */
public final class PrThreads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How many threads, and comments per thread, one read brings (GitHub's page size limit is 100). */
    static final int MAX_THREADS = 100;

    static final int MAX_COMMENTS = 50;

    private PrThreads() {}

    /** One comment of a thread. */
    public record Comment(String author, String body, String createdAt, String url) {}

    /**
     * One review thread: its GraphQL id, file, side ({@code left}: the base), lines (1-based; {@code line} is 0 for a
     * thread GitHub no longer places on the diff, which is then {@code outdated}), whether it is resolved, and its
     * comments, oldest first.
     */
    public record Thread(
            String id,
            String path,
            boolean left,
            int startLine,
            int line,
            boolean resolved,
            boolean outdated,
            List<Comment> comments) {

        /** Whether this thread is marked on {@code line} of {@code path}'s {@code left} side. */
        public boolean on(String filePath, boolean leftSide, int fileLine) {
            return line > 0 && path.equals(filePath) && left == leftSide && line == fileLine;
        }
    }

    private static final String QUERY = "query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,"
            + "name:$name){pullRequest(number:$number){reviewThreads(first:" + MAX_THREADS + "){nodes{id isResolved"
            + " isOutdated path line startLine originalLine originalStartLine diffSide comments(first:" + MAX_COMMENTS
            + "){nodes{author{login} body createdAt url}}}}}}}";

    private static final String REPLY = "mutation($id:ID!,$body:String!){addPullRequestReviewThreadReply(input:"
            + "{pullRequestReviewThreadId:$id,body:$body}){comment{id}}}";

    private static final String RESOLVE = "mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{id}}}";

    private static final String UNRESOLVE =
            "mutation($id:ID!){unresolveReviewThread(input:{threadId:$id}){thread{id}}}";

    /** The call that reads pull request {@code number}'s threads, or null when {@code prUrl} names no repository. */
    public static @Nullable GhInvocation read(@Nullable String prUrl, int number) {
        PrReviewArgs.Target t = PrReviewArgs.target(prUrl, number);
        if (t == null) {
            return null;
        }
        String[] ownerName = t.repo().split("/", 2);
        return new GhInvocation(
                List.of(
                        "api",
                        "graphql",
                        "--hostname",
                        t.host(),
                        "-F",
                        "owner=" + ownerName[0],
                        "-F",
                        "name=" + ownerName[1],
                        "-F",
                        "number=" + number,
                        "-f",
                        "query=" + QUERY),
                null);
    }

    /** The call that adds {@code body} as a reply to thread {@code threadId}. */
    public static @Nullable GhInvocation reply(@Nullable String prUrl, int number, String threadId, String body) {
        PrReviewArgs.Target t = PrReviewArgs.target(prUrl, number);
        if (t == null) {
            return null;
        }
        return new GhInvocation(
                List.of(
                        "api",
                        "graphql",
                        "--hostname",
                        t.host(),
                        "-f",
                        "id=" + threadId,
                        "-F",
                        "body=@-",
                        "-f",
                        "query=" + REPLY),
                body);
    }

    /** The call that resolves ({@code resolve}) or unresolves thread {@code threadId}. */
    public static @Nullable GhInvocation resolve(@Nullable String prUrl, int number, String threadId, boolean resolve) {
        PrReviewArgs.Target t = PrReviewArgs.target(prUrl, number);
        if (t == null) {
            return null;
        }
        return new GhInvocation(
                List.of(
                        "api",
                        "graphql",
                        "--hostname",
                        t.host(),
                        "-f",
                        "id=" + threadId,
                        "-f",
                        "query=" + (resolve ? RESOLVE : UNRESOLVE)),
                null);
    }

    /** The threads in a {@link #read} answer; none when it cannot be read. */
    public static List<Thread> parse(@Nullable String json) {
        List<Thread> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (java.io.IOException e) {
            return out;
        }
        JsonNode nodes = root.path("data")
                .path("repository")
                .path("pullRequest")
                .path("reviewThreads")
                .path("nodes");
        for (JsonNode n : nodes) {
            List<Comment> comments = new ArrayList<>();
            for (JsonNode c : n.path("comments").path("nodes")) {
                comments.add(new Comment(
                        c.path("author").path("login").asText("ghost"),
                        c.path("body").asText(""),
                        c.path("createdAt").asText(""),
                        c.path("url").asText("")));
            }
            boolean outdated = n.path("isOutdated").asBoolean(false);
            int line = n.path("line").asInt(0);
            int start = n.path("startLine").asInt(0);
            if (line <= 0) {
                outdated = true; // GitHub no longer places it on the diff: kept, unmarked
            }
            out.add(new Thread(
                    n.path("id").asText(""),
                    n.path("path").asText(""),
                    "LEFT".equals(n.path("diffSide").asText("RIGHT")),
                    start > 0 ? start : line,
                    line,
                    n.path("isResolved").asBoolean(false),
                    outdated,
                    comments));
        }
        return out;
    }

    /** Whether a mutation's answer reports an error ({@code errors} in the body). */
    public static boolean failed(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return root.has("errors") && root.get("errors").size() > 0;
        } catch (java.io.IOException e) {
            return true;
        }
    }
}
