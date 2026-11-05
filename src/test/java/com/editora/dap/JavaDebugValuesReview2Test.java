package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java debugging review 2, the values and hot code replace findings: lazy values (B101), a replace with obsolete
 * frames (B103), evaluation errors (B106), lambda values (B108) and Mute muting exception stops (B109).
 */
class JavaDebugValuesReview2Test {

    private static DapModels.VariableInfo lazy(String name, String value, String type, int ref) {
        return new DapModels.VariableInfo(name, value, type, ref, 0, 0, true);
    }

    // --- B101: lazy values ---------------------------------------------------------------------------------

    @Test
    void aRecordShowsItsToStringWithItsFieldsDirectlyBelow() {
        DapModels.VariableInfo p = lazy("p", "Main$Point@17", "Main$Point", 7);
        DapModels.VariableInfo child =
                new DapModels.VariableInfo("", "Main$Point@17 \"Point[x=3, y=4]\"", "", 31, 2, 0);
        DapModels.VariableInfo shown = LazyValues.resolved(p, child);
        assertEquals("p", shown.name());
        assertEquals("Main$Point@17 \"Point[x=3, y=4]\"", shown.value());
        assertEquals("Main$Point", shown.type());
        assertEquals(31, shown.variablesReference(), "the fields come from the child, one level up");
        assertEquals(2, shown.namedVariables());
        assertFalse(shown.lazy());
    }

    @Test
    void aBoxedPrimitiveShowsItsBareValueAndHasNothingToExpand() {
        DapModels.VariableInfo n = lazy("0", "Integer@62", "Integer", 9);
        DapModels.VariableInfo shown =
                LazyValues.resolved(n, new DapModels.VariableInfo("", "Integer@62 \"0\"", "", 40));
        assertEquals("0", shown.value());
        assertEquals(0, shown.variablesReference());
        assertEquals("42", LazyValues.boxedValue("java.lang.Long", "Long@3 \"42\""));
        assertEquals("'x'", LazyValues.boxedValue("Character", "Character@4 \"x\""));
        assertEquals("true", LazyValues.boxedValue("Boolean", "Boolean@5 \"true\""));
        assertNull(LazyValues.boxedValue("Main$Color", "Main$Color@18 \"GREEN\""), "an enum keeps its id and fields");
        assertNull(LazyValues.boxedValue("Integer", "Integer@62"), "not resolved: nothing to unwrap");
    }

    @Test
    void lazyRowsAreResolvedOnceEachPerStopAndOthersPassThrough() {
        LazyValues values = new LazyValues();
        List<Integer> asked = new ArrayList<>();
        Map<Integer, List<DapModels.VariableInfo>> children = Map.of(
                7, List.of(new DapModels.VariableInfo("", "LocalDate@7 \"2026-11-04\"", "", 70)),
                8, List.of(new DapModels.VariableInfo("", "a", "", 0), new DapModels.VariableInfo("", "b", "", 0)));
        java.util.function.IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> fetch = ref -> {
            asked.add(ref);
            return CompletableFuture.completedFuture(children.getOrDefault(ref, List.of()));
        };
        DapModels.VariableInfo plain = new DapModels.VariableInfo("n", "3", "int", 0);
        DapModels.VariableInfo date = lazy("d", "LocalDate@7", "LocalDate", 7);
        DapModels.VariableInfo odd = lazy("o", "Odd@8", "Odd", 8);

        List<DapModels.VariableInfo> first =
                values.resolveAll(List.of(plain, date, odd), fetch).join();
        assertSame(plain, first.get(0));
        assertEquals("LocalDate@7 \"2026-11-04\"", first.get(1).value());
        assertSame(odd, first.get(2), "not exactly one child: left as it came");
        values.resolveAll(List.of(date), fetch).join();
        assertEquals(List.of(7, 8), asked, "the date was resolved once; the cache answered the second time");

        values.clear(); // the program ran
        values.resolveAll(List.of(date), fetch).join();
        assertEquals(List.of(7, 8, 7), asked);

        CompletableFuture<List<DapModels.VariableInfo>> failing =
                CompletableFuture.failedFuture(new RuntimeException());
        DapModels.VariableInfo unanswered = lazy("u", "Thing@99", "Thing", 99);
        assertSame(
                unanswered,
                values.resolveAll(List.of(unanswered), r -> failing).join().get(0),
                "a failed fetch leaves the row as it came");
    }

