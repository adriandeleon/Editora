package com.editora.lsp;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaScriptShadowTest {

    @Test
    void onlyAJavaDocumentNotNamedDotJavaNeedsAStandIn() {
        assertTrue(JavaScriptShadow.wanted(Path.of("/s/launcher"), "java"));
        assertTrue(JavaScriptShadow.wanted(Path.of("/s/tool.sh"), "java"), "a language picked by hand counts");
        assertFalse(JavaScriptShadow.wanted(Path.of("/s/App.java"), "java"));
        assertFalse(JavaScriptShadow.wanted(Path.of("/s/launcher"), "shellscript"));
        assertFalse(JavaScriptShadow.wanted(null, "java"));
    }

    @Test
    void theStandInIsNamedLikeTheScriptAsAJavaIdentifier() {
        assertEquals("launcher.java", JavaScriptShadow.fileName(Path.of("/s/launcher")));
        assertEquals("my_tool_sh.java", JavaScriptShadow.fileName(Path.of("/s/my-tool.sh")));
        assertEquals("_2fa.java", JavaScriptShadow.fileName(Path.of("/s/2fa")));
    }

    @Test
    void theShebangBecomesABlockCommentOfTheSameLength() {
        String script = "#!/usr/bin/env -S java --source 25\nvoid main() {}\n";

        String wire = JavaScriptShadow.wireText(script);

        assertEquals("/*/usr/bin/env -S java --source */\nvoid main() {}\n", wire);
        assertEquals(script.length(), wire.length());
        assertEquals(script.indexOf('\n'), wire.indexOf('\n'), "every position stays where it was");
    }

    @Test
    void aCrlfLineKeepsItsReturn() {
        assertEquals(
                "/*/usr/bin/java --source */\r\nvoid main() {}",
                JavaScriptShadow.wireText("#!/usr/bin/java --source 25\r\nvoid main() {}"));
    }

    @Test
    void aLineThatCannotHoldABlockCommentGetsALineComment() {
        assertEquals("//x\nvoid main() {}", JavaScriptShadow.wireText("#!x\nvoid main() {}"), "too short");
        assertEquals("// a */ b\n", JavaScriptShadow.wireText("#! a */ b\n"), "would close early");
        assertEquals("//", JavaScriptShadow.wireText("#!"));
    }

    @Test
    void textWithoutAShebangIsLeftAlone() {
        assertEquals("void main() {}\n", JavaScriptShadow.wireText("void main() {}\n"));
        assertEquals(" #!/usr/bin/java\n", JavaScriptShadow.wireText(" #!/usr/bin/java\n"));
        assertEquals(null, JavaScriptShadow.wireText(null));
    }

    @Test
    void anAliasedDocumentGoesOutAsItsAliasAndComesBackAsItself() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        Path script = tmp.resolve("scripts").resolve("launcher");
        Path standIn = tmp.resolve("x").resolve("s1").resolve("launcher.java");
        String key = DocumentUris.of(script);
        String alias = standIn.toUri().toString();
        Map<String, String> aliases = new ConcurrentHashMap<>();
        var uris = WireUris.aliasing(() -> WireUris.LOCAL, aliases);
        aliases.put(key, alias);

        assertEquals(alias, uris.toWire(key));
        assertEquals(key, uris.toKey(alias));
        assertEquals(key, uris.toKey(alias.replaceFirst("^file:///", "file:/")), "however it is spelled");
        assertEquals(script, uris.toPath(alias));
        String other = DocumentUris.of(tmp.resolve("App.java"));
        assertEquals(other, uris.toWire(other), "everything else is the base's");
        assertEquals("jdt://contents/x", uris.toKey("jdt://contents/x"));
    }
}
