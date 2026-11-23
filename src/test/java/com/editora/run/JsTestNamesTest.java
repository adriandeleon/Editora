package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsTestNamesTest {

    private static final String FILE = """
            import { describe, it, expect } from "vitest";

            describe("math (basic)", () => {
              it("adds", () => {
                expect(1 + 1).toBe(2); // ")" in a comment
              });
              describe.only('nested', () => {
                test.concurrent(`deep`, async () => {});
              });
            });

            test("top level", () => {});
            test.each([[1], [2]])("row %i", (n) => {});
            // it("commented", () => {});
            Deno.test("not jest", () => {});
            """;

    @Test
    void aTestIsNamedWithItsSuites() {
        assertEquals(new JsTestNames.Target(List.of("math (basic)", "adds"), false), JsTestNames.at(FILE, 4));
        assertEquals(new JsTestNames.Target(List.of("math (basic)", "nested", "deep"), false), JsTestNames.at(FILE, 7));
        assertEquals(new JsTestNames.Target(List.of("top level"), false), JsTestNames.at(FILE, 11));
    }

    @Test
    void aSuiteAtTheCaretRunsWhole() {
        assertEquals(new JsTestNames.Target(List.of("math (basic)"), true), JsTestNames.at(FILE, 2));
        assertEquals(new JsTestNames.Target(List.of("math (basic)", "nested"), true), JsTestNames.at(FILE, 6));
    }

    @Test
    void noLiteralNameOrNoTestNamesNothing() {
        assertNull(JsTestNames.at(FILE, 12), "an each table's row names come from the runner");
        assertNull(JsTestNames.at(FILE, 0), "outside every test");
        assertNull(JsTestNames.at(FILE, 13), "commented out");
        assertNull(JsTestNames.at(FILE, 14), "Deno.test is Deno's");
    }
}
