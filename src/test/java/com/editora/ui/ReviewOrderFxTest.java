package com.editora.ui;

import java.util.List;

import com.editora.diff.DiffEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A review tab shows its files by kind, and the walkthrough prompt carries each file's diff in that order. */
@Tag("fx")
class ReviewOrderFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static PatchReviewPane.Entry entry(String path, String left, String right) {
        var model = DiffEngine.compute(left, right, DiffEngine.DiffOptions.DEFAULT);
        return new PatchReviewPane.Entry(
                path,
                "M",
                1,
                1,
                new DiffViewerPane(
                        path, "L", "R", "a/" + path, "b/" + path, left, right, model, "Monospaced", 13, true, path));
    }

    @Test
    void sourceComesFirstAndTheWalkthroughFollowsTheOrder() throws Exception {
        PatchReviewPane review = FxTestSupport.callOnFx(() -> new PatchReviewPane(
                "Review",
                List.of(
                        entry("package-lock.json", "{}\n", "{\"a\":1}\n"),
                        entry("src/test/FooTest.java", "t\n", "t2\n"),
                        entry("src/Foo.java", "x\n", "y\n"))));
        assertEquals(
                List.of("src/Foo.java", "src/test/FooTest.java", "package-lock.json"),
                review.entries().stream().map(PatchReviewPane.Entry::label).toList());
        String prompt = FxTestSupport.callOnFx(() -> ReviewWalkthrough.prompt(
                review.panes().stream().map(DiffViewerPane::patchRequest).toList()));
        assertTrue(prompt.indexOf("b/src/Foo.java") < prompt.indexOf("b/src/test/FooTest.java"), prompt);
        assertTrue(prompt.contains("-x\n+y"), prompt);
    }
}
