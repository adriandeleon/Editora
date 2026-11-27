package com.editora.github;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Line comments on a pull request: drafts sent with the review, and the GraphQL calls on its threads. Pure. */
class PrInlineCallsTest {

    private static final String URL = "https://github.com/octo/app/pull/7";
    private final ObjectMapper m = new ObjectMapper();

    @Test
    void draftsGoWithThePinnedReviewAsItsLineComments() throws Exception {
        List<PrLineDraft> drafts = List.of(
                PrLineDraft.create("src/A.java", false, 12, 12, "Off by one."),
                PrLineDraft.create("src/A.java", true, 3, 5, "```suggestion\nx\n```"),
                PrLineDraft.create("src/B.java", false, 1, 1, "  "));
        GhInvocation call = PrReviewArgs.invocation(
                7, PrReviewArgs.ReviewAction.REQUEST_CHANGES, "See inline.", "abc123", URL, drafts);
        assertEquals(
                List.of(
                        "api",
                        "--hostname",
                        "github.com",
                        "--method",
                        "POST",
                        "repos/octo/app/pulls/7/reviews",
                        "--input",
                        "-"),
                call.args());
        JsonNode json = m.readTree(call.stdin());
        assertEquals("abc123", json.get("commit_id").asText());
        assertEquals("REQUEST_CHANGES", json.get("event").asText());
        JsonNode comments = json.get("comments");
        assertEquals(2, comments.size(), "a blank draft is left out");
        assertEquals("RIGHT", comments.get(0).get("side").asText());
        assertEquals(12, comments.get(0).get("line").asInt());
        assertFalse(comments.get(0).has("start_line"), "one line: no range");
        assertEquals("LEFT", comments.get(1).get("side").asText());
        assertEquals(3, comments.get(1).get("start_line").asInt());
        assertEquals("LEFT", comments.get(1).get("start_side").asText());
        assertEquals(5, comments.get(1).get("line").asInt());
        assertFalse(
                m.readTree(PrReviewArgs.invocation(7, PrReviewArgs.ReviewAction.COMMENT, "x", "abc123", URL)
                                .stdin())
                        .has("comments"),
                "no drafts: no comments");
    }

    @Test
    void draftsNeedThePinnedCall() {
        assertTrue(PrReviewArgs.canCarryDrafts("abc", URL, 7));
        assertFalse(PrReviewArgs.canCarryDrafts("", URL, 7), "no head commit");
        assertFalse(PrReviewArgs.canCarryDrafts("abc", "", 7), "no address");
        assertFalse(PrReviewArgs.canCarryDrafts("abc", URL, 8), "another pull request's address");
    }

    @Test
    void theThreadCallsNameTheRepositoryAndKeepTheReplyOffTheCommandLine() {
        GhInvocation read = PrThreads.read(URL, 7);
        assertTrue(
                read.args()
                        .containsAll(
                                List.of("graphql", "--hostname", "github.com", "owner=octo", "name=app", "number=7")),
                read.args().toString());
        assertTrue(read.args().stream().anyMatch(a -> a.startsWith("query=") && a.contains("reviewThreads")));
        assertNull(read.stdin());
        GhInvocation reply = PrThreads.reply(URL, 7, "T_1", "Fixed, thanks.");
        assertTrue(reply.args().contains("id=T_1"));
        assertTrue(reply.args().contains("body=@-"), "the body is read from standard input");
        assertEquals("Fixed, thanks.", reply.stdin());
        assertFalse(String.join(" ", reply.args()).contains("Fixed, thanks."));
        assertTrue(
                String.join(" ", PrThreads.resolve(URL, 7, "T_1", true).args()).contains("resolveReviewThread"));
        assertTrue(
                String.join(" ", PrThreads.resolve(URL, 7, "T_1", false).args()).contains("unresolveReviewThread"));
        assertNull(PrThreads.read("", 7), "no address: no call");
        assertNull(PrThreads.reply("not a url", 7, "T", "x"));
        assertNull(PrThreads.resolve(URL, 8, "T", true));
    }

    @Test
    void theThreadsAnswerIsReadWithSidesLinesAndOutdatedOnes() {
        String json = """
                {"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[
                  {"id":"T_1","isResolved":false,"isOutdated":false,"path":"src/A.java","line":12,"startLine":10,
                   "diffSide":"RIGHT","comments":{"nodes":[
                     {"author":{"login":"ada"},"body":"Why?","createdAt":"2026-11-27T01:00:00Z","url":"https://x/1"},
                     {"author":null,"body":"Because.","createdAt":"","url":""}]}},
                  {"id":"T_2","isResolved":true,"isOutdated":false,"path":"src/A.java","line":4,"startLine":null,
                   "diffSide":"LEFT","comments":{"nodes":[]}},
                  {"id":"T_3","isResolved":false,"isOutdated":false,"path":"src/B.java","line":null,
                   "diffSide":"RIGHT","comments":{"nodes":[]}}]}}}}}
                """;
        List<PrThreads.Thread> threads = PrThreads.parse(json);
        assertEquals(3, threads.size());
        PrThreads.Thread first = threads.get(0);
        assertEquals(10, first.startLine());
        assertEquals(12, first.line());
        assertFalse(first.left());
        assertEquals("ada", first.comments().get(0).author());
        assertEquals("ghost", first.comments().get(1).author(), "a deleted account");
        assertTrue(first.on("src/A.java", false, 12));
        assertFalse(first.on("src/A.java", true, 12), "the other side");
        PrThreads.Thread second = threads.get(1);
        assertTrue(second.left() && second.resolved());
        assertEquals(4, second.startLine(), "no start line: the line itself");
        PrThreads.Thread third = threads.get(2);
        assertTrue(third.outdated(), "GitHub no longer places it: outdated");
        assertFalse(third.on("src/B.java", false, 0));
        assertEquals(List.of(), PrThreads.parse("not json"));
        assertEquals(List.of(), PrThreads.parse(null));
        assertTrue(PrThreads.failed("{\"errors\":[{\"message\":\"nope\"}]}"));
        assertFalse(PrThreads.failed("{\"data\":{}}"));
        assertTrue(PrThreads.failed(""));
    }

    @Test
    void aDraftKnowsItsLineAndKeepsItsRangeInOrder() {
        PrLineDraft d = PrLineDraft.create("a.txt", true, 9, 4, "x");
        assertEquals(4, d.startLine(), "start clamped to the line");
        assertTrue(new PrLineDraft("i", "a.txt", false, 2, 6, "x").on("a.txt", false, 6));
        assertEquals("y", d.withBody("y").body());
        assertEquals(d.id(), d.withBody("y").id());
    }
}