    // --- B103: a replace with obsolete frames is a replace that worked ---------------------------------------

    @Test
    void aReplaceWithObsoleteMethodsIsAppliedAndAnyOtherErrorStillFails() {
        String obsolete = "JVM contains obsolete methods";
        DapManager.CodeReplaceOutcome withClasses =
                DapManager.codeReplaceOutcome(new DapModels.CodeReplace(List.of("Main"), obsolete), null);
        assertEquals(DapManager.CodeReplaceResult.APPLIED, withClasses.result());
        assertEquals(obsolete, withClasses.message(), "the note is kept for the report");
        assertEquals(
                DapManager.CodeReplaceResult.APPLIED,
                DapManager.codeReplaceOutcome(new DapModels.CodeReplace(List.of(), obsolete), null)
                        .result(),
                "java-debug names no classes with this warning");
        assertEquals(
                DapManager.CodeReplaceResult.FAILED,
                DapManager.codeReplaceOutcome(
                                new DapModels.CodeReplace(List.of(), "Scheme change not implemented"), null)
                        .result());
        assertTrue(new DapModels.CodeReplace(List.of(), "JVM contains Obsolete Methods").obsoleteMethods());
        assertFalse(new DapModels.CodeReplace(List.of(), "").obsoleteMethods());
    }

    // --- B106: evaluation errors ------------------------------------------------------------------------------

    @Test
    void anEvaluationThatThrowsSaysWhatItThrewWithoutEclipseClassNames() {
        assertEquals(
                tr("debug.eval.threwIn", "NullPointerException", "length()"),
                DapErrors.userText("Cannot evaluate because of org.eclipse.debug.core.DebugException: "
                        + "java.lang.NullPointerException at length()."));
        assertEquals(
                tr("debug.eval.threw", "IllegalStateException"),
                DapErrors.userText("Cannot evaluate because of org.eclipse.debug.core.DebugException: "
                        + "java.lang.IllegalStateException"));
        assertEquals(
                tr("debug.eval.cannot", "generated value is not a String"),
                DapErrors.userText("Cannot evaluate because of org.eclipse.debug.core.DebugException: "
                        + "com.sun.jdi.InvalidTypeException: generated value is not a String"));
        String compile = "Cannot evaluate because of compilation error(s): x cannot be resolved to a variable.";
        assertEquals(compile, DapErrors.userText(compile), "already plain");
    }

    // --- B108: lambdas ------------------------------------------------------------------------------------------

    @Test
    void aLambdaReadsLambdaNotItsHiddenClassAddress() {
        DapModels.VariableInfo lam =
                JavaValueText.tidy(new DapModels.VariableInfo("lam", "0x0000000036041000@19", "0x0000000036041000", 5));
        assertEquals("lambda@19", lam.value());
        assertEquals("lambda", lam.type());
        assertEquals(5, lam.variablesReference());
        DapModels.VariableInfo hex = new DapModels.VariableInfo("h", "0x1f", "int", 0);
        assertSame(hex, JavaValueText.tidy(hex), "a value that only looks like hex is left alone");
        assertEquals(
                "lambda@3",
                JavaValueText.tidy(new DapModels.EvalResult("0xabc@3", 3, "0xabc"))
                        .result());
        DapModels.EvalResult failed = DapModels.EvalResult.failure("0x1@2");
        assertSame(failed, JavaValueText.tidy(failed));
    }

    // --- B109: Mute mutes exception stops ----------------------------------------------------------------------

    @Test
    void muteSendsNoExceptionFiltersAndKeepsTheChoiceForAfter() throws Exception {
        DapManager dap = new DapManager(null);
        dap.setExceptionFilters("java", List.of("caught", "uncaught"));
        java.lang.reflect.Method filtersFor = DapManager.class.getDeclaredMethod("filtersFor", String.class);
        filtersFor.setAccessible(true);
        assertEquals(List.of("caught", "uncaught"), filtersFor.invoke(dap, "java"));
        dap.setExceptionsMuted(true);
        assertEquals(List.of(), filtersFor.invoke(dap, "java"), "muted: no exception stops");
        dap.setExceptionsMuted(false);
        assertEquals(List.of("caught", "uncaught"), filtersFor.invoke(dap, "java"), "the choice is back");
        assertNull(filtersFor.invoke(dap, "python"), "nothing chosen: the adapter's defaults");
    }
}
