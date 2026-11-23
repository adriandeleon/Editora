package com.editora.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DenoTestsTest {

    private static final String FILE = """
            import { describe, it } from "jsr:@std/testing/bdd";

            Deno.test("adds", () => {
              const s = "not a ) bracket";
            });

            Deno.test({
              name: "subtracts (big)",
              fn() {},
            });

            Deno.test(function multiplies() {
            });

            Deno.test("outer", async (t) => {
              await t.step("inner", () => {});
            });

            describe("a suite", () => {
              it("one", () => {});
            });

            Deno.test(`made ${1}`, () => {});
            // Deno.test("commented", () => {});
            Deno.test.only('it\\'s only', () => {});
            """;

    @Test
    void theTestSpanningTheCaretIsNamed() {
        assertEquals("adds", DenoTests.at(FILE, 2));
        assertEquals("adds", DenoTests.at(FILE, 3), "a string with a parenthesis in it does not end the call");
        assertEquals("adds", DenoTests.at(FILE, 4));
    }

    @Test
    void anOptionsObjectsNameAndAFunctionsNameAreNames() {
        assertEquals("subtracts (big)", DenoTests.at(FILE, 9));
        assertEquals("multiplies", DenoTests.at(FILE, 12));
    }

    @Test
    void theOutermostTestIsNamedForAStepOrANestedIt() {
        assertEquals("outer", DenoTests.at(FILE, 16), "deno test --filter matches top-level tests alone");
        assertEquals("a suite", DenoTests.at(FILE, 20));
    }

    @Test
    void aComputedNameAndNoTestNameNothing() {
        assertNull(DenoTests.at(FILE, 22), "a template with a placeholder");
        assertNull(DenoTests.at(FILE, 1), "between tests");
        assertNull(DenoTests.at(FILE, 23), "commented out");
    }

    @Test
    void escapesAndModifiers() {
        assertEquals("it's only", DenoTests.at(FILE, 24));
    }

    @Test
    void anUnfinishedCallRunsToTheEnd() {
        assertEquals("half", DenoTests.at("Deno.test(\"half\", () => {\n  let x = 1;\n", 1));
        assertNull(DenoTests.at("Deno.test(", 0));
    }
}
