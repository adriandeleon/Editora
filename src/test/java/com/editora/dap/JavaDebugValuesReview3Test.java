package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java debugging review 3, values: the toString() budget (B201), toString() after Pause (B202), one request per
 * object and per Locals (B207), boxed evaluation results (B206), Set Value on an object (B208) and the Show toString()
 * values setting.
 */
class JavaDebugValuesReview3Test {

    private static DapModels.VariableInfo lazy(String name, String value, int ref) {
        return new DapModels.VariableInfo(name, value, value.substring(0, value.indexOf('@')), ref, 0, 0, true);
    }

    private static List<DapModels.VariableInfo> child(String value, int ref) {
        return List.of(new DapModels.VariableInfo("", value, "", ref));
    }

    // --- B201: the budget ------------------------------------------------------------------------------------

    @Test
    void aToStringThatDoesNotReturnLeavesItsRowForAClickAndTheOthersStillShow() {
        AtomicLong now = new AtomicLong();
        LazyValues values = new LazyValues(now::get);
        long almostAll = TimeUnit.MILLISECONDS.toNanos(LazyValues.BUDGET_MILLIS) - TimeUnit.MILLISECONDS.toNanos(20);
        List<Integer> asked = new ArrayList<>();
        IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> fetch = ref -> {
            asked.add(ref);
            if (ref == 1) {
                now.addAndGet(almostAll); // it runs, and runs: the budget's 1.5 s go by
                return new CompletableFuture<>(); // never answers
            }
            return CompletableFuture.completedFuture(child("Point@" + ref + " \"p" + ref + "\"", 100 + ref));
        };
        DapModels.VariableInfo loops = lazy("loops", "Loops@1", 1);
        List<DapModels.VariableInfo> shown = values.resolveAll(
                        List.of(loops, lazy("a", "Point@2", 2), lazy("b", "Point@3", 3)), fetch)
                .orTimeout(5, TimeUnit.SECONDS)
                .join();

        assertEquals(DapModels.ValueNote.NOT_LOADED, shown.get(0).note(), "past the budget: click to load");
        assertTrue(shown.get(0).lazy());
        assertEquals(1, shown.get(0).variablesReference(), "the click asks for the same child");
        assertEquals("Point@2 \"p2\"", shown.get(1).value(), "the rows that answered show their value");
        assertEquals("Point@3 \"p3\"", shown.get(2).value());

        assertNull(values.stuck(), "not yet over the budget since it was asked");
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(100));
        assertEquals("Loops@1", values.stuck(), "a step now would wait behind it: say so");

        // The budget is spent: a row expanded later is not asked for, until a click.
        DapModels.VariableInfo later = lazy("c", "Point@4", 4);
        assertEquals(
                DapModels.ValueNote.NOT_LOADED,
                values.resolveAll(List.of(later), fetch).join().get(0).note());
        assertEquals(List.of(1, 2, 3), asked);
        assertEquals("Point@4 \"p4\"", values.resolveNow(later, fetch).join().value(), "a click loads it");

