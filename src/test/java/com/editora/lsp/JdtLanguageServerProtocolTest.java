package com.editora.lsp;

import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdtLanguageServerProtocolTest {

    @Test
    void customResponseSurvivesTheProductionMethodRegistry() {
        String method = "java/checkToStringStatus";
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"fields\":[{\"name\":\"count\"}]}}";
        var handler = new MessageJsonHandler(ServiceEndpoints.getSupportedMethods(JdtLanguageServer.class));
        handler.setMethodProvider(id -> method);

        Object result = ((ResponseMessage) handler.parseMessage(wire)).getResult();

        assertNotNull(result);
        assertEquals(
                "count",
                ((com.google.gson.JsonElement) result)
                        .getAsJsonObject()
                        .getAsJsonArray("fields")
                        .get(0)
                        .getAsJsonObject()
                        .get("name")
                        .getAsString());
    }

    @Test
    void jdtClientNotificationsAreRegistered() {
        var methods = ServiceEndpoints.getSupportedMethods(LanguageServerSession.class);

        assertTrue(methods.containsKey("language/status"));
        assertTrue(methods.containsKey("language/progressReport"));
        assertTrue(methods.containsKey("language/eventNotification"));
        assertTrue(methods.containsKey("language/actionableNotification"));
    }
}
