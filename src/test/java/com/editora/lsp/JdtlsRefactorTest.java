package com.editora.lsp;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdtlsRefactor} — the client-driven jdtls refactorings.
 *
 * <p>The fixtures are answers captured from jdtls 1.61 by {@code JdtlsRefactorProbeTest}, shortened.
 */
class JdtlsRefactorTest {

    private static final String PARAMS = """
            {"textDocument":{"uri":"file:///p/src/main/java/demo/App.java"},
             "range":{"start":{"line":9,"character":19},"end":{"line":9,"character":19}},
             "context":{"diagnostics":[]}}
            """;

    private static final String PACKAGES = """
            {"destinations":[
              {"displayName":"(default package)","uri":"file:///p/src/main/java","path":"/p/src/main/java",
               "project":"p","isDefaultPackage":true,"isParentOfSelectedFile":false},
              {"displayName":"demo","uri":"file:///p/src/main/java/demo","path":"/p/src/main/java/demo",
               "project":"p","isDefaultPackage":false,"isParentOfSelectedFile":true},
              {"displayName":"demo.other","uri":"file:///p/src/main/java/demo/other",
               "path":"/p/src/main/java/demo/other","project":"p","isDefaultPackage":false,
               "isParentOfSelectedFile":false}]}
            """;

    private static final String SIGNATURE = """
            {"methodIdentifier":"=p/src<demo{App.java[App~greet~QHelper;~I","modifier":"public",
             "returnType":"String","methodName":"greet",
             "parameters":[{"type":"Helper","name":"helper","defaultValue":"","originalIndex":0},
                           {"type":"int","name":"n","defaultValue":"","originalIndex":1}],
             "exceptions":[{"type":"IOException","typeHandleIdentifier":"=p/io<java.io(IOException.class"}]}
            """;

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    private static List<JsonElement> args(String... parts) {
        return java.util.Arrays.stream(parts).map(JdtlsRefactorTest::json).toList();
    }

    @Test
    void parsesTheRefactoringCommandAndItsInfo() {
        var request = JdtlsRefactor.parse(
                JdtlsRefactor.COMMAND,
                args(
                        "\"moveType\"",
                        PARAMS,
                        "{\"supportedDestinationKinds\":[\"newFile\",\"class\"],\"displayName\":\"Inner\","
                                + "\"enclosingTypeName\":\"demo.App\",\"projectName\":\"p\"}"));

        assertEquals("moveType", request.name());
        assertEquals("file:///p/src/main/java/demo/App.java", request.documentUri());
        assertEquals("demo.App", request.info("enclosingTypeName"));
        assertTrue(request.supportsDestination("newFile"));
        assertTrue(request.supportsDestination("class"));
        assertFalse(request.supportsDestination("package"));
    }

    @Test
    void anythingElseIsNotARefactoringCommand() {
        assertNull(JdtlsRefactor.parse("java.action.generateToStringPrompt", args(PARAMS)));
        assertNull(JdtlsRefactor.parse(JdtlsRefactor.COMMAND, args("\"moveFile\"")), "no params");
        assertNull(JdtlsRefactor.parse(JdtlsRefactor.COMMAND, args(PARAMS, PARAMS)), "no name");
        assertNull(JdtlsRefactor.parse(JdtlsRefactor.COMMAND, null));
        var noInfo = JdtlsRefactor.parse(JdtlsRefactor.COMMAND, args("\"changeSignature\"", PARAMS));
        assertNull(noInfo.info("projectName"));
        assertFalse(noInfo.supportsDestination("newFile"));
    }

    @Test
    void aFileIsNotOfferedThePackageItIsAlreadyIn() {
        var packages = JdtlsRefactor.packages(json(PACKAGES), false);

        assertEquals(
                List.of("(default package)", "demo.other"),
                packages.stream().map(JdtlsRefactor.Destination::label).toList());
        assertEquals("/p/src/main/java/demo/other", packages.get(1).detail());
        assertTrue(packages.get(1).raw().getAsJsonObject().has("uri"), "the node goes back verbatim");
    }

