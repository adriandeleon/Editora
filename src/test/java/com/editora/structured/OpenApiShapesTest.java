package com.editora.structured;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes of API description {@link OpenApiParser} must read besides a tidy OpenAPI 3 document: a
 * Swagger 2 one (host / basePath / schemes, {@code definitions}, inline parameter types) and documents
 * with parts that are missing or of the wrong kind — which must yield an empty section, not an exception.
 */
class OpenApiShapesTest {

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void onlyAnObjectWithATextualVersionFieldIsAnApiDescription() throws Exception {
        assertTrue(OpenApiParser.detect(json("{\"openapi\": \"3.1.0\"}")));
        assertTrue(OpenApiParser.detect(json("{\"swagger\": \"2.0\"}")));
        assertFalse(OpenApiParser.detect(json("{\"openapi\": 3}")), "a number is some other document's field");
        assertFalse(OpenApiParser.detect(json("{\"swagger\": {\"version\": 2}}")));
        assertFalse(OpenApiParser.detect(json("{\"name\": \"x\"}")));
        assertFalse(OpenApiParser.detect(json("[\"openapi\"]")));
        assertFalse(OpenApiParser.detect(json("\"openapi\"")));
        assertFalse(OpenApiParser.detect(null));
    }

    @Test
    void aSwagger2DocumentIsReadFromItsOwnFields() throws Exception {
        OpenApiModel model = OpenApiParser.parse(json("""
                {
                  "swagger": "2.0",
                  "info": { "title": "Legacy", "version": "0.9" },
                  "host": "api.example.com",
                  "basePath": "/v2",
                  "schemes": ["https", "http"],
                  "paths": {
                    "/items/{id}": {
                      "parameters": [ { "name": "shared", "in": "header" } ],
                      "GET": {
                        "deprecated": true,
                        "parameters": [
                          { "name": "id", "in": "path", "required": true, "type": "integer", "description": "the id" },
                          { "name": "tags", "in": "query", "type": "array", "items": { "type": "string" } },
                          { "name": "raw", "in": "query", "type": "array" },
                          { "name": "body", "in": "body", "schema": { "$ref": "#/definitions/Item" } },
                          { "$ref": "#/parameters/PageSize" },
                          { "in": "query" }
                        ],
                        "responses": { "200": { "description": "ok" }, "default": {} }
                      },
                      "x-internal": { "summary": "not an operation" },
                      "post": "not an object"
                    }
                  },
                  "definitions": {
                    "Item": {
                      "type": "object",
                      "required": ["id"],
                      "properties": {
                        "id": { "type": "integer" },
                        "parts": { "type": "array", "items": { "$ref": "#/definitions/Part" } },
                        "odd": "not a schema"
                      }
                    },
                    "Part": { "type": "string" }
                  }
                }
                """));

        assertEquals("Legacy", model.title());
        assertNull(model.description());
        assertEquals(
                List.of(
                        new OpenApiModel.Server("https://api.example.com/v2", null),
                        new OpenApiModel.Server("http://api.example.com/v2", null)),
                model.servers());

        assertEquals(1, model.paths().size());
        OpenApiModel.PathItem path = model.paths().getFirst();
        assertEquals("/items/{id}", path.path());
        assertEquals(1, path.operations().size(), "only HTTP verbs with an object are operations");
        OpenApiModel.Operation get = path.operations().getFirst();
        assertEquals("GET", get.method());
        assertTrue(get.deprecated());
        assertNull(get.summary());
        assertEquals(
                List.of(
                        new OpenApiModel.Param("id", "path", true, "integer", "the id"),
                        new OpenApiModel.Param("tags", "query", false, "string[]", null),
                        new OpenApiModel.Param("raw", "query", false, "array", null),
                        new OpenApiModel.Param("body", "body", false, "Item", null),
                        new OpenApiModel.Param("PageSize", null, false, "PageSize", null),
                        new OpenApiModel.Param("?", "query", false, "", null)),
                get.parameters());
        assertEquals(
                List.of(new OpenApiModel.Response("200", "ok"), new OpenApiModel.Response("default", null)),
                get.responses());

        assertEquals(2, model.schemas().size());
        OpenApiModel.Schema item = model.schemas().getFirst();
        assertEquals("object", item.type());
        assertEquals(
                List.of(
                        new OpenApiModel.Property("id", "integer", true),
                        new OpenApiModel.Property("parts", "Part[]", false),
                        new OpenApiModel.Property("odd", "", false)),
                item.properties());
        assertEquals(
                new OpenApiModel.Schema("Part", "string", List.of()),
                model.schemas().get(1));
    }

    @Test
    void aSwagger2HostWithoutSchemesIsListedAsItIsAndABasePathAloneToo() throws Exception {
        assertEquals(
                List.of(new OpenApiModel.Server("api.example.com", null)),
                OpenApiParser.parse(json("{\"swagger\":\"2.0\",\"host\":\"api.example.com\",\"schemes\":[]}"))
                        .servers());
        assertEquals(
                List.of(new OpenApiModel.Server("api.example.com/v1", null)),
                OpenApiParser.parse(
                                json(
                                        "{\"swagger\":\"2.0\",\"host\":\"api.example.com\",\"basePath\":\"/v1\",\"schemes\":\"https\"}"))
                        .servers());
        assertEquals(
                List.of(),
                OpenApiParser.parse(json("{\"swagger\":\"2.0\",\"host\":{}}")).servers(),
                "a host that is not text names no server");
        assertEquals(
                List.of(),
                OpenApiParser.parse(json("{\"swagger\":\"2.0\",\"basePath\":\"/v1\"}"))
                        .servers(),
                "no host at all");
    }

    @Test
    void sectionsOfTheWrongKindAreEmptyRatherThanAnError() throws Exception {
        OpenApiModel model = OpenApiParser.parse(json("""
                {
                  "openapi": "3.0.0",
                  "info": "not an object",
                  "servers": [ { "description": "no url" }, { "url": "https://ok.example" }, "text" ],
                  "paths": {
                    "/a": "not an object",
                    "/b": { "get": { "parameters": "none", "responses": [] } }
                  },
                  "components": { "schemas": [] },
                  "definitions": { "Fallback": { "required": "id", "properties": [] } }
                }
                """));
        assertNull(model.title());
        assertNull(model.version());
        assertEquals(List.of(new OpenApiModel.Server("https://ok.example", null)), model.servers());
        assertEquals(1, model.paths().size(), "a path that is not an object is skipped");
        OpenApiModel.Operation get = model.paths().getFirst().operations().getFirst();
        assertEquals(List.of(), get.parameters());
        assertEquals(List.of(), get.responses());
        assertFalse(get.deprecated());
        assertEquals(
                List.of(new OpenApiModel.Schema("Fallback", "", List.of())),
                model.schemas(),
                "with no usable components.schemas, definitions are read");

        OpenApiModel bare = OpenApiParser.parse(json("{\"openapi\": \"3.0.0\", \"paths\": [], \"definitions\": 5}"));
        assertEquals(List.of(), bare.paths());
        assertEquals(List.of(), bare.schemas());
        assertEquals(List.of(), bare.servers());
    }

    @Test
    void aReferenceIsShownByItsLastSegment() {
        assertEquals("Pet", OpenApiParser.refName("#/components/schemas/Pet"));
        assertEquals("Pet", OpenApiParser.refName("Pet"));
        assertEquals("", OpenApiParser.refName("#/components/schemas/"));
        assertEquals("", OpenApiParser.refName(" "));
        assertEquals("", OpenApiParser.refName(null));
    }
}
