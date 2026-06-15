package com.editora.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the JSONPath-subset evaluator used in request chaining. */
class JsonPathTest {

    private static final String JSON = """
            {
              "token": "abc",
              "user": { "id": 7, "name": "Ada" },
              "items": [ { "id": 10 }, { "id": 20 }, { "id": 30 } ],
              "a.b": "dotted"
            }
            """;

    @Test
    void resolvesDottedFields() {
        assertEquals("abc", JsonPath.eval(JSON, "$.token"));
        assertEquals("Ada", JsonPath.eval(JSON, "$.user.name"));
        assertEquals("7", JsonPath.eval(JSON, "$.user.id"));
    }

    @Test
    void resolvesArrayIndices() {
        assertEquals("20", JsonPath.eval(JSON, "$.items[1].id"));
        assertEquals("10", JsonPath.eval(JSON, "$.items[0].id"));
    }

    @Test
    void resolvesBracketedFieldWithDot() {
        assertEquals("dotted", JsonPath.eval(JSON, "$[\"a.b\"]"));
    }

    @Test
    void worksWithoutLeadingDollar() {
        assertEquals("abc", JsonPath.eval(JSON, "token"));
    }

    @Test
    void starOrEmptyReturnsWholeBody() {
        assertEquals(JSON.replaceAll("\\s+", ""), JsonPath.eval(JSON, "*").replaceAll("\\s+", ""));
        assertEquals(JSON.replaceAll("\\s+", ""), JsonPath.eval(JSON, "").replaceAll("\\s+", ""));
    }

    @Test
    void missesYieldEmptyString() {
        assertEquals("", JsonPath.eval(JSON, "$.nope"));
        assertEquals("", JsonPath.eval(JSON, "$.items[9].id"));
        assertEquals("", JsonPath.eval("not json", "$.x"));
        assertEquals("", JsonPath.eval("", "$.x"));
    }
}
