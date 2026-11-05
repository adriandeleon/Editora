package com.editora.ui;

import java.util.List;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepTargetOrderTest {

    private static final String LINE = "        int r = add(twice(1), twice(2));";

    private static List<String> labels(List<DapModels.StepTarget> targets) {
        return targets.stream().map(DapModels.StepTarget::label).toList();
    }

    @Test
    void callsAreListedInTheOrderTheyRunWhateverTheAdapterSent() {
        var add = new DapModels.StepTarget(9, "add(twice(1),twice(2))", 7);
        var one = new DapModels.StepTarget(7, "twice(1)", 7);
        var two = new DapModels.StepTarget(8, "twice(2)", 7);
        List<String> expected = List.of("twice(1)", "twice(2)", "add(twice(1),twice(2))");
        assertEquals(expected, labels(StepTargetOrder.sort(List.of(add, one, two), LINE)));
        assertEquals(expected, labels(StepTargetOrder.sort(List.of(two, add, one), LINE)));
    }

    @Test
    void aLabelTheLineDoesNotHoldGoesLast() {
        var odd = new DapModels.StepTarget(1, "lambda$main$0()", 7);
        var one = new DapModels.StepTarget(7, "twice(1)", 7);
        assertEquals(List.of("twice(1)", "lambda$main$0()"), labels(StepTargetOrder.sort(List.of(odd, one), LINE)));
    }

    @Test
    void noLineTextKeepsTheAdapterOrder() {
        var a = new DapModels.StepTarget(1, "b()", 0);
        var b = new DapModels.StepTarget(2, "a()", 0);
        assertEquals(List.of(a, b), StepTargetOrder.sort(List.of(a, b), ""));
    }
}
