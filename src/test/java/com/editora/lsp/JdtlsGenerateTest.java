package com.editora.lsp;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdtlsGenerate} — the jdtls source-generation prompts (#741).
 *
 * <p>The fixtures are <b>real responses</b>, captured by driving jdtls 1.60 against a small class, not
 * hand-written guesses at the shape. That matters here more than usual: these are vendor requests with no
 * specification, so the only authority for their shape is the server.
 */
class JdtlsGenerateTest {

    /** Captured from {@code java/checkToStringStatus}. Note jdtls pre-selects the fields, not the methods. */
    private static final String TO_STRING_STATUS = """
            {"type":"Person",
             "fields":[
               {"bindingKey":"Ldemo/Person;.name)Ljava/lang/String;","name":"name","type":"String",
                "isField":true,"isSelected":true},
               {"bindingKey":"Ldemo/Person;.age)I","name":"age","type":"int",
                "isField":true,"isSelected":true},
               {"bindingKey":"Ljava/lang/Object;.hashCode()I","name":"hashCode","type":"int",
                "isField":false,"isSelected":false,"parameters":[]}],
             "exists":false}
            """;

    /** Captured from {@code java/listOverridableMethods} — a different array name and extra fields. */
    private static final String OVERRIDABLE = """
            {"type":"Person",
             "methods":[
               {"bindingKey":"Ljava/lang/Object;.equals(Ljava/lang/Object;)Z","name":"equals",
                "parameters":["Object"],"unimplemented":false,"declaringClass":"java.lang.Object",
                "declaringClassType":"class"},
               {"bindingKey":"Ljava/lang/Comparable<Ldemo/Person;>;.compareTo(Ldemo/Person;)I",
                "name":"compareTo","parameters":["Person"],"unimplemented":true,
                "declaringClass":"java.lang.Comparable","declaringClassType":"interface"}]}
            """;

    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    @Test
    void everyPromptCommandMapsToItsRequestPair() {
        assertEquals(JdtlsGenerate.Kind.TO_STRING, JdtlsGenerate.forCommand("java.action.generateToStringPrompt"));
        assertEquals(JdtlsGenerate.Kind.HASH_CODE_EQUALS, JdtlsGenerate.forCommand("java.action.hashCodeEqualsPrompt"));
        assertEquals(
                JdtlsGenerate.Kind.CONSTRUCTORS, JdtlsGenerate.forCommand("java.action.generateConstructorsPrompt"));
        assertEquals(
                JdtlsGenerate.Kind.OVERRIDE_METHODS, JdtlsGenerate.forCommand("java.action.overrideMethodsPrompt"));
        assertEquals("java/checkToStringStatus", JdtlsGenerate.Kind.TO_STRING.checkRequest());
        assertEquals("java/generateToString", JdtlsGenerate.Kind.TO_STRING.generateRequest());
    }

    /** An ordinary code action's command must fall through to the normal apply path, not be intercepted. */
    @Test
    void anUnrelatedCommandIsNotAPrompt() {
        assertNull(JdtlsGenerate.forCommand("java.edit.organizeImports"));
        assertNull(JdtlsGenerate.forCommand(null));
        assertNull(JdtlsGenerate.forCommand(""));
    }

    @Test
    void candidatesReadTheFieldsArrayAndItsPreselection() {
        List<JdtlsGenerate.Candidate> found =
                JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, json(TO_STRING_STATUS));

        assertEquals(3, found.size());
        assertEquals("name : String", found.get(0).label());
        assertTrue(found.get(0).preselected(), "jdtls pre-selects the fields");
        assertEquals("hashCode() : int", found.get(2).label(), "a no-arg method shows empty parens");
        assertTrue(!found.get(2).preselected(), "…and is not pre-selected");
    }

    /** Override-methods uses "methods", not "fields" — reading a hardcoded key would yield an empty picker. */
    @Test
    void candidatesReadTheCorrectArrayPerKind() {
        List<JdtlsGenerate.Candidate> found =
                JdtlsGenerate.candidates(JdtlsGenerate.Kind.OVERRIDE_METHODS, json(OVERRIDABLE));

        assertEquals(2, found.size());
        assertEquals("equals(Object)", found.get(0).label(), "no ': type' — an override response carries none");
        assertEquals("compareTo(Person)", found.get(1).label());
        assertTrue(
                JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, json(OVERRIDABLE))
                        .isEmpty(),
                "reading 'fields' from an override response finds nothing");
    }

    /**
     * The chosen objects go back <b>verbatim</b>. jdtls keys them by an opaque {@code bindingKey}, so
     * rebuilding them from parsed fields would drop anything we didn't model — silently generating the wrong
     * members.
     */
    @Test
    void theChosenCandidatesArePassedBackUnmodified() {
        List<JdtlsGenerate.Candidate> found =
                JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, json(TO_STRING_STATUS));
        JsonElement params = json("{\"textDocument\":{\"uri\":\"file:///A.java\"}}");

        var args = JdtlsGenerate.generateParams(
                JdtlsGenerate.Kind.TO_STRING, params, found.subList(0, 1), json(TO_STRING_STATUS));

        assertSame(params, args.get("context"), "the original action params are retained as context");
        var picked = args.getAsJsonArray("fields");
        assertEquals(1, picked.size());
        assertEquals(
                "Ldemo/Person;.name)Ljava/lang/String;",
                picked.get(0).getAsJsonObject().get("bindingKey").getAsString(),
                "the opaque binding key survives the round trip");
    }

    /**
     * {@code class AppException extends RuntimeException {}}: five super constructors and no fields. The
     * prompt used to read only {@code fields} ("nothing to generate") and to send every super constructor
     * back, generating one constructor each.
     */
    @Test
    void superConstructorsAreChoosableAndOnlyTheChosenOnesAreSent() {
        JsonElement status = json("{\"constructors\":["
                + "{\"name\":\"RuntimeException\",\"parameters\":[],\"bindingKey\":\"c0\"},"
                + "{\"name\":\"RuntimeException\",\"parameters\":[\"String\"],\"bindingKey\":\"c1\"},"
                + "{\"name\":\"RuntimeException\",\"parameters\":[\"String\",\"Throwable\"],\"bindingKey\":\"c2\"}],"
                + "\"fields\":[]}");

        assertTrue(JdtlsGenerate.candidates(JdtlsGenerate.Kind.CONSTRUCTORS, status)
                .isEmpty());
        List<JdtlsGenerate.Candidate> offered = JdtlsGenerate.constructorCandidates(status);
        assertEquals(3, offered.size(), "a class without fields still has constructors to generate");
        assertEquals("RuntimeException(String, Throwable)", offered.get(2).label());
        assertTrue(offered.get(0).preselected());
        assertFalse(offered.get(1).preselected());

        var args = JdtlsGenerate.generateParams(
                JdtlsGenerate.Kind.CONSTRUCTORS, json("{}"), List.of(), status, List.of(offered.get(1)));

        assertEquals(1, args.getAsJsonArray("constructors").size(), "only the chosen super constructor");
        assertEquals(
                "c1",
                args.getAsJsonArray("constructors")
                        .get(0)
                        .getAsJsonObject()
                        .get("bindingKey")
                        .getAsString());
        assertEquals(0, args.getAsJsonArray("fields").size());
        assertTrue(JdtlsGenerate.constructorCandidates(json("[]")).isEmpty());
    }

    /** Each request is one server-defined object, including constructor choices and regenerate=false. */
    @Test
    void theGenerateArgumentsMatchEachRequestsShape() {
        JsonElement params = json("{}");
        List<JdtlsGenerate.Candidate> none = List.of();

        JsonElement constructorStatus = json("{\"constructors\":[{\"bindingKey\":\"ctor\"}],\"fields\":[]}");
        var constructors =
                JdtlsGenerate.generateParams(JdtlsGenerate.Kind.CONSTRUCTORS, params, none, constructorStatus);
        assertEquals(
                "ctor",
                constructors
                        .getAsJsonArray("constructors")
                        .get(0)
                        .getAsJsonObject()
                        .get("bindingKey")
                        .getAsString());
        var hash = JdtlsGenerate.generateParams(JdtlsGenerate.Kind.HASH_CODE_EQUALS, params, none, null);
        assertEquals(3, hash.size());
        assertTrue(!hash.get("regenerate").getAsBoolean(), "never silently regenerate existing methods");
        var overrides = JdtlsGenerate.generateParams(JdtlsGenerate.Kind.OVERRIDE_METHODS, params, none, null);
        assertTrue(overrides.has("overridableMethods"));
    }

    /** A malformed or unexpected answer must degrade to an empty picker, never throw on the FX thread. */
    @Test
    void anUnusableResponseYieldsNoCandidates() {
        assertTrue(JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, null).isEmpty());
        assertTrue(JdtlsGenerate.candidates(null, json(TO_STRING_STATUS)).isEmpty());
        assertTrue(JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, json("[]"))
                .isEmpty());
        assertTrue(JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, json("{\"fields\":\"nope\"}"))
                .isEmpty());
        assertTrue(JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, json("{\"fields\":[1,null,{}]}"))
                        .size()
                <= 1);
    }

    /** A nameless entry still renders a row rather than a blank one. */
    @Test
    void anEntryWithNoNameStillGetsALabel() {
        List<JdtlsGenerate.Candidate> found =
                JdtlsGenerate.candidates(JdtlsGenerate.Kind.TO_STRING, json("{\"fields\":[{\"bindingKey\":\"x\"}]}"));

        assertEquals(1, found.size());
        assertEquals("?", found.get(0).label());
    }

    /** Captured from {@code java/resolveUnimplementedAccessors} (jdtls 1.61): a bare array. */
    private static final String ACCESSORS = """
            [{"fieldName":"count","isStatic":false,"generateGetter":true,"generateSetter":true,"typeName":"int"},
             {"fieldName":"items","isStatic":false,"generateGetter":true,"generateSetter":false,
              "typeName":"List<String>"}]
            """;

    /** Captured from {@code java/checkDelegateMethodsStatus} (jdtls 1.61), shortened. */
    private static final String DELEGATES = """
            {"delegateFields":[
              {"field":{"bindingKey":"Ldemo/App;.items)Ljava/util/List<Ljava/lang/String;>;","name":"items",
                        "type":"List<String>","isField":true,"isSelected":false},
               "delegateMethods":[
                 {"bindingKey":"Ljava/util/List<Ljava/lang/String;>;.add(Ljava/lang/String;)Z","name":"add",
                  "parameters":["String"]},
                 {"bindingKey":"Ljava/util/List<Ljava/lang/String;>;.clear()V","name":"clear","parameters":[]}]},
              {"field":{"bindingKey":"Ldemo/App;.done)Z","name":"done","type":"Done","isField":true},
               "delegateMethods":[]}]}
            """;

    @Test
    void accessorFieldsArePreselectedAndSayWhatIsMissing() {
        var kind = JdtlsGenerate.forCommand("java.action.generateAccessorsPrompt");
        assertEquals(JdtlsGenerate.Kind.ACCESSORS, kind);

        var fields = JdtlsGenerate.candidates(kind, json(ACCESSORS));

        assertEquals(
                List.of("count : int  (get, set)", "items : List<String>  (get)"),
                fields.stream().map(JdtlsGenerate.Candidate::label).toList());
        assertTrue(fields.stream().allMatch(JdtlsGenerate.Candidate::preselected));
        assertTrue(JdtlsGenerate.candidates(kind, json("{}")).isEmpty(), "not the array: nothing to offer");

        var params = JdtlsGenerate.generateParams(kind, json("{\"kind\":2}"), fields.subList(1, 2), json(ACCESSORS));
        assertEquals(2, params.getAsJsonObject("context").get("kind").getAsInt(), "the accessor kind goes back");
        assertEquals(1, params.getAsJsonArray("accessors").size());
        assertEquals(
                "items",
                params.getAsJsonArray("accessors")
                        .get(0)
                        .getAsJsonObject()
                        .get("fieldName")
                        .getAsString());
    }

    @Test
    void delegateMethodsAreChosenPerField() {
        var kind = JdtlsGenerate.forCommand("java.action.generateDelegateMethodsPrompt");
        assertEquals(JdtlsGenerate.Kind.DELEGATE_METHODS, kind);

        var fields = JdtlsGenerate.delegateFields(json(DELEGATES));

        assertEquals(1, fields.size(), "a field with nothing left to delegate is not offered");
        assertEquals("items : List<String>", fields.get(0).label());
        assertEquals(
                List.of("add(String)", "clear()"),
                fields.get(0).methods().stream()
                        .map(JdtlsGenerate.Candidate::label)
                        .toList());
        assertTrue(JdtlsGenerate.candidates(kind, json(DELEGATES)).isEmpty(), "never a flat list");
        assertTrue(JdtlsGenerate.delegateFields(json("[]")).isEmpty());

        var params = JdtlsGenerate.delegateParams(
                json("{}"), fields.get(0), fields.get(0).methods().subList(1, 2));
        var entry = params.getAsJsonArray("delegateEntries").get(0).getAsJsonObject();
        assertEquals("items", entry.getAsJsonObject("field").get("name").getAsString());
        assertEquals(
                "clear", entry.getAsJsonObject("delegateMethod").get("name").getAsString());
        assertTrue(params.has("context"));
    }
}
