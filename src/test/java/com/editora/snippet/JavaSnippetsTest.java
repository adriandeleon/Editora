package com.editora.snippet;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.ConfigManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled Java snippets expand to Java that names the file's type and package. */
class JavaSnippetsTest {

    @TempDir
    Path dir;

    private SnippetManager snippets() {
        return new SnippetManager(new ConfigManager(dir));
    }

    /** Expands {@code prefix} as if typed in {@code file} (directory and name taken from the path). */
    private String expand(String prefix, String file) {
        Snippet s = snippets().byPrefix("java", prefix);
        assertNotNull(s, prefix);
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        VariableResolver vars =
                new VariableResolver(file.substring(slash + 1), file.substring(0, slash), file, "", "", 0, "");
        return SnippetParser.parse(s.body(), vars).text();
    }

    @Test
    void everySnippetExpandsWithoutLeftoverSyntaxAndHasADescription() {
        List<Snippet> all = snippets().bundledSnippets("java");
        assertTrue(all.size() >= 55, "one row per trigger (31 before this pass): " + all.size());
        for (Snippet s : all) {
            String text = expand(s.prefix(), "/p/src/main/java/com/acme/Shop.java");
            assertFalse(text.contains("${"), s.prefix() + ": " + text);
            assertFalse(text.contains("TM_"), s.prefix() + " names a variable as text: " + text);
            assertFalse(s.description() == null || s.description().isBlank(), s.prefix());
        }
    }

    @Test
    void typesAreNamedAfterTheFile() {
        String file = "/p/src/main/java/com/acme/Shop.java";
        assertTrue(expand("class", file).startsWith("public class Shop {"), expand("class", file));
        assertTrue(expand("record", file).startsWith("public record Shop(String name) {"));
        assertTrue(expand("interface", file).startsWith("public interface Shop {"));
        assertTrue(expand("enum", file).startsWith("public enum Shop {"));
        assertTrue(expand("ctor", file).startsWith("public Shop() {"), expand("ctor", file));
        assertTrue(expand("sealed", file).contains("record First(String value) implements Shop {}"));
    }

    @Test
    void packageIsTheFoldersPackageUnderTheSourceRoot() {
        assertEquals("package com.acme.util;", expand("package", "/p/app/src/main/java/com/acme/util/A.java"));
        assertEquals("package com.acme;", expand("package", "/p/src/test/java/com/acme/ATest.java"));
        assertEquals("package com.acme;", expand("package", "C:\\p\\src\\main\\java\\com\\acme\\A.java"));
        assertEquals("package ;", expand("package", "/p/src/main/java/A.java"), "the unnamed package");
        assertEquals("package ;", expand("package", "/home/me/scratch/A.java"), "no source root: type it");
    }

    @Test
    void statementsEndInASemicolon() {
        String file = "/p/src/main/java/A.java";
        assertEquals("import java.util.List;", expand("import", file));
        assertEquals("Object foo = new Object();", expand("new", file));
        assertEquals("System.out.println(\"value = \" + value);", expand("soutv", file));
    }

    @Test
    void switchUsesArrowCases() {
        String text = expand("switch", "/p/A.java");
        assertEquals(
                "switch (value) {\n\tcase CONSTANT -> {}\n"
                        + "\tdefault -> throw new IllegalStateException(\"Unexpected value: \" + value);\n}",
                text);
    }

    @Test
    void intellijStyleAliasesExpandLikeTheEclipseNames() {
        SnippetManager m = snippets();
        for (String[] pair : new String[][] {
            {"psvm", "main"},
            {"sout", "sysout"},
            {"serr", "syserr"},
            {"iter", "foreach"},
            {"ifn", "ifnull"},
            {"inn", "ifnotnull"},
            {"twr", "try_resources"},
            {"thr", "throw"},
            {"psf", "const"}
        }) {
            assertEquals(
                    m.byPrefix("java", pair[1]).body(),
                    m.byPrefix("java", pair[0]).body(),
                    pair[0]);
        }
    }
}
