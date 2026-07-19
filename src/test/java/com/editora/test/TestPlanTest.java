package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seeding scanned targets into pending (RUNNING) suites: plain @Test only, grouped by class. */
class TestPlanTest {

    @Test
    void seedsPlainTestsGroupedByClassAsRunning() {
        List<ParsedSuite> suites = TestPlan.seed(List.of(
                new JavaTestScanner.TestTarget(0, "com.x.FooTest", null, false), // class target — ignored
                new JavaTestScanner.TestTarget(2, "com.x.FooTest", "a", false),
                new JavaTestScanner.TestTarget(5, "com.x.FooTest", "param", true), // parameterized — skipped
                new JavaTestScanner.TestTarget(1, "com.x.BarTest", "b", false)));
        assertEquals(2, suites.size());
        ParsedSuite foo = suites.stream()
                .filter(s -> s.suiteName().equals("com.x.FooTest"))
                .findFirst()
                .orElseThrow();
        assertEquals(1, foo.tests().size()); // only the plain @Test "a" (param dropped)
        ParsedTest a = foo.tests().get(0);
        assertEquals("a", a.methodName());
        assertEquals(TestStatus.RUNNING, a.status());
        assertEquals("com.x.FooTest", a.className());
    }

    @Test
    void noSeedableMethodsYieldsEmpty() {
        assertTrue(TestPlan.seed(List.of(
                        new JavaTestScanner.TestTarget(0, "T", null, false),
                        new JavaTestScanner.TestTarget(1, "T", "p", true)))
                .isEmpty());
        assertTrue(TestPlan.seed(List.of()).isEmpty());
    }
}