        values.newStop(false);
        assertEquals("Loops@1", values.stuck(), "the adapter is still busy with it at the next stop");
        values.reset();
        assertNull(values.stuck(), "a new session starts clean");
    }

    @Test
    void theBudgetCountsOnlyTimeWithAToStringOutstanding() {
        AtomicLong now = new AtomicLong();
        LazyValues values = new LazyValues(now::get);
        IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> fetch =
                ref -> CompletableFuture.completedFuture(child("Point@" + ref + " \"p\"", 0));
        values.resolveAll(List.of(lazy("a", "Point@1", 1)), fetch).join();
        now.addAndGet(TimeUnit.SECONDS.toNanos(30)); // the user looks at the stop for half a minute
        assertEquals(
                "Point@2 \"p\"",
                values.resolveAll(List.of(lazy("b", "Point@2", 2)), fetch)
                        .join()
                        .get(0)
                        .value(),
                "expanding a list later still shows its values");
    }

    // --- B207: one request per object, one per Locals ------------------------------------------------------------

    @Test
    void theTreeAndTheInlineValuesShareOneAnswerPerObject() {
        LazyValues values = new LazyValues();
        AtomicInteger asked = new AtomicInteger();
        CompletableFuture<List<DapModels.VariableInfo>> slow = new CompletableFuture<>();
        IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> fetch = ref -> {
            asked.incrementAndGet();
            return slow;
        };
        // java-debug hands out a fresh reference with every reply: the same object comes as 7, then 19.
        CompletableFuture<List<DapModels.VariableInfo>> tree =
                values.resolveAll(List.of(lazy("p", "Point@5", 7)), fetch);
        CompletableFuture<List<DapModels.VariableInfo>> inline =
                values.resolveAll(List.of(lazy("p", "Point@5", 19)), fetch);
        slow.complete(child("Point@5 \"Point[x=3, y=4]\"", 31));
        assertEquals("Point@5 \"Point[x=3, y=4]\"", tree.join().get(0).value());
        assertEquals("Point@5 \"Point[x=3, y=4]\"", inline.join().get(0).value());
        assertEquals(1, asked.get(), "toString() ran once for both");

        values.clear();
        values.resolveAll(List.of(lazy("p", "Point@5", 40)), r -> {
                    asked.incrementAndGet();
                    return CompletableFuture.completedFuture(child("Point@5 \"x\"", 0));
                })
                .join();
        assertEquals(2, asked.get(), "the program ran (or a console evaluation): asked again");

        AtomicInteger sent = new AtomicInteger();
        values.reply("8/null/0/0", () -> {
            sent.incrementAndGet();
            return CompletableFuture.completedFuture(List.of());
        });
        values.reply("8/null/0/0", () -> {
            sent.incrementAndGet();
            return CompletableFuture.completedFuture(List.of());
        });
        assertEquals(1, sent.get(), "Locals fetched once for the tree and the inline values");
    }

    @Test
    void oneObjectUnderTwoNamesKeepsBothNames() {
        LazyValues values = new LazyValues();
        IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> fetch =
                ref -> CompletableFuture.completedFuture(child("Point@5 \"p\"", 31));
        List<DapModels.VariableInfo> shown = values.resolveAll(
                        List.of(lazy("p", "Point@5", 7), lazy("q", "Point@5", 8)), fetch)
                .join();
        assertEquals("p", shown.get(0).name());
        assertEquals("q", shown.get(1).name());
        assertEquals("Point@5 \"p\"", shown.get(1).value());
    }

    @Test
    void manyRowsAnsweredAtOnceDoNotOverflowTheStack() {
        LazyValues values = new LazyValues();
        List<DapModels.VariableInfo> rows = new ArrayList<>();
        for (int i = 1; i <= 20_000; i++) {
            rows.add(lazy("e" + i, "Point@" + i, i));
        }
        List<DapModels.VariableInfo> shown = values.resolveAll(
                        rows, ref -> CompletableFuture.completedFuture(child("Point@" + ref + " \"v\"", 0)))
                .join();
        assertEquals(20_000, shown.size());
        assertEquals("Point@20000 \"v\"", shown.get(19_999).value());
    }

    // --- B202: no toString() after Pause ------------------------------------------------------------------------

    @Test
    void afterPauseAnObjectKeepsTypeAtIdWithANoteNotNull() {
        DapModels.VariableInfo boxed = lazy("boxed", "Integer@13", 9);
        DapModels.VariableInfo paused =
                LazyValues.resolved(boxed, new DapModels.VariableInfo("", "Integer@13 null", "", 40, 1, 0), true);
        assertEquals("Integer@13", paused.value(), "not \"Integer@13 null\"");
        assertEquals(DapModels.ValueNote.NO_TO_STRING_AFTER_PAUSE, paused.note());
        assertEquals(40, paused.variablesReference(), "its fields (value = 42) are still one click away");
        assertFalse(paused.lazy());

        DapModels.VariableInfo thrown = LazyValues.resolved(
                lazy("thr", "Main$Throws@31", 3), new DapModels.VariableInfo("", "Main$Throws@31 null", "", 41), false);
        assertEquals("Main$Throws@31", thrown.value());
        assertEquals(DapModels.ValueNote.TO_STRING_FAILED, thrown.note());

        assertFalse(LazyValues.noToString("Point@1", "Point@1 \"null\""), "a toString() that returned \"null\"");
        assertEquals(
                "42",
                LazyValues.resolved(boxed, new DapModels.VariableInfo("", "Integer@13 \"42\"", "", 40))
                        .value());
    }

    @Test
    void aMissingToStringIsAskedAgainNotRemembered() {
        LazyValues values = new LazyValues();
        values.newStop(true);
        AtomicInteger asked = new AtomicInteger();
        IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> fetch = ref -> {
            asked.incrementAndGet();
            return CompletableFuture.completedFuture(child("Point@5 null", 31));
        };
        DapModels.VariableInfo first = values.resolveAll(List.of(lazy("p", "Point@5", 7)), fetch)
                .join()
                .get(0);
        assertEquals(DapModels.ValueNote.NO_TO_STRING_AFTER_PAUSE, first.note());
        values.resolveAll(List.of(lazy("p", "Point@5", 8)), fetch).join();
        assertEquals(2, asked.get());
    }

    // --- B206: boxed evaluation results ------------------------------------------------------------------------

    @Test
    void anEvaluatedBoxedNumberReadsItsValueWithNothingToExpand() {
        DapModels.EvalResult watch =
                JavaValueText.tidy(new DapModels.EvalResult("Integer@17 \"42\"", 23, "java.lang.Integer"));
        assertEquals("42", watch.result());
        assertEquals(0, watch.variablesReference(), "no tree of BYTES, digits, MAX_VALUE…");
        DapModels.EvalResult record = new DapModels.EvalResult("Main$Point@20 \"Point[x=3, y=4]\"", 24, "Main$Point");
        assertSame(record, JavaValueText.tidy(record), "a record keeps its fields");
        DapModels.EvalResult failed = DapModels.EvalResult.failure("Integer@17 \"42\"");
        assertSame(failed, JavaValueText.tidy(failed));
    }

    // --- B208: Set Value on an object ------------------------------------------------------------------------------

    @Test
    void setValueOnAnObjectTypeBecomesAnAssignment() {
        assertTrue(SetValueText.unsupportedType(
                "Failed to set variable. Reason: Set value is not supported yet for " + "type java.lang.Integer."));
        assertFalse(SetValueText.unsupportedType("Failed to set variable. Reason: not a number"));
        assertFalse(SetValueText.unsupportedType(null));
        assertEquals("boxed = 44", SetValueText.assignment("boxed", " 44 "));
        assertEquals("p.col = Main.Color.RED", SetValueText.assignment("p.col", "Main.Color.RED"));
    }

    // --- the Show toString() values setting ------------------------------------------------------------------

    @Test
    void theToStringSwitchReachesJavaDebug() {
        assertTrue(JavaDebugSettings.defaults().showToString());
        assertTrue(JavaDebugSettings.defaults().toJson().contains("\"showToString\":true"));
        String off = new JavaDebugSettings(true, true, List.of(), false).toJson();
        assertTrue(off.contains("\"showToString\":false"), off);
        assertTrue(new JavaDebugSettings(true, false, List.of()).showToString(), "the old constructor keeps it on");
    }
}
