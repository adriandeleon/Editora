package com.editora.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a row stands among its column's rows, filters aside, for the Project Map's screen-reader text and its
 * keyboard moves out of a filtered column (perf review 2026-11-23 S1). Each column's order is worked out once per
 * listing, sorting that column alone, and its positions are a map: a column filter's keystroke in a 50,000-row
 * folder used to sort every column of the map and scan the column once per drawn row.
 *
 * <p>Confined to the FX thread, as the surface that owns it.
 */
final class MapColumnOrders {

    /** One column's rows in the order the map shows them, and each row's place in it. */
    static final class Order {

        static final Order EMPTY = new Order(List.of());

        private final List<ProjectMapModel.Entry> rows;
        private final Map<ProjectMapModel.Entry, Integer> index;

        Order(List<ProjectMapModel.Entry> rows) {
            this.rows = List.copyOf(rows);
            this.index = HashMap.newHashMap(rows.size());
            for (int at = 0; at < this.rows.size(); at++) {
                index.putIfAbsent(this.rows.get(at), at);
            }
        }

        /** The column's rows, in order. */
        List<ProjectMapModel.Entry> rows() {
            return rows;
        }

        /** {@code entry}'s place among {@link #rows}, or -1: {@code rows().indexOf(entry)} without the scan. */
        int indexOf(ProjectMapModel.Entry entry) {
            Integer at = index.get(entry);
            return at == null ? -1 : at;
        }

        /**
         * Where {@code entry}, hidden from {@code drawn} by a filter, would stand among the drawn rows, from 1: one
         * more than the drawn rows before it in this order. One lookup per drawn row.
         */
        int positionAmong(List<ProjectMapModel.Entry> drawn, ProjectMapModel.Entry entry) {
            int at = indexOf(entry);
            int before = 0;
            for (ProjectMapModel.Entry row : drawn) {
                if (indexOf(row) < at) {
                    before++;
                }
            }
            return before + 1;
        }
    }

    private List<ProjectMapModel.Entry> listing = List.of();
    private final Map<ProjectMapModel.ColumnId, Order> orders = new HashMap<>();

    /** Columns sorted since this was made (tests: a keystroke sorts nothing). */
    long sorted;

    /** The order of column {@code id} in {@code entries}: kept while the listing is the same list. */
    Order of(List<ProjectMapModel.Entry> entries, ProjectMapModel.ColumnId id) {
        if (entries != listing) {
            listing = entries;
            orders.clear();
        }
        Order known = orders.get(id);
        if (known == null) {
            List<ProjectMapModel.Entry> rows = ProjectMapModel.columnInOrder(entries, id);
            known = rows.isEmpty() ? Order.EMPTY : new Order(rows);
            orders.put(id, known);
            sorted++;
        }
        return known;
    }
}