    @Test
    void anExtractedInterfaceDefaultsToTheClassesOwnPackage() {
        var status = json("{\"members\":[{\"name\":\"greet\",\"typeName\":\"String\","
                + "\"parameters\":[\"Helper\",\"int\"],\"handleIdentifier\":\"h1\"},"
                + "{\"name\":\"run\",\"typeName\":\"void\",\"parameters\":[],\"handleIdentifier\":\"h2\"}],"
                + "\"subTypeName\":\"App\",\"destinationResponse\":" + PACKAGES + "}");

        var members = JdtlsRefactor.interfaceMembers(status);
        var packages = JdtlsRefactor.interfacePackages(status);

        assertEquals(
                List.of("greet(Helper, int) : String", "run() : void"),
                members.stream().map(JdtlsGenerate.Candidate::label).toList());
        assertTrue(members.stream().allMatch(JdtlsGenerate.Candidate::preselected));
        assertEquals("App", JdtlsRefactor.subTypeName(status));
        assertEquals("demo", packages.get(0).label());
        assertEquals(3, packages.size());

        JsonArray arguments = JdtlsRefactor.extractInterfaceArguments(members.subList(1, 2), "Runner", packages.get(0));
        assertEquals("[\"h2\"]", arguments.get(0).toString());
        assertEquals("Runner", arguments.get(1).getAsString());
        assertEquals(
                "demo", arguments.get(2).getAsJsonObject().get("displayName").getAsString());
    }

    @Test
    void instanceTargetsAndTypesAreLabelled() {
        var targets = JdtlsRefactor.instanceTargets(json("{\"destinations\":[{\"bindingKey\":\"k\","
                + "\"name\":\"helper\",\"type\":\"Helper\",\"isField\":false,\"isSelected\":false}]}"));
        assertEquals("helper : Helper", targets.get(0).label());

        var types = JdtlsRefactor.types(
                json("[{\"name\":\"Helper\",\"kind\":5,\"containerName\":\"demo.other\"},"
                        + "{\"name\":\"App\",\"kind\":5,\"containerName\":\"demo\"},"
                        + "{\"name\":\"Inner\",\"kind\":5,\"containerName\":\"demo.App\"}]"),
                "demo.App");
        assertEquals(
                List.of("Helper", "Inner"),
                types.stream().map(JdtlsRefactor.Destination::label).toList());
        assertEquals("demo.other", types.get(0).detail());
    }

    @Test
    void unexpectedAnswersDegradeToNothing() {
        assertTrue(JdtlsRefactor.packages(null, true).isEmpty());
        assertTrue(JdtlsRefactor.packages(json("[]"), true).isEmpty());
        assertTrue(JdtlsRefactor.instanceTargets(json("{\"destinations\":7}")).isEmpty());
        assertTrue(JdtlsRefactor.types(json("{}"), null).isEmpty());
        assertTrue(JdtlsRefactor.interfaceMembers(json("{\"members\":[{\"name\":\"x\"}]}"))
                .isEmpty());
        assertNull(JdtlsRefactor.editOf(json("[]")));
        assertEquals("", JdtlsRefactor.signatureText(null));
        assertNull(JdtlsRefactor.changeSignatureArguments(null, "void f()"));
    }

    @Test
    void moveParamsCarryTheDestinationOnlyWhenThereIsOne() {
        var ask = JdtlsRefactor.moveParams("moveResource", "file:///a/App.java", null, null);
        assertEquals("[\"file:///a/App.java\"]", ask.get("sourceUris").toString());
        assertFalse(ask.has("params"));
        assertFalse(ask.has("destination"));

        var move = JdtlsRefactor.moveParams(
                "moveInstanceMethod", "file:///a/App.java", json(PARAMS), json("{\"name\":\"h\"}"));
        assertEquals("moveInstanceMethod", move.get("moveKind").getAsString());
        assertTrue(move.has("params"));
        assertEquals("h", move.getAsJsonObject("destination").get("name").getAsString());
        assertTrue(move.get("updateReferences").getAsBoolean());
    }

    @Test
    void theEditIsFoundWrappedOrBare() {
        var wrapped = json("{\"edit\":{\"changes\":{}},\"command\":{\"command\":\"java.action.rename\"}}");
        assertEquals("{\"changes\":{}}", JdtlsRefactor.editOf(wrapped).toString());
        assertEquals(
                "{\"documentChanges\":[]}",
                JdtlsRefactor.editOf(json("{\"documentChanges\":[]}")).toString());

        var refused = json("{\"errorMessage\":\"Cannot move the method.\"}");
        assertNull(JdtlsRefactor.editOf(refused));
        assertEquals("Cannot move the method.", JdtlsRefactor.errorMessage(refused));
        assertNull(JdtlsRefactor.errorMessage(wrapped));
    }

    @Test
    void theSignatureIsOneEditableLine() {
        assertEquals(
                "public String greet(Helper helper, int n) throws IOException",
                JdtlsRefactor.signatureText(json(SIGNATURE)));
    }

