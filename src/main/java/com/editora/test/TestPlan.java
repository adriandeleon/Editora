package com.editora.test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a project scan's {@link JavaTestScanner.TestTarget}s into pending {@link ParsedSuite}s (all tests
 * {@code RUNNING}) so the Test Results tree can show the full expected list greyed-out up front, IntelliJ-style,
 * before any result arrives. Pure.
 *
 * <p>Only <b>plain {@code @Test}</b> methods are seeded: a parameterized-family method's runtime case id
 * ({@code foo(int)[1]}) doesn't match its method name, so seeding {@code foo} would leave a grey ghost — those
 * appear when they actually run. A class with no seedable method contributes nothing (it pops in on its report).
 */
public final class TestPlan {

    private TestPlan() {}

    public static List<ParsedSuite> seed(Collection<JavaTestScanner.TestTarget> targets) {
        Map<String, List<ParsedTest>> byClass = new LinkedHashMap<>();
        for (JavaTestScanner.TestTarget t : targets) {
            if (t.methodName() == null || t.dynamic()) {
                continue; // class-level target / parameterized family — not a stable-id leaf to pre-seed
            }
            byClass.computeIfAbsent(t.className(), k -> new ArrayList<>())
                    .add(ParsedTest.of(t.className(), t.methodName(), TestStatus.RUNNING, 0));
        }
        List<ParsedSuite> suites = new ArrayList<>(byClass.size());
        for (Map.Entry<String, List<ParsedTest>> e : byClass.entrySet()) {
            suites.add(new ParsedSuite(e.getKey(), e.getValue()));
        }
        return suites;
    }
}
