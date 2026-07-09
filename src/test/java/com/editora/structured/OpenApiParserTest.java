package com.editora.structured;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for OpenAPI/Swagger detection + shallow parsing (driven through {@link StructuredParser}). */
class OpenApiParserTest {

    private static final String SPEC = """
            {
              "openapi": "3.0.0",
              "info": { "title": "Pet API", "version": "1.2.0", "description": "Manage pets" },
              "servers": [ { "url": "https://api.example.com/v1", "description": "prod" } ],
              "paths": {
                "/pets": {
                  "get": {
                    "summary": "List pets",
                    "parameters": [ { "name": "limit", "in": "query", "required": false, "schema": { "type": "integer" } } ],
                    "responses": { "200": { "description": "a list" } }
                  },
                  "post": { "summary": "Create", "responses": { "201": { "description": "created" } } }
                }
              },
              "components": {
                "schemas": {
                  "Pet": {
                    "type": "object",
                    "required": [ "id" ],
                    "properties": { "id": { "type": "integer" }, "tag": { "type": "string" }, "owner": { "$ref": "#/components/schemas/Owner" } }
                  }
                }
              }
            }
            """;

    @Test
    void detectsAndParsesOpenApi3() {
        StructuredParser.Parsed p = StructuredParser.parse(SPEC, StructuredParser.Format.JSON);
        assertTrue(p.ok());
        assertTrue(p.isOpenApi());
        OpenApiModel m = p.openApi();
        assertEquals("Pet API", m.title());
        assertEquals("1.2.0", m.version());
        assertEquals(1, m.servers().size());
        assertEquals("https://api.example.com/v1", m.servers().get(0).url());

        assertEquals(1, m.paths().size());
        OpenApiModel.PathItem pets = m.paths().get(0);
        assertEquals("/pets", pets.path());
        assertEquals(2, pets.operations().size());
        OpenApiModel.Operation get = pets.operations().get(0);
        assertEquals("GET", get.method());
        assertEquals("List pets", get.summary());
        assertEquals(1, get.parameters().size());
        assertEquals("limit", get.parameters().get(0).name());
        assertEquals("query", get.parameters().get(0).in());
        assertEquals("integer", get.parameters().get(0).type());
        assertEquals("200", get.responses().get(0).code());

        assertEquals(1, m.schemas().size());
        OpenApiModel.Schema pet = m.schemas().get(0);
        assertEquals("Pet", pet.name());
        assertEquals(3, pet.properties().size());
        OpenApiModel.Property id = pet.properties().get(0);
        assertEquals("id", id.name());
        assertEquals("integer", id.type());
        assertTrue(id.required());
        assertFalse(pet.properties().get(1).required());
        assertEquals("Owner", pet.properties().get(2).type()); // $ref shown by simple name
    }

    @Test
    void plainJsonIsNotOpenApi() {
        StructuredParser.Parsed p = StructuredParser.parse("{\"paths\":{}}", StructuredParser.Format.JSON);
        assertTrue(p.ok());
        assertFalse(p.isOpenApi()); // no openapi/swagger discriminator
    }

    @Test
    void refNameExtractsLastSegment() {
        assertEquals("Pet", OpenApiParser.refName("#/components/schemas/Pet"));
        assertEquals("Order", OpenApiParser.refName("#/definitions/Order"));
        assertEquals("Bare", OpenApiParser.refName("Bare"));
        assertEquals("", OpenApiParser.refName(null));
    }
}
