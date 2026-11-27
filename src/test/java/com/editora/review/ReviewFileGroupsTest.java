package com.editora.review;

import java.util.List;

import org.junit.jupiter.api.Test;

import static com.editora.review.ReviewFileGroups.Group.CONFIG;
import static com.editora.review.ReviewFileGroups.Group.DOCS;
import static com.editora.review.ReviewFileGroups.Group.GENERATED;
import static com.editora.review.ReviewFileGroups.Group.SOURCE;
import static com.editora.review.ReviewFileGroups.Group.TESTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ReviewFileGroups}: what kind of file a path is, the review order, and the walkthrough prompt. */
class ReviewFileGroupsTest {

    @Test
    void eachPathHasItsKind() {
        assertEquals(SOURCE, ReviewFileGroups.classify("src/main/java/com/x/Foo.java"));
        assertEquals(TESTS, ReviewFileGroups.classify("src/test/java/com/x/FooTest.java"));
        assertEquals(TESTS, ReviewFileGroups.classify("web/app.spec.ts"));
        assertEquals(TESTS, ReviewFileGroups.classify("pkg/thing_test.go"));
        assertEquals(TESTS, ReviewFileGroups.classify("tests/test_parse.py"));
        assertEquals(CONFIG, ReviewFileGroups.classify("pom.xml"));
        assertEquals(CONFIG, ReviewFileGroups.classify(".github/workflows/ci.yml"));
        assertEquals(CONFIG, ReviewFileGroups.classify("src/main/resources/app.properties"));
        assertEquals(DOCS, ReviewFileGroups.classify("README.md"));
        assertEquals(DOCS, ReviewFileGroups.classify("docs\\guide\\intro.txt"), "a Windows path too");
        assertEquals(GENERATED, ReviewFileGroups.classify("package-lock.json"));
        assertEquals(GENERATED, ReviewFileGroups.classify("web/dist/app.min.js"));
        assertEquals(GENERATED, ReviewFileGroups.classify("go.sum"));
        assertEquals(SOURCE, ReviewFileGroups.classify("src/Contest.java"), "a name with 'test' inside is source");
    }

    @Test
    void theOrderIsByKindThenAsTheyCame() {
        List<String> paths = List.of("yarn.lock", "b/BTest.java", "README.md", "a/A.java", "pom.xml", "c/C.java");
        assertEquals(List.of(3, 5, 1, 4, 2, 0), ReviewFileGroups.order(paths));
    }

    @Test
    void theWalkthroughAsksForASummaryRiskAndOrderAndSaysWhatWasLeftOut() {
        String p = ReviewFileGroups.walkthroughPrompt(List.of("--- a\n+++ b\n@@ -1 +1 @@\n-x\n+y\n"), 2);
        assertTrue(p.startsWith("Walk me through this change set"), p);
        assertTrue(p.contains("```diff\n--- a\n"), p);
        assertTrue(p.contains("Do not change any file."), p);
        assertTrue(p.contains("2 more file(s) left out"), p);
    }
}