    @Test
    void anUneditedSignatureKeepsEveryParameterAndException() {
        JsonElement info = json(SIGNATURE);
        JsonArray a = JdtlsRefactor.changeSignatureArguments(info, JdtlsRefactor.signatureText(info));

        assertEquals("=p/src<demo{App.java[App~greet~QHelper;~I", a.get(0).getAsString());
        assertFalse(a.get(1).getAsBoolean());
        assertEquals("greet", a.get(2).getAsString());
        assertEquals("public", a.get(3).getAsString());
        assertEquals("String", a.get(4).getAsString());
        assertEquals(List.of(0, 1), originalIndexes(a));
        assertEquals(
                "=p/io<java.io(IOException.class",
                a.get(6)
                        .getAsJsonArray()
                        .get(0)
                        .getAsJsonObject()
                        .get("typeHandleIdentifier")
                        .getAsString());
        assertFalse(a.get(7).getAsBoolean());
    }

    @Test
    void reorderedRenamedAndNewParametersAreToldApart() {
        JsonArray a = JdtlsRefactor.changeSignatureArguments(
                json(SIGNATURE),
                "protected CharSequence hello(int times, Helper helper, Map<String, Integer> extra = Map.of(\"a\", 1))");

        assertEquals("hello", a.get(2).getAsString());
        assertEquals("protected", a.get(3).getAsString());
        assertEquals("CharSequence", a.get(4).getAsString());
        assertEquals(List.of(1, 0, -1), originalIndexes(a), "int times is the renamed n; extra is new");
        var extra = a.get(5).getAsJsonArray().get(2).getAsJsonObject();
        assertEquals("Map<String, Integer>", extra.get("type").getAsString());
        assertEquals("Map.of(\"a\", 1)", extra.get("defaultValue").getAsString());
        assertEquals(0, a.get(6).getAsJsonArray().size(), "the throws clause was removed");
    }

    @Test
    void aParameterWithAValueIsNewEvenWhenATypeMatches() {
        JsonArray a =
                JdtlsRefactor.changeSignatureArguments(json(SIGNATURE), "String greet(Helper helper, int count = 5)");

        assertEquals("", a.get(3).getAsString(), "no visibility keyword: package-private");
        assertEquals(List.of(0, -1), originalIndexes(a));
        assertEquals(
                "5",
                a.get(5)
                        .getAsJsonArray()
                        .get(1)
                        .getAsJsonObject()
                        .get("defaultValue")
                        .getAsString());
    }

    @Test
    void aNewParameterWithoutAValueGetsNull() {
        JsonArray a = JdtlsRefactor.changeSignatureArguments(
                json(SIGNATURE),
                "public String greet(Helper helper, int n, String label) throws IOException, java.sql.SQLException");

        assertEquals(List.of(0, 1, -1), originalIndexes(a));
        assertEquals(
                "null",
                a.get(5)
                        .getAsJsonArray()
                        .get(2)
                        .getAsJsonObject()
                        .get("defaultValue")
                        .getAsString());
        var added = a.get(6).getAsJsonArray().get(1).getAsJsonObject();
        assertEquals("java.sql.SQLException", added.get("type").getAsString());
        assertFalse(added.has("typeHandleIdentifier"));
    }

    @Test
    void textThatIsNotASignatureIsRefused() {
        JsonElement info = json(SIGNATURE);
        assertNull(JdtlsRefactor.changeSignatureArguments(info, "greet"));
        assertNull(JdtlsRefactor.changeSignatureArguments(info, "public String (int n)"));
        assertNull(JdtlsRefactor.changeSignatureArguments(info, "public String greet(int)"));
        assertNull(JdtlsRefactor.changeSignatureArguments(info, "public String greet(int n) oops"));
        assertNotNull(JdtlsRefactor.changeSignatureArguments(info, "  public String greet()  "));
    }

    @Test
    void typeNamesAreJavaIdentifiers() {
        assertTrue(JdtlsRefactor.isTypeName("Greeter"));
        assertTrue(JdtlsRefactor.isTypeName("_G1"));
        assertFalse(JdtlsRefactor.isTypeName("1G"));
        assertFalse(JdtlsRefactor.isTypeName("My Type"));
        assertFalse(JdtlsRefactor.isTypeName(""));
        assertFalse(JdtlsRefactor.isTypeName(null));
    }

    private static List<Integer> originalIndexes(JsonArray arguments) {
        return arguments.get(5).getAsJsonArray().asList().stream()
                .map(p -> p.getAsJsonObject().get("originalIndex").getAsInt())
                .toList();
    }
}
