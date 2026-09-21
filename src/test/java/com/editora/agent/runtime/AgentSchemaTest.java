package com.editora.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentSchemaTest {
    @Test
    void mcpSchemasSupportNullableReferencesAndAlternativesWithoutRelaxingConstraints() throws Exception {
        var json = new ObjectMapper();
        var schema = json.readTree("""
                {"type":"object","additionalProperties":false,"required":["value"],
                 "$defs":{"choice":{"anyOf":[{"type":"null"},{"type":"integer","minimum":1}]}},
                 "properties":{"value":{"$ref":"#/$defs/choice"}}}
                """);
        AgentTools.validate(json.readTree("{\"value\":null}"), schema);
        AgentTools.validate(json.readTree("{\"value\":2}"), schema);
        assertThrows(IllegalArgumentException.class, () -> AgentTools.validate(json.readTree("{\"value\":0}"), schema));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentTools.validate(json.readTree("{\"value\":2,\"escape\":true}"), schema));
        assertThrows(IllegalArgumentException.class, () -> AgentTools.validate(json.readTree("{}"), schema));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentTools.validate(
                        json.readTree("\"bad\""), json.readTree("{\"type\":\"string\",\"pattern\":\"(a+)+\"}")));
    }
}
