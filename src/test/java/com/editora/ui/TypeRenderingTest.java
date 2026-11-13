package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.dap.DapModels;
import com.editora.dap.TypeRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TypeRendering} against an evaluator that answers at once from a table. */
class TypeRenderingTest {

    private final List<TypeRenderer> renderers = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private boolean muted;

    /** Expression → its result; anything else fails. */
    private final Map<String, DapModels.EvalResult> answers = Map.of(
            "(order.total).amount + \" \" + (order.total).currency",
            new DapModels.EvalResult("\"12.50 EUR\"", 0, "String", 0, 0, false),
            "(order.total).amount",
            new DapModels.EvalResult("12.50", 0, "BigDecimal", 0, 0, false),
            "(order.total).owner",
            new DapModels.EvalResult("Owner@3", 44, "Owner", 2, 0, false),
            "((order.total).owner).name",
            new DapModels.EvalResult("\"Ann\"", 0, "String", 0, 0, false));

    private final TypeRendering rendering =
            new TypeRendering(() -> renderers, () -> "java", () -> muted, (e, f, cb) -> {
                asked.add(e);
                DapModels.EvalResult r = answers.get(e);
                cb.accept(r != null ? r : new DapModels.EvalResult("no such field", 0, null, 0, 0, true));
            });

    private static DapModels.VariableInfo money(String evaluateName) {
        return new DapModels.VariableInfo("total", "Money@7", "Money", 21, 2, 0, false, null, evaluateName);
    }

    private List<DapModels.VariableInfo> apply(DapModels.VariableInfo... vars) {
        AtomicReference<List<DapModels.VariableInfo>> out = new AtomicReference<>();
        rendering.apply(List.of(vars), 3, out::set);
        return out.get();
    }

    private List<DapModels.VariableInfo> children(int ref) {
        AtomicReference<List<DapModels.VariableInfo>> out = new AtomicReference<>();
        rendering.children(ref, out::set);
        return out.get();
    }

    @Test
    void aRenderersValueReplacesTheDebuggersAndKeepsItsChildren() {
        renderers.add(new TypeRenderer(true, "java", "Money", "this.amount + \" \" + this.currency", ""));
        DapModels.VariableInfo shown = apply(money("order.total")).getFirst();
        assertEquals("12.50 EUR", shown.value(), "a string shown without its quotes");
        assertEquals(21, shown.variablesReference(), "the debugger's own children");
        assertEquals(2, shown.namedVariables());
        assertEquals("order.total", shown.evaluateName());
    }

    @Test
    void aValueWithoutAnExpressionOrAFailedRendererIsTheDebuggers() {
        renderers.add(new TypeRenderer(true, "java", "Money", "this.missing", ""));
        DapModels.VariableInfo plain = new DapModels.VariableInfo("t", "Money@7", "Money", 21, 0, 0, false);
        assertEquals(List.of(plain), apply(plain), "no evaluateName: nothing to evaluate");
        assertEquals("Money@7", apply(money("order.total")).getFirst().value(), "failed: the debugger's value");
    }

    @Test
    void childrenAreTheRenderersExpressionsThenTheRawView() {
        renderers.add(
                new TypeRenderer(true, "", "Money", "", "amount = this.amount\nowner = this.owner\nbad = this.x"));
        DapModels.VariableInfo shown = apply(money("order.total")).getFirst();
        assertEquals("Money@7", shown.value());
        int ref = shown.variablesReference();
        assertTrue(rendering.owns(ref) && ref >= TypeRendering.FIRST_REF, "a reference of Editora's own");
        assertEquals(4, shown.namedVariables(), "three children and the raw view");

        List<DapModels.VariableInfo> kids = children(ref);
        assertEquals(
                List.of("amount", "owner", "bad", TypeRendering.RAW_VIEW),
                kids.stream().map(DapModels.VariableInfo::name).toList());
        assertEquals("12.50", kids.get(0).value());
        assertEquals("(order.total).amount", kids.get(0).evaluateName(), "Copy as Expression, Add to Watches");
        assertEquals(44, kids.get(1).variablesReference(), "the owner opens as the debugger shows it");
        assertEquals("no such field", kids.get(2).value(), "a failure says why");
        assertEquals(21, kids.get(3).variablesReference(), "the raw view is the debugger's own children");

        rendering.clear();
        assertFalse(rendering.owns(ref), "the program moved on");
        assertTrue(children(ref).isEmpty());
    }

    @Test
    void aChildIsRenderedByItsOwnTypesRenderer() {
        renderers.add(new TypeRenderer(true, "", "Money", "", "owner = this.owner"));
        renderers.add(new TypeRenderer(true, "", "Owner", "this.name", ""));
        int ref = apply(money("order.total")).getFirst().variablesReference();
        assertEquals("Ann", children(ref).getFirst().value());
    }

    @Test
    void mutedOrAnotherTypeIsLeftAlone() {
        renderers.add(new TypeRenderer(true, "", "Money", "this.amount", ""));
        DapModels.VariableInfo other = new DapModels.VariableInfo("n", "3", "int", 0, 0, 0, false, null, "n");
        assertEquals(List.of(other), apply(other));
        muted = true;
        assertEquals("Money@7", apply(money("order.total")).getFirst().value());
        assertTrue(asked.isEmpty(), "nothing evaluated");
    }
}
