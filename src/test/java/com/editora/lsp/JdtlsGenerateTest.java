package com.editora.lsp;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        List<Object> args = JdtlsGenerate.generateParams(JdtlsGenerate.Kind.TO_STRING, params, found.subList(0, 1));

        assertSame(params, args.get(0), "the original action params lead");
        var picked = (com.google.gson.JsonArray) args.get(1);
        assertEquals(1, picked.size());
        assertEquals(
                "Ldemo/Person;.name)Ljava/lang/String;",
                picked.get(0).getAsJsonObject().get("bindingKey").getAsString(),
                "the opaque binding key survives the round trip");
    }

    /** Two lists for constructors, and a trailing regenerate=false for hashCode/equals — server-defined order. */
    @Test
    void theGenerateArgumentsMatchEachRequestsShape() {
        JsonElement params = json("{}");
        List<JdtlsGenerate.Candidate> none = List.of();

        assertEquals(
                3,
                JdtlsGenerate.generateParams(JdtlsGenerate.Kind.CONSTRUCTORS, params, none)
                        .size());
        List<Object> hash = JdtlsGenerate.generateParams(JdtlsGenerate.Kind.HASH_CODE_EQUALS, params, none);
        assertEquals(3, hash.size());
        assertEquals(Boolean.FALSE, hash.get(2), "never silently regenerate existing methods");
        assertEquals(
                2,
                JdtlsGenerate.generateParams(JdtlsGenerate.Kind.TO_STRING, params, none)
                        .size());
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
}
