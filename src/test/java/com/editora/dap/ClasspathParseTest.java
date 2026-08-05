package com.editora.dap;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * jdtls answers {@code vscode.java.resolveClasspath} with {@code [modulepaths, classpaths]}. Verified
 * against a real jdtls 1.60 + java-debug 0.53.2 driving the generated quickstart project:
 *
 * <pre>[[], ["/home/user/adltest/target/classes"]]</pre>
 *
 * <p>An empty parse of that reply is reported to the user as "the project hasn't finished importing", which
 * sends them to debug a healthy language server — so what the parser accepts is worth pinning.
 */
class ClasspathParseTest {

    private static List<List<String>> parse(Object res) {
        List<String> modulepaths = new ArrayList<>();
        List<String> classpaths = new ArrayList<>();
        FxlessAccess.parseClasspath(res, modulepaths, classpaths);
        return List.of(modulepaths, classpaths);
    }

    @Test
    void readsTheRealJdtlsReplyAsAGsonElement() {
        Object res = JsonParser.parseString("[[], [\"/home/user/adltest/target/classes\"]]");
        assertEquals(List.of(), parse(res).get(0));
        assertEquals(List.of("/home/user/adltest/target/classes"), parse(res).get(1));
    }

    @Test
    void readsTheSameReplyAsAPlainList() {
        // lsp4j may hand an untyped executeCommand result back as java.util.List rather than a JsonElement.
        Object res = List.of(List.of(), List.of("/home/user/adltest/target/classes"));
        assertEquals(List.of(), parse(res).get(0));
        assertEquals(List.of("/home/user/adltest/target/classes"), parse(res).get(1));
    }

    @Test
    void aPathWithASpaceOrCommaSurvives() {
        // The old fallback rendered a List via toString() and re-parsed it, which emits UNQUOTED elements:
        // "[[], [/home/My Projects/a,b/target/classes]]" does not round-trip. Reading the list avoids it.
        Object res = List.of(List.of(), List.of("/home/My Projects/a,b/target/classes"));
        assertEquals(List.of("/home/My Projects/a,b/target/classes"), parse(res).get(1));
    }

    @Test
    void modulePathsAreReadWhenPresent() {
        Object res = List.of(List.of("/mods/foo.jar"), List.of("/cp/classes"));
        assertEquals(List.of("/mods/foo.jar"), parse(res).get(0));
        assertEquals(List.of("/cp/classes"), parse(res).get(1));
    }

    @Test
    void junkYieldsEmptyRatherThanThrowing() {
        assertEquals(List.of(), parse(null).get(1));
        assertEquals(List.of(), parse("not a classpath").get(1));
        assertEquals(List.of(), parse(List.of()).get(1));
    }
}
