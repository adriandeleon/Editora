package com.editora.diff;

import java.util.List;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.UnifiedRow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PatchLineNumbersTest {

    private static final String PATCH = "--- a/f\n+++ b/f\n@@ -500,3 +500,3 @@\n ctx\n-old\n+new\n ctx2\n"
            + "@@ -900,2 +900,3 @@\n far\n+added\n away\n";

    @Test
    void parserRecordsTheLineNumbersTheHunkHeadersState() {
        PatchParser.FilePatch patch = PatchParser.parse(PATCH).get(0);

        assertEquals(List.of(500, 501, 502, 900, 901), patch.oldLineNumbers());
        assertEquals(List.of(500, 501, 502, 900, 901, 902), patch.newLineNumbers());
    }

    @Test
    void rowsOfAPatchDiffCarryTheRealLineNumbers() {
        PatchParser.FilePatch patch = PatchParser.parse(PATCH).get(0);
        DiffModel model = PatchLineNumbers.renumber(
                DiffEngine.compute(patch.oldLines(), patch.newLines()), patch.oldLineNumbers(), patch.newLineNumbers());

        assertEquals(
                List.of(500, 501, 502, 900, -1, 901),
                model.rows().stream().map(Row::leftLine).toList());
        assertEquals(
                List.of(500, 501, 502, 900, 901, 902),
                model.rows().stream().map(Row::rightLine).toList());
        UnifiedRow added = model.unified().stream()
                .filter(row -> "added".equals(row.text()))
                .findFirst()
                .orElseThrow();
        assertEquals(901, added.rightLine());
        assertEquals(-1, added.leftLine());
    }

    @Test
    void aModelWithoutKnownNumbersIsLeftAlone() {
        DiffModel model = DiffEngine.compute(List.of("a"), List.of("b"));

        assertSame(model, PatchLineNumbers.renumber(model, List.of(), List.of()));
    }
}
