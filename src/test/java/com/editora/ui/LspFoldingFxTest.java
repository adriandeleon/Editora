package com.editora.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.editor.FoldManager;
import com.editora.editor.FoldRegions;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FoldManager}'s server-region override (#738) — where a language server's grammar-accurate folding
 * replaces the brace/indent heuristic, and where it must fall back rather than leave a file unfoldable.
 *
 * <p>An FX test rather than a pure one because {@code FoldManager} is built over a live {@code CodeArea};
 * the mapping from an LSP response to regions is unit-tested separately in {@code LspFoldingTest}.
 */
@Tag("fx")
class LspFoldingFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final String JAVA = """
            class A {
              void f() {
                int x = 1;
              }
            }
            """;

    private static FoldManager managerOver(String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = new CodeArea(text);
            FoldManager folds = new FoldManager(area);
            folds.setLanguage("java");
            return folds;
        });
    }

    @Test
    void withoutServerRegionsTheHeuristicIsUsed() throws Exception {
        FoldManager folds = managerOver(JAVA);

        List<FoldRegions.Region> regions = FxTestSupport.callOnFx(folds::regions);

        assertEquals(FoldRegions.detect(JAVA, "java"), regions);
        assertTrue(regions.size() >= 2, "the class body and the method body both fold");
    }

    /**
     * The point of the feature: a server knows things brace scanning cannot — here an import block, which
     * has no delimiters at all and so produces no heuristic region.
     */
    @Test
    void serverRegionsReplaceTheHeuristic() throws Exception {
        FoldManager folds = managerOver(JAVA);
        List<FoldRegions.Region> fromServer = List.of(new FoldRegions.Region(0, 4));

        FxTestSupport.runOnFx(() -> folds.setServerRegions(fromServer));

        assertEquals(fromServer, FxTestSupport.callOnFx(folds::regions));
        assertNotEquals(FoldRegions.detect(JAVA, "java"), fromServer, "the two models really do differ here");
    }

    /**
     * Falling back matters more than overriding: LSP is off by default, a server may have no folding
     * provider, and there is a window between opening a file and its server reporting ready. Any of those
     * leaving the file unfoldable would be a visible regression for every non-LSP user.
     */
    @Test
    void anEmptyOrClearedAnswerRestoresTheHeuristic() throws Exception {
        FoldManager folds = managerOver(JAVA);
        List<FoldRegions.Region> heuristic = FxTestSupport.callOnFx(folds::regions);

        FxTestSupport.runOnFx(() -> folds.setServerRegions(List.of(new FoldRegions.Region(0, 4))));
        FxTestSupport.runOnFx(() -> folds.setServerRegions(List.of()));
        assertEquals(heuristic, FxTestSupport.callOnFx(folds::regions), "empty list");

        FxTestSupport.runOnFx(() -> folds.setServerRegions(List.of(new FoldRegions.Region(0, 4))));
        FxTestSupport.runOnFx(() -> folds.setServerRegions(null));
        assertEquals(heuristic, FxTestSupport.callOnFx(folds::regions), "null");
    }

    /**
     * The request rides the debounced document pulse and a server re-reports identical regions for any edit
     * that doesn't move a block boundary — the common case. Recomputing anyway would rebuild fold gutter
     * graphics on every settle, so an unchanged answer must be a no-op.
     */
    @Test
    void anUnchangedAnswerDoesNotRecompute() throws Exception {
        FoldManager folds = managerOver(JAVA);
        AtomicInteger recomputes = new AtomicInteger();
        List<FoldRegions.Region> answer = List.of(new FoldRegions.Region(0, 4));

        FxTestSupport.runOnFx(() -> {
            folds.setOnRegionsChanged(recomputes::incrementAndGet);
            folds.setServerRegions(answer);
        });
        int afterFirst = recomputes.get();
        assertTrue(afterFirst > 0, "guard: the first install must recompute, or the assertion below is vacuous");
        FxTestSupport.runOnFx(() -> folds.setServerRegions(List.copyOf(answer)));

        assertEquals(afterFirst, recomputes.get(), "an equal-but-not-same list must not recompute");
    }
}
