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

    /** Go to Super Method's answer survives LSP4J: undeclared, it arrived as null (seventh review BR1). */
    @Test
    void findLinksAnswerSurvivesTheProductionMethodRegistry() {
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":[{\"displayName\":\"demo.Shape.area\","
                + "\"kind\":\"method\",\"uri\":\"file:///p/Shape.java\",\"range\":{\"start\":{\"line\":3,"
                + "\"character\":27},\"end\":{\"line\":3,\"character\":31}}}]}";
        var handler = new MessageJsonHandler(ServiceEndpoints.getSupportedMethods(JdtLanguageServer.class));
        handler.setMethodProvider(id -> "java/findLinks");

        Object result = ((ResponseMessage) handler.parseMessage(wire)).getResult();

        assertNotNull(result, "the method must be declared on JdtLanguageServer");
        assertEquals(1, ((com.google.gson.JsonElement) result).getAsJsonArray().size());
    }

    /**
     * Every {@code java/…} request Editora sends is declared, or its answer is dropped to null (seventh review BR1).
     * Notifications, which have no answer, are listed apart.
     */
    @Test
    void everyJavaRequestEditoraSendsIsDeclared() throws Exception {
        java.util.Set<String> notifications = java.util.Set.of("java/projectConfigurationUpdate");
        var declared =
                ServiceEndpoints.getSupportedMethods(JdtLanguageServer.class).keySet();
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile("\"(java/[A-Za-z]+)\"");
        java.util.List<String> missing = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var m = literal.matcher(java.nio.file.Files.readString(file));
                while (m.find()) {
                    if (!declared.contains(m.group(1)) && !notifications.contains(m.group(1))) {
                        missing.add(m.group(1) + " in " + file.getFileName());
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "declare these on JdtLanguageServer: " + missing);
    }
}
