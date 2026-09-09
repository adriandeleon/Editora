package com.editora.lsp;
import java.util.Map;
import org.eclipse.lsp4j.jsonrpc.json.*;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class JavaLspEvaluationRawResponseTest {
    @Test void customResponseMustSurviveTheProductionMethodRegistry() {
        String method = "java/checkToStringStatus";
        String wire = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"fields\":[{\"name\":\"count\"}]}}";
        var production = new MessageJsonHandler(ServiceEndpoints.getSupportedMethods(LanguageServer.class));
        production.setMethodProvider(id -> method);
        var actual = ((ResponseMessage) production.parseMessage(wire)).getResult();
        var control = new MessageJsonHandler(Map.of(method, JsonRpcMethod.request(method, com.google.gson.JsonElement.class, Object.class)));
        control.setMethodProvider(id -> method);
        var expected = ((ResponseMessage) control.parseMessage(wire)).getResult();
        System.out.println("EVALUATION raw-response production=" + actual + " registered-control=" + expected);
        assertNotNull(expected, "registered method preserves payload");
        assertNotNull(actual, "nonempty raw result must reach the extension handler");
    }
}
