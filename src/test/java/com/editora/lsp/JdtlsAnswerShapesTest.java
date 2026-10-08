package com.editora.lsp;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdtlsGenerate} and {@link JdtlsRefactor} read answers to vendor requests that have no specification:
 * the only authority for their shape is whichever jdtls build is installed. These tests feed them the
 * answers a different build could give — a field missing, a string where an object was, an entry that is
 * not an object — and assert that each one yields less (an entry dropped, an empty list, a null) rather
 * than an exception on the FX thread or a row labelled with the text {@code null}.
 */
class JdtlsAnswerShapesTest {

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    private static List<String> labels(List<JdtlsGenerate.Candidate> candidates) {
        return candidates.stream().map(JdtlsGenerate.Candidate::label).toList();
    }

    private static List<String> names(List<JdtlsRefactor.Destination> destinations) {
        return destinations.stream().map(JdtlsRefactor.Destination::label).toList();
    }

    // --- generators ----------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"null", "7", "[]", "{}", "{\"fields\":7}", "{\"fields\":{}}"})
    void aCheckAnswerWithoutAUsableListOffersNothing(String answer) {
        JsonElement status = json(answer);
        assertEquals(List.of(), JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, status));
        assertEquals(List.of(), JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, null));
        assertEquals(List.of(), JdtlsGenerate.delegateFields(status));
        assertEquals(List.of(), JdtlsGenerate.delegateFields(null));
        assertEquals(List.of(), JdtlsGenerate.constructorCandidates(status));
        assertEquals(List.of(), JdtlsGenerate.constructorCandidates(null));
    }

    @Test
    void entriesThatAreNotObjectsAreSkippedAndMissingPartsAreLeftOutOfTheLabel() {
        JsonElement status = json("{\"fields\":[7, null, \"x\","
                + "{\"type\":\"int\"},"
                + "{\"name\":\" \",\"type\":\"int\"},"
                + "{\"name\":\"count\",\"type\":\" \"},"
                + "{\"name\":\"run\",\"parameters\":[\"int\",{\"odd\":1}]},"
                + "{\"name\":\"plain\",\"parameters\":7}]}");

        assertEquals(
                List.of("?", "?", "count", "run(int, ?)", "plain"),
                labels(JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, status)),
                "a nameless member is shown as unknown; a blank type adds nothing");
    }

    @Test
    void accessorRowsSayWhichAccessorsAreMissing() {
        JsonElement fields = json("[7, null,"
                + "{\"fieldName\":\"both\",\"typeName\":\"int\",\"generateGetter\":true,\"generateSetter\":true},"
                + "{\"fieldName\":\"getOnly\",\"typeName\":\" \",\"generateGetter\":true,\"generateSetter\":false},"
                + "{\"fieldName\":\"setOnly\",\"generateGetter\":\"yes\",\"generateSetter\":true},"
                + "{\"fieldName\":\" \",\"typeName\":\"long\"},"
                + "{\"typeName\":\"long\"}]");

        assertEquals(
                List.of("both : int  (get, set)", "getOnly  (get)", "setOnly  (set)", "? : long", "? : long"),
                labels(JdtlsGenerate.candidates(JdtlsGenerate.Kind.ACCESSORS, fields)),
                "a flag that is not a boolean is not a yes");
    }

    @Test
    void delegateFieldsNeedAFieldAndAtLeastOneMethodLeft() {
        JsonElement status = json("{\"delegateFields\":[7, null,"
                + "{\"delegateMethods\":[{\"name\":\"a\"}]},"
                + "{\"field\":\"items\",\"delegateMethods\":[{\"name\":\"a\"}]},"
                + "{\"field\":{\"name\":\"items\"}},"
                + "{\"field\":{\"name\":\"items\"},\"delegateMethods\":{}},"
                + "{\"field\":{\"name\":\"items\"},\"delegateMethods\":[7, null]},"
                + "{\"field\":{\"name\":\"kept\"},\"delegateMethods\":[7, {\"name\":\"size\",\"parameters\":[]}]}]}");

        List<JdtlsGenerate.DelegateField> fields = JdtlsGenerate.delegateFields(status);

        assertEquals(1, fields.size());
        assertEquals("kept", fields.get(0).label());
        assertEquals(List.of("size()"), labels(fields.get(0).methods()));
    }

    @Test
    void onlyTheFirstSuperConstructorIsPreselected() {
        JsonElement status = json("{\"constructors\":[7, null,"
                + "{\"name\":\"Base\",\"parameters\":[]},{\"name\":\"Base\",\"parameters\":[\"int\"]}]}");

        List<JdtlsGenerate.Candidate> offered = JdtlsGenerate.constructorCandidates(status);

        assertEquals(List.of("Base()", "Base(int)"), labels(offered));
        assertTrue(offered.get(0).preselected());
        assertFalse(offered.get(1).preselected());
    }

    @Test
    void constructorParamsCarryAnEmptyListWhenTheCheckAnswerHadNone() {
        var none = JdtlsGenerate.generateParams(
                JdtlsGenerate.Kind.CONSTRUCTORS, json("{}"), List.of(), json("{\"constructors\":7}"));
        assertEquals(0, none.getAsJsonArray("constructors").size());
        var missing = JdtlsGenerate.generateParams(JdtlsGenerate.Kind.CONSTRUCTORS, json("{}"), List.of(), null);
        assertEquals(0, missing.getAsJsonArray("constructors").size());
        var notAnObject =
                JdtlsGenerate.generateParams(JdtlsGenerate.Kind.CONSTRUCTORS, json("{}"), List.of(), json("[]"));
        assertEquals(0, notAnObject.getAsJsonArray("constructors").size());
    }

    /** Delegate methods have their own parameter builder; asking the general one for them is a bug in the
     *  caller and says so. */
    @Test
    void delegateMethodsAreNotAssembledByTheGeneralBuilder() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JdtlsGenerate.generateParams(JdtlsGenerate.Kind.DELEGATE_METHODS, json("{}"), List.of(), null));
    }

    @Test
    void everyOtherKindBuildsItsOwnParameterObject() {
        for (JdtlsGenerate.Kind kind : JdtlsGenerate.Kind.values()) {
            if (kind == JdtlsGenerate.Kind.DELEGATE_METHODS) {
                continue;
            }
            var params = JdtlsGenerate.generateParams(kind, json("{\"k\":1}"), List.of(), json("{}"));
            assertEquals(1, params.getAsJsonObject("context").get("k").getAsInt(), kind + " lost the action params");
        }
    }

    // --- refactorings --------------------------------------------------------------------------------

    @Test
    void aRefactoringCommandNeedsANameAndParameters() {
        assertNull(
                JdtlsRefactor.parse(JdtlsRefactor.COMMAND, List.of(json("{}"), json("{}"))),
                "the name is not a string");
        var noDocument = JdtlsRefactor.parse(JdtlsRefactor.COMMAND, List.of(json("\"moveFile\""), json("7")));
        assertNotNull(noDocument);
        assertNull(noDocument.documentUri(), "parameters that are not an object name no document");
        var noUri = JdtlsRefactor.parse(
                JdtlsRefactor.COMMAND,
                List.of(json("\"moveFile\""), json("{\"textDocument\":7}"), json("\"not an info object\"")));
        assertNull(noUri.documentUri());
        assertNull(noUri.info("projectName"));
        var kinds = JdtlsRefactor.parse(
                JdtlsRefactor.COMMAND,
                List.of(
                        json("\"moveType\""),
                        json("{\"textDocument\":{\"uri\":\"file:///a\"}}"),
                        json("{\"supportedDestinationKinds\":[{\"odd\":1},\"class\"],\"projectName\":7}")));
        assertEquals("file:///a", kinds.documentUri());
        assertTrue(kinds.supportsDestination("class"));
        assertFalse(kinds.supportsDestination("newFile"));
        assertEquals("7", kinds.info("projectName"));
        var notAList = JdtlsRefactor.parse(
                JdtlsRefactor.COMMAND,
                List.of(json("\"moveType\""), json("{}"), json("{\"supportedDestinationKinds\":\"class\"}")));
        assertFalse(notAList.supportsDestination("class"));
    }

    @Test
    void destinationsWithoutANameAreNotOffered() {
        JsonElement answer = json("{\"destinations\":[7, {\"path\":\"/p\"},"
                + "{\"displayName\":\"demo\",\"path\":\"/p/demo\"},"
                + "{\"name\":\"helper\"},{\"name\":\"field\",\"type\":\"Helper\",\"isField\":true}]}");

        assertEquals(List.of("demo"), names(JdtlsRefactor.packages(answer, true)));
        List<JdtlsRefactor.Destination> targets = JdtlsRefactor.instanceTargets(answer);
        assertEquals(List.of("helper", "field : Helper"), names(targets));
        assertEquals("", targets.get(0).detail());
        assertEquals("field", targets.get(1).detail());
    }

    @Test
    void typesAreListedWithTheirPackageAndWithoutTheTypeItself() {
        JsonElement answer = json("[7, null, {\"kind\":5},"
                + "{\"name\":\"TopLevel\"},{\"name\":\"Blank\",\"containerName\":\" \"},"
                + "{\"name\":\"A\",\"containerName\":\"demo\"}]");

        List<JdtlsRefactor.Destination> types = JdtlsRefactor.types(answer, "demo.A");

        assertEquals(List.of("TopLevel", "Blank"), names(types));
        assertEquals("", types.get(0).detail(), "no container: the default package");
        assertEquals(List.of("Blank", "A"), names(JdtlsRefactor.types(answer, "TopLevel")));
        assertEquals(List.of(), JdtlsRefactor.types(null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "7", "[]", "{}", "{\"members\":7}", "{\"members\":[7, null, {\"name\":\"x\"}]}"})
    void anExtractInterfaceStatusWithoutUsableMembersOffersNothing(String answer) {
        JsonElement status = json(answer);
        assertEquals(List.of(), JdtlsRefactor.interfaceMembers(status));
        assertEquals(List.of(), JdtlsRefactor.interfaceMembers(null));
        assertEquals(List.of(), JdtlsRefactor.interfacePackages(status));
        assertEquals(List.of(), JdtlsRefactor.interfacePackages(null));
        assertNull(JdtlsRefactor.subTypeName(status));
        assertNull(JdtlsRefactor.subTypeName(null));
    }

    @Test
    void anAnswerIsAnErrorOnlyWithANonBlankMessage() {
        assertNull(JdtlsRefactor.errorMessage(null));
        assertNull(JdtlsRefactor.errorMessage(json("[]")));
        assertNull(JdtlsRefactor.errorMessage(json("{\"errorMessage\":\" \"}")));
        assertNull(JdtlsRefactor.errorMessage(json("{\"errorMessage\":{}}")));
        assertNull(JdtlsRefactor.editOf(null));
        assertNull(JdtlsRefactor.editOf(json("{\"edit\":\"not an edit\"}")));
        assertNull(JdtlsRefactor.editOf(json("{}")));
    }

    // --- change signature ----------------------------------------------------------------------------

    private static final String INFO = "{\"methodIdentifier\":\"id\",\"modifier\":\"public\",\"returnType\":\"String\","
            + "\"methodName\":\"greet\",\"parameters\":[7,"
            + "{\"type\":\"int\",\"name\":\"a\",\"originalIndex\":4},{\"type\":\"int\",\"name\":\"b\"}],"
            + "\"exceptions\":[{\"type\":\"java.io.IOException\"},{\"type\":\"E2\"}]}";

    private static List<Integer> originalIndexes(JsonArray arguments) {
        return arguments.get(5).getAsJsonArray().asList().stream()
                .map(p -> p.getAsJsonObject().get("originalIndex").getAsInt())
                .toList();
    }

    @Test
    void theSignatureLineListsEveryExceptionAndSkipsWhatIsMissing() {
        assertEquals(
                "public String greet(int a, int b) throws java.io.IOException, E2",
                JdtlsRefactor.signatureText(json(INFO)));
        assertEquals("()", JdtlsRefactor.signatureText(json("{\"modifier\":\" \"}")));
        assertEquals("", JdtlsRefactor.signatureText(json("[]")));
    }

    @Test
    void renamedParametersKeepTheirPlaceByTypeAndTheServersOwnIndex() {
        // Both renamed: each is matched to the unclaimed original of its type at the same position.
        JsonArray both = JdtlsRefactor.changeSignatureArguments(json(INFO), "public String greet(int x, int y)");
        assertEquals(List.of(4, 1), originalIndexes(both), "the server's index when it gave one, else the position");

        // One kept by name, one renamed, one of a type nothing had: the last is new.
        JsonArray mixed = JdtlsRefactor.changeSignatureArguments(
                json(INFO), "public String greet(int b, int renamed, long extra = )");
        assertEquals(List.of(1, 4, -1), originalIndexes(mixed));
        assertEquals(
                "null",
                mixed.get(5)
                        .getAsJsonArray()
                        .get(2)
                        .getAsJsonObject()
                        .get("defaultValue")
                        .getAsString(),
                "a new parameter given no value passes null at the call sites");

        // More parameters of a type than there were: the surplus one is new.
        JsonArray surplus =
                JdtlsRefactor.changeSignatureArguments(json(INFO), "public String greet(int p, int q, int r)");
        assertEquals(List.of(4, 1, -1), originalIndexes(surplus));
    }

    @Test
    void aConstructorHasNoReturnTypeButAMethodMayNotLoseIts() {
        JsonElement constructor = json("{\"methodIdentifier\":\"id\",\"methodName\":\"App\",\"parameters\":[]}");
        JsonArray kept = JdtlsRefactor.changeSignatureArguments(constructor, "App(int n)");
        assertNotNull(kept);
        assertEquals("", kept.get(4).getAsString(), "no return type, before or after");
        assertEquals("", kept.get(3).getAsString());

        JsonElement blankReturn = json("{\"methodName\":\"App\",\"returnType\":\" \",\"parameters\":[]}");
        assertEquals(
                " ",
                JdtlsRefactor.changeSignatureArguments(blankReturn, "protected App()")
                        .get(4)
                        .getAsString());

        assertNull(
                JdtlsRefactor.changeSignatureArguments(json(INFO), "greet(int a)"), "the method lost its return type");
        assertNull(JdtlsRefactor.changeSignatureArguments(json(INFO), "public greet(int a)"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "(int a)",
                "public String 1greet(int a)",
                "public String greet(int a",
                "public String greet)int a(",
                "public String greet(List<String>names)",
                "public String greet(int 1a)",
                "public String greet( a)"
            })
    void aLineThatIsNotASignatureIsRefused(String edited) {
        assertNull(JdtlsRefactor.changeSignatureArguments(json(INFO), edited));
        assertNull(JdtlsRefactor.changeSignatureArguments(json("[]"), edited));
        assertNull(JdtlsRefactor.changeSignatureArguments(json(INFO), null));
    }

    @Test
    void commasInsideTypeArgumentsCallsAndArraysDoNotSplitParameters() {
        assertEquals(
                List.of("Map<String, List<Integer>> m", "int[] xs = new int[2]", "String s = f(a, b)"),
                JdtlsRefactor.splitTopLevel(
                        " Map<String, List<Integer>> m , int[] xs = new int[2], String s = f(a, b), ,"));
        assertEquals(List.of(), JdtlsRefactor.splitTopLevel("  "));
    }
}
