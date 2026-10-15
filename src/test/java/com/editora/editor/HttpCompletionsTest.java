package com.editora.editor;

import java.util.List;
import java.util.Map;

import com.editora.completion.Completion;
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
}
