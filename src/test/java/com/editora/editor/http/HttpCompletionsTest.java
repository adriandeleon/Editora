package com.editora.editor.http;

import java.util.List;
import java.util.Map;

import com.editora.completion.Completion;
import com.editora.editor.markdown.MarkdownCompletions;
import com.editora.http.HttpVariableIndex;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@code {{} completion offers in a {@code .http} buffer. */
class HttpCompletionsTest {

    private static final HttpVariableIndex.Context ENV =
            new HttpVariableIndex.Context(Map.of("host", "http://h", "token", "t"), null);

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static MarkdownCompletions.Offer offer(String document, int line, String before, String after) {
        return HttpCompletions.offer(document, line, before, after, ENV);
    }

    private static List<String> inserts(MarkdownCompletions.Offer o) {
        return o.items().stream().map(Completion::insert).toList();
    }

    @Test
    void afterTwoBracesItOffersVariablesRequestsAndBuiltIns() {
        String doc = "@id = 1\n# @name login\nPOST http://h/login\n\n###\nGET {{\n";
        MarkdownCompletions.Offer o = offer(doc, 5, "GET {{", "");
        assertEquals("GET {{".length(), o.start());
        List<String> rows = inserts(o);
        assertTrue(
                rows.containsAll(List.of("host}}", "id}}", "token}}", "login.response.", "$uuid}}")), rows.toString());
        assertEquals("host}}", rows.get(0), "variables first");
    }

    @Test
    void whatWasTypedNarrowsTheListAndAClosingPairIsNotRepeated() {
        MarkdownCompletions.Offer o = offer("GET {{ho}}\n", 0, "GET {{ ho", "}}");
        assertEquals(List.of("host"), inserts(o));
        assertEquals("GET {{ ".length(), o.start(), "leading blanks are kept");
        assertEquals(List.of("$random.email}}"), inserts(offer("GET {{\n", 0, "GET {{$random.e", "")));
    }

    @Test
    void afterAResponsePrefixItOffersTheResponseParts() {
        String doc = "# @name login\nPOST http://h\n\n###\nGET {{login.response.\n";
        MarkdownCompletions.Offer o = offer(doc, 4, "GET {{login.response.", "");
        assertEquals(
                List.of(
                        "login.response.body}}",
                        "login.response.body.$.",
                        "login.response.headers.",
                        "login.response.status}}"),
                inserts(o));
        assertNull(offer(doc, 4, "GET {{nobody.response.", ""), "not a request of this file");
    }

    @Test
    void outsideAnOpenPairThereIsNoOffer() {
        assertNull(offer("GET {{host}}/x\n", 0, "GET {{host}}/x", ""));
        assertNull(offer("GET /x\n", 0, "GET /x", ""));
    }

    @Test
    void aNestedEnvironmentOffersOneLevelAtATime() {
        StringBuilder users = new StringBuilder("[");
        for (int i = 0; i < 30; i++) {
            users.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append(",\"address\":{\"city\":\"c\"}}");
        }
        String env = "{\"dev\":{\"host\":\"http://h\",\"users\":" + users + "]}}";
        HttpVariableIndex.Context ctx =
                new HttpVariableIndex.Context(com.editora.http.HttpEnv.merged(env, null, "dev"), null);
        String doc = "# @name login\nPOST {{host}}/login\n\n###\nGET {{host}}/x?u=\n";
        List<String> rows = inserts(HttpCompletions.offer(doc, 4, "GET {{host}}/x?u={{", "", ctx));
        assertTrue(rows.contains("login.response."), rows.toString());
        assertTrue(rows.contains("$uuid}}"), rows.toString());
        assertTrue(rows.contains("users}}") && !rows.contains("users[0]}}"), rows.toString());
        List<String> inArray = inserts(HttpCompletions.offer(doc, 4, "GET {{host}}/x?u={{users[", "", ctx));
        assertTrue(inArray.contains("users[0]}}") && !inArray.contains("users[0].id}}"), inArray.toString());
        List<String> inItem = inserts(HttpCompletions.offer(doc, 4, "GET {{host}}/x?u={{users[3].", "", ctx));
        assertTrue(inItem.contains("users[3].id}}") && inItem.contains("users[3].address}}"), inItem.toString());
    }

    @Test
    void theDotenvKeysComeFromTheContextNotFromAFileRead(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve(".env"), "API_KEY=x\n");
        HttpVariableIndex.Context ctx = new HttpVariableIndex.Context(Map.of(), dir);
        java.nio.file.Files.delete(dir.resolve(".env")); // the popup must not go back to the disk (FX thread)
        List<String> rows = inserts(HttpCompletions.offer("GET {{\n", 0, "GET {{$dot", "", ctx));
        assertEquals(List.of("$dotenv API_KEY}}"), rows);
    }

    @Test
    void anEscapedBraceOffersNothing() {
        assertNull(HttpCompletions.offer("GET \\{{\n", 0, "GET \\{{", "", ENV), "\\{{ is a literal {{");
        assertNull(HttpCompletions.offer("GET \\{{ho\n", 0, "GET \\{{ho", "", ENV));
    }

    @Test
    void aDotenvLinkedOutOfTheFolderListsNoKeys(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        java.nio.file.Path outside = java.nio.file.Files.createDirectories(tmp.resolve("home"));
        java.nio.file.Files.writeString(outside.resolve("credentials"), "AWS_SECRET_ACCESS_KEY=fake\n");
        java.nio.file.Path repo = java.nio.file.Files.createDirectories(tmp.resolve("repo"));
        try {
            java.nio.file.Files.createSymbolicLink(repo.resolve(".env"), outside.resolve("credentials"));
        } catch (UnsupportedOperationException | java.io.IOException noLinks) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
        }
        HttpVariableIndex.Context ctx = new HttpVariableIndex.Context(Map.of(), repo);
        assertEquals(java.util.Set.of(), ctx.dotenvKeys());
        assertNull(HttpCompletions.offer("GET {{\n", 0, "GET {{$dotenv", "", ctx));
    }
}
