package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;

/** Toolkit-free grouping for the side-by-side diff's visual change connectors and overview markers. */
final class DiffConnectorModel {

    private DiffConnectorModel() {}

    enum Kind {
        ADDED,
        REMOVED,
        MODIFIED
    }

    /** A contiguous changed range in displayed-row coordinates; {@code endRow} is exclusive. */
    record Band(int startRow, int endRow, Kind kind) {}

    static List<Band> bands(List<Row> rows) {
        List<Band> result = new ArrayList<>();
        int start = 0;
        while (start < rows.size()) {
            if (rows.get(start).type() == RowType.EQUAL) {
                start++;
                continue;
            }
            int end = start;
            boolean hasLeft = false;
            boolean hasRight = false;
            while (end < rows.size() && rows.get(end).type() != RowType.EQUAL) {
                Row row = rows.get(end++);
                hasLeft |= row.left() != null;
                hasRight |= row.right() != null;
            }
            Kind kind = hasLeft && hasRight ? Kind.MODIFIED : hasRight ? Kind.ADDED : Kind.REMOVED;
            result.add(new Band(start, end, kind));
            start = end;
        }
        return List.copyOf(result);
    }
}
