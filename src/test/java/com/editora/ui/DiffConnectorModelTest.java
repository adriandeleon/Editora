package com.editora.ui;

import java.util.List;

import com.editora.diff.DiffModels.Row;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiffConnectorModelTest {

    @Test
    void groupsContiguousChangesAndClassifiesTheirVisibleSides() {
        List<Row> rows = List.of(
                Row.equal("context", 1, 1),
                Row.removed("gone", 2),
                Row.equal("context", 3, 2),
                Row.added("new", 3),
                Row.added("more", 4),
                Row.equal("context", 4, 5),
                Row.modified("old", 5, "new", 6, null, null),
                Row.removed("also old", 6));

        assertEquals(
                List.of(
                        new DiffConnectorModel.Band(1, 2, DiffConnectorModel.Kind.REMOVED),
                        new DiffConnectorModel.Band(3, 5, DiffConnectorModel.Kind.ADDED),
                        new DiffConnectorModel.Band(6, 8, DiffConnectorModel.Kind.MODIFIED)),
                DiffConnectorModel.bands(rows));
    }

    @Test
    void emptyAndEqualInputsHaveNoBands() {
        assertEquals(List.of(), DiffConnectorModel.bands(List.of()));
        assertEquals(List.of(), DiffConnectorModel.bands(List.of(Row.equal("same", 1, 1))));
    }
}
