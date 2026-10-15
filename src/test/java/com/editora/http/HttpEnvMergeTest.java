package com.editora.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the two environment files combine, and how nested values are reached (the JetBrains rules). */
class HttpEnvMergeTest {

    @Test
    void theMergeOrderIsSharedPublicSharedPrivatePublicPrivate() {
        String pub = "{\"$shared\":{\"a\":\"pub-shared\",\"b\":\"pub-shared\",\"c\":\"pub-shared\"},"
                + "\"dev\":{\"host\":\"PUBLIC-DEV\",\"b\":\"pub-dev\"}}";
        String priv = "{\"$shared\":{\"host\":\"PRIVATE-SHARED\",\"a\":\"priv-shared\"},\"dev\":{\"c\":\"priv-dev\"}}";
        Map<String, String> m = HttpEnv.merged(pub, priv, "dev");
        assertEquals("PUBLIC-DEV", m.get("host"), "a private $shared value must not beat the public environment");
        assertEquals("priv-shared", m.get("a"));
        assertEquals("pub-dev", m.get("b"));
        assertEquals("priv-dev", m.get("c"));
    }

    @Test
    void namesComeFromBothFiles() {
        assertEquals(
                List.of("dev", "prod", "staging"),
                HttpEnv.environmentNames("{\"dev\":{},\"prod\":{}}", "{\"$shared\":{},\"staging\":{},\"dev\":{}}"));
        assertEquals(List.of("local"), HttpEnv.environmentNames(null, "{\"local\":{}}"));
        assertEquals(List.of(), HttpEnv.environmentNames("not json", ""));
    }

    @Test
    void nestedObjectsArraysAndNull() {
        String json = "{\"dev\":{\"client\":{\"host\":{\"url\":\"http://n\"},\"a.b\":1},"
                + "\"users\":[{\"name\":\"ada\"}],\"z\":null,\"n\":12,\"t\":true}}";
        Map<String, String> m = HttpEnv.variables(json, "dev");
        assertEquals("http://n", m.get("client.host.url"));
        assertEquals("{\"url\":\"http://n\"}", m.get("client.host"));
        assertEquals("1", m.get("client['a.b']"));
        assertEquals("ada", m.get("users[0].name"));
        assertEquals("", m.get("z"), "a JSON null is empty, not the text null");
        assertEquals("12", m.get("n"));
        assertEquals("true", m.get("t"));
    }

    @Test
    void noFilesMeanNoVariables() {
        assertTrue(HttpEnv.merged(null, null, "dev").isEmpty());
    }

    @Test
    void aNestedObjectIsTheSameMergedObjectAsItsMembers() {
        String pub = "{\"dev\":{\"obj\":{\"a\":1,\"b\":2},\"s\":{\"x\":1}}}";
        String priv = "{\"dev\":{\"obj\":{\"c\":3,\"b\":20},\"s\":\"text\"}}";
        Map<String, String> m = HttpEnv.merged(pub, priv, "dev");
        assertEquals("{\"a\":1,\"b\":20,\"c\":3}", m.get("obj"), "the object holds every merged member");
        assertEquals("1", m.get("obj.a"));
        assertEquals("20", m.get("obj.b"), "the private member wins");
        assertEquals("3", m.get("obj.c"));
        assertEquals("text", m.get("s"), "a value replaces an object");
        assertTrue(!m.containsKey("s.x"), "and its old members are gone: " + m);
    }
}
