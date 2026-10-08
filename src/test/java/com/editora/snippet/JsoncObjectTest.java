package com.editora.snippet;

import java.io.IOException;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Splicing one member of a JSONC object without re-serializing the rest (N17: comments survive a save). */
class JsoncObjectTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private static JsoncObject parse(String text) throws IOException {
        return JsoncObject.parse(text, MAPPER.getFactory());
    }

    private static final String FILE = """
            {
              // my snippets
              "One": { "prefix": "o", "body": "1" }, // trailing note
              /* about two */
              "Two": { "prefix": "t", "body": ["a", "b"] }
            }
            """;

    @Test
    void membersAreLocatedInTheSource() throws IOException {
        JsoncObject doc = parse(FILE);
        assertEquals(2, doc.members.size());
        assertEquals("{ \"prefix\": \"o\", \"body\": \"1\" }", doc.valueText(doc.member("One")));
        assertEquals(3, doc.lineOf(doc.member("One").keyStart()));
        assertEquals(5, doc.lineOf(doc.member("Two").keyStart()));
        assertNull(doc.member("Three"));
    }

    @Test
    void replacingAMemberTouchesNothingElse() throws IOException {
        String out = parse(FILE).with("One", "\"Uno\"", "{\n  \"prefix\": \"u\"\n}");
        assertEquals(
                FILE.replace(
                        "\"One\": { \"prefix\": \"o\", \"body\": \"1\" }", "\"Uno\": {\n    \"prefix\": \"u\"\n  }"),
                out);
        assertEquals("u", MAPPER.readTree(out).get("Uno").get("prefix").asText());
    }

    @Test
    void appendingAddsACommaOnlyWhenThereIsNone() throws IOException {
        String out = parse(FILE).with(null, "\"Three\"", "{ }");
        assertTrue(out.contains("\"Two\": { \"prefix\": \"t\", \"body\": [\"a\", \"b\"] },\n  \"Three\": { }\n}"), out);
        String trailing = "{\n  \"One\": 1, // why\n}\n";
        String out2 = parse(trailing).with(null, "\"Two\"", "2");
        assertEquals("{\n  \"One\": 1,\n  \"Two\": 2 // why\n}\n", out2);
        assertEquals(2, MAPPER.readTree(out2).get("Two").asInt());
    }

    @Test
    void anEmptyOrMissingObjectGetsItsBraces() throws IOException {
        assertEquals("{\n  \"A\": 1\n}\n", parse("").with(null, "\"A\"", "1"));
        assertEquals("{\n  \"A\": 1\n}\n", parse("   \n").with(null, "\"A\"", "1"));
        assertEquals("{\n  \"A\": 1\n}\n", parse("null").with(null, "\"A\"", "1"));
        assertEquals("// mine\n{\n  \"A\": 1\n}\n", parse("// mine\n").with(null, "\"A\"", "1"));
        assertEquals(
                "{\n  // none yet\n  \"A\": 1\n}", parse("{\n  // none yet\n}").with(null, "\"A\"", "1"));
    }

    @Test
    void removingAMemberTakesItsCommaAndLine() throws IOException {
        JsoncObject doc = parse(FILE);
        String noOne = doc.without("One");
        assertEquals("""
                {
                  // my snippets
                  /* about two */
                  "Two": { "prefix": "t", "body": ["a", "b"] }
                }
                """, noOne.replace(" // trailing note\n", "\n").replace("// my snippets\n\n", "// my snippets\n"));
        assertEquals(1, parse(noOne).members.size());
        String noTwo = doc.without("Two");
        assertEquals(1, parse(noTwo).members.size(), noTwo);
        assertTrue(noTwo.contains("// my snippets") && noTwo.contains("/* about two */"), "comments stay: " + noTwo);
        assertTrue(!noTwo.contains("\"1\" },"), "the comma that separated them is gone: " + noTwo);
        assertEquals("{\n}\n", parse("{\n  \"Only\": 1\n}\n").without("Only"));
        assertEquals(FILE, doc.without("Absent"));
    }

    @Test
    void aTopLevelThatIsNotAnObjectIsRefused() {
        assertThrows(JsoncObject.NotAnObjectException.class, () -> parse("[1, 2]"));
        assertThrows(IOException.class, () -> parse("{ \"a\": 1 } trailing"));
        assertThrows(IOException.class, () -> parse("{ \"a\": "));
    }
}
