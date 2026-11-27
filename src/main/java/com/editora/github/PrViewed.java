package com.editora.github;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Which files of a pull request you marked as viewed on GitHub, and the commit your last review was on: the GraphQL
 * call that reads both, the ones that mark a file viewed or not, and the REST compare that lists what changed
 * between your last reviewed commit and the head. GitHub keeps the viewed state per user and clears it on a file
 * that changes, so it is the source of truth for a pull request. Pure: builds {@link GhInvocation}s and reads JSON.
 */
public final class PrViewed {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How many files one page brings (GitHub's page size limit); {@code gh --paginate} reads every page. */
    static final int MAX_FILES = 100;

    private PrViewed() {}

    /**
     * What one read gives: the pull request's node id (for the mutations), each listed file's state
     * ({@code VIEWED}, {@code DISMISSED} — viewed, then changed — or {@code UNVIEWED}), and the commit of your latest
     * review ({@code ""} when you have not reviewed it).
     */
    public record State(String prId, Map<String, String> files, String lastReviewedOid) {
        public static final State NONE = new State("", Map.of(), "");

        /** Whether {@code path} is marked viewed (a file changed since is not). */
        public boolean viewed(String path) {
            return "VIEWED".equals(files.get(path));
        }

        /** How many of {@code paths} are viewed. */
        public int viewedCount(List<String> paths) {
            int n = 0;
            for (String p : paths) {
                if (viewed(p)) {
                    n++;
                }
            }
            return n;
        }

        /** Whether a review of yours is on a commit other than {@code headOid}: there is something new since. */
        public boolean changedSinceReview(@Nullable String headOid) {
            return !lastReviewedOid.isBlank()
                    && headOid != null
                    && !headOid.isBlank()
                    && !lastReviewedOid.equalsIgnoreCase(headOid.strip());
        }
    }

    private static final String QUERY = "query($owner:String!,$name:String!,$number:Int!,$endCursor:String){"
            + "repository(owner:$owner,name:$name){pullRequest(number:$number){id viewerLatestReview{commit{oid}}"
            + " files(first:" + MAX_FILES + ",after:$endCursor){pageInfo{hasNextPage endCursor}"
            + " nodes{path viewerViewedState}}}}}";

    private static final String MARK =
            "mutation($id:ID!,$path:String!){markFileAsViewed(input:{pullRequestId:$id,path:$path}){clientMutationId}}";

    private static final String UNMARK = "mutation($id:ID!,$path:String!){unmarkFileAsViewed(input:"
            + "{pullRequestId:$id,path:$path}){clientMutationId}}";

    /** The call that reads pull request {@code number}'s viewed files; null when {@code prUrl} names no repository. */
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
                        "--paginate", // every page of files: a pull request lists up to 3,000
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

    /** The call that marks ({@code viewed}) or unmarks {@code path} of the pull request with node id {@code prId}. */
    public static @Nullable GhInvocation mark(
            @Nullable String prUrl, int number, String prId, String path, boolean viewed) {
        PrReviewArgs.Target t = PrReviewArgs.target(prUrl, number);
        if (t == null || prId.isBlank()) {
            return null;
        }
        return new GhInvocation(
                List.of(
                        "api",
                        "graphql",
                        "--hostname",
                        t.host(),
                        "-f",
                        "id=" + prId,
                        "-f",
                        "path=" + path,
                        "-f",
                        "query=" + (viewed ? MARK : UNMARK)),
                null);
    }

    /**
     * The call that lists the files changed between {@code fromOid} (your last reviewed commit) and {@code toOid} (the
     * head), with their hunks: the REST compare, whose answer {@link PrFilesParser} reads.
     */
    public static @Nullable GhInvocation compare(@Nullable String prUrl, int number, String fromOid, String toOid) {
        PrReviewArgs.Target t = PrReviewArgs.target(prUrl, number);
        if (t == null || !isOid(fromOid) || !isOid(toOid)) {
            return null;
        }
        return new GhInvocation(
                List.of(
                        "api",
                        "--hostname",
                        t.host(),
                        "repos/" + t.repo() + "/compare/" + fromOid.strip() + "..." + toOid.strip()),
                null);
    }

    /** A commit id as GitHub prints it: hex only, so it can sit in a URL path as it is. */
    static boolean isOid(@Nullable String oid) {
        return oid != null && oid.strip().matches("[0-9a-fA-F]{7,64}");
    }

    /** The state in a {@link #read} answer; {@link State#NONE} when it cannot be read. */
    public static State parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return State.NONE;
        }
        // gh --paginate prints one answer per page, back to back: the first gives the id and the reviewed commit,
        // each gives its files. A page cut short keeps the files read before it.
        String id = null;
        String oid = "";
        Map<String, String> files = new LinkedHashMap<>();
        try (com.fasterxml.jackson.core.JsonParser parser = MAPPER.getFactory().createParser(json)) {
            while (parser.nextToken() != null) {
                JsonNode root = MAPPER.readTree(parser);
                JsonNode pr = (root == null ? MAPPER.missingNode() : root)
                        .path("data")
                        .path("repository")
                        .path("pullRequest");
                if (pr.isMissingNode() || pr.isNull()) {
                    continue;
                }
                if (id == null) {
                    id = pr.path("id").asText("");
                    oid = pr.path("viewerLatestReview")
                            .path("commit")
                            .path("oid")
                            .asText("");
                }
                for (JsonNode f : pr.path("files").path("nodes")) {
                    String path = f.path("path").asText("");
                    if (!path.isEmpty()) {
                        files.put(path, f.path("viewerViewedState").asText("UNVIEWED"));
                    }
                }
            }
        } catch (java.io.IOException e) {
            // keep what was read
        }
        return id == null ? State.NONE : new State(id, Map.copyOf(files), oid);
    }

    /**
     * What a compare answer says about how the two commits relate: {@code ahead} (the head only adds to what you
     * reviewed), {@code diverged} (the branch was rewritten or merged, so base changes can show too), or
     * {@code ""} when it cannot be read.
     */
    public static String compareStatus(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return MAPPER.readTree(json).path("status").asText("");
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** The compare answer's files, ready for {@link PrFilesParser}: its {@code files} array. */
    public static String compareFiles(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return MAPPER.readTree(json).path("files").toString();
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
