package com.editora.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.stage.Stage;

import com.editora.dap.DapModels;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Load More after the program moved on reads the table again from the first rows, not after the old stop's. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugTableStaleFxTest {

    private static final int TOTAL = ValueTable.MAX_ROWS + 2;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A page of a 1-D array whose values say which stop they were read at. */
    private static String page(int stop, int start, int count) {
        StringBuilder rows = new StringBuilder();
        StringBuilder index = new StringBuilder();
        for (int i = start; i < start + count; i++) {
            rows.append(i > start ? ", " : "")
                    .append("[\"s")
                    .append(stop)
                    .append("-")
                    .append(i)
                    .append("\"]");
            index.append(i > start ? ", " : "").append('"').append(i).append('"');
        }
        return PythonDataTable.pythonString("{\"kind\": \"ndarray\", \"shape\": [" + TOTAL + "], \"leaf\": true, "
                + "\"columns\": [\"\"], \"dtypes\": [\"int64\"], \"index\": [" + index + "], \"rows\": [" + rows
                + "], \"total\": " + TOTAL + ", \"width\": 1}");
    }

    private static final class Program implements DebugTableWindow.Actions {
        int stop = 1;
        final List<String> asked = new ArrayList<>();

        @Override
        public void children(
                int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
            cb.accept(List.of());
        }

        @Override
        public void openText(String title, String text, String language) {}

        @Override
        public void evaluate(String expression, Consumer<DapModels.@Nullable EvalResult> cb) {
            cb.accept(null);
        }

        @Override
        public boolean canRefresh() {
            return true;
        }

        @Override
        public void evaluateData(String expression, Consumer<DapModels.@Nullable EvalResult> cb) {
            asked.add(expression);
            int start = expression.equals(PythonDataTable.expression("a", 0, ValueTable.MAX_ROWS))
                    ? 0
                    : ValueTable.MAX_ROWS;
            int count = Math.min(ValueTable.MAX_ROWS, TOTAL - start);
            cb.accept(new DapModels.EvalResult(page(stop, start, count), 0, null));
        }
    }

    private static <T> T await(String what, java.util.concurrent.Callable<T> value) throws InterruptedException {
        return FxTestSupport.awaitFxValue(value, what, Duration.ofSeconds(10));
    }

    @Test
    void loadMoreAfterANewStopStartsAgainFromTheFirstRows() throws Exception {
        Program program = new Program();
        DebugTableWindow w = FxTestSupport.callOnFx(() -> {
            Stage owner = new Stage();
            DebugTableWindow table = new DebugTableWindow(program);
            table.showData(owner, "a", "a");
            return table;
        });
        try {
            await("the first page", () -> w.table().getItems().size() == ValueTable.MAX_ROWS ? true : null);
            assertEquals(
                    "s1-0",
                    FxTestSupport.callOnFx(() -> w.table().getItems().getFirst().cell(ValueTable.VALUE_COLUMN)));

            // The program goes on and stops again: the rows shown are the old stop's.
            FxTestSupport.runOnFx(() -> {
                program.stop = 2;
                w.sessionChanged();
            });
            assertTrue(FxTestSupport.callOnFx(() -> w.note().getText().endsWith(tr("valuetable.dataStale"))));

            FxTestSupport.runOnFx(w.moreButton()::fire);
            await(
                    "the table read again at the new stop",
                    () -> !w.table().getItems().isEmpty()
                                    && "s2-0"
                                            .equals(w.table()
                                                    .getItems()
                                                    .getFirst()
                                                    .cell(ValueTable.VALUE_COLUMN))
                            ? true
                            : null);
            assertEquals(
                    PythonDataTable.expression("a", 0, ValueTable.MAX_ROWS),
                    program.asked.getLast(),
                    "from the first rows, not after the old stop's");
            assertEquals(
                    ValueTable.MAX_ROWS,
                    FxTestSupport.callOnFx(() -> w.table().getItems().size()));
            assertEquals(
                    tr("valuetable.dataFirstOf", ValueTable.MAX_ROWS, TOTAL),
                    FxTestSupport.callOnFx(() -> w.note().getText()));

            // At the same stop, Load More goes on after the rows shown.
            FxTestSupport.runOnFx(w.moreButton()::fire);
            await("every row", () -> w.table().getItems().size() == TOTAL ? true : null);
            assertEquals(
                    "s2-" + (TOTAL - 1),
                    FxTestSupport.callOnFx(() -> w.table().getItems().getLast().cell(ValueTable.VALUE_COLUMN)));
        } finally {
            FxTestSupport.runOnFx(w::hide);
        }
    }
}
