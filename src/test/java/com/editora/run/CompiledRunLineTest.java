package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CompiledRunLine}: where Run File's ▶ sits on a C, C++, Go or Rust file, and when it has none. */
class CompiledRunLineTest {

    @Test
    void theCompiledLanguagesAreTheOnesSupported() {
        for (String language : new String[] {"c", "cpp", "go", "rust"}) {
            assertTrue(CompiledRunLine.supports(language), language);
        }
        assertFalse(CompiledRunLine.supports("python"));
        assertFalse(CompiledRunLine.supports("csharp"), "only Debug starts a C# file");
        assertFalse(CompiledRunLine.supports(null));
    }

    @Test
    void aCOrCppFileRunsFromItsMainDefinition() {
        String tally =
                "#include <stdio.h>\n\nstatic int helper(void) { return 1; }\n\nint main(void) {\n    return 0;\n}\n";
        assertEquals(4, CompiledRunLine.runLine("c", "tally.c", tally));
        assertEquals(
                1,
                CompiledRunLine.runLine(
                        "cpp", "a.cpp", "#include <iostream>\nauto main(int argc, char **argv) -> int {\n"));
        assertEquals(
                1, CompiledRunLine.runLine("c", "gnu.c", "int\nmain (void)\n{\n"), "GNU style: main alone on its line");
        assertEquals(0, CompiledRunLine.runLine("cpp", "w.cpp", "int main()\n{\n"));
    }

    @Test
    void aCFileWithoutAMainDefinitionHasNoProgram() {
        assertEquals(-1, CompiledRunLine.runLine("c", "lib.c", "int remain(int x) { return domain(x); }\n"));
        assertEquals(-1, CompiledRunLine.runLine("c", "decl.c", "int main(void);\n"), "a declaration is not one");
        assertEquals(-1, CompiledRunLine.runLine("c", "c.c", "// main( is described here\n    return main(1);\n"));
        assertEquals(-1, CompiledRunLine.runLine("cpp", "x.hpp", "int main() {}\n"), "a header has no program");
        assertEquals(-1, CompiledRunLine.runLine("c", "x.h", "int main(void) { return 0; }\n"));
    }

    @Test
    void aGoFileRunsFromFuncMainInPackageMainOrItsFirstTest() {
        assertEquals(4, CompiledRunLine.runLine("go", "hello.go", "package main\n\nimport \"fmt\"\n\nfunc main() {\n"));
        assertEquals(-1, CompiledRunLine.runLine("go", "shelf.go", "package shelf\n\nfunc main() {}\n"));
        assertEquals(-1, CompiledRunLine.runLine("go", "util.go", "package main\n\nfunc helper() {}\n"));
        assertEquals(
                2, CompiledRunLine.runLine("go", "shelf_test.go", "package shelf\n\nfunc TestCount(t *testing.T) {\n"));
    }

    @Test
    void aRustFileRunsFromItsTopLevelFnMain() {
        assertEquals(1, CompiledRunLine.runLine("rust", "main.rs", "use std::io;\nfn main() {\n"));
        assertEquals(1, CompiledRunLine.runLine("rust", "main.rs", "#[tokio::main]\nasync fn main() {\n"));
        assertEquals(-1, CompiledRunLine.runLine("rust", "lib.rs", "pub fn add(a: i32) -> i32 { a }\n"));
    }
}
