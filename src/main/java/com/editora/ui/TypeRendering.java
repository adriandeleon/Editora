package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.editora.dap.DapModels;
import com.editora.dap.TypeRenderer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Type renderers at work while the program is stopped: the variables the debugger lists (and the watches it
 * evaluates) go through {@link #apply}, which shows a renderer's value in place of the debugger's and, when it lists
 * children, gives the row a reference of Editora's own, answered by {@link #children} — each child an expression of
 * the value, then {@code [Raw View]} with the debugger's own children. The value's expression is the adapter's
 * {@code evaluateName} (or the watch's text); a value without one is shown as the debugger shows it.
 *
 * <p>Everything is evaluated in the stopped frame, so the references Editora hands out last until the program moves
 * on ({@link #clear}). One per debug session; FX thread.
 */
final class TypeRendering {

    /** Evaluates an expression in a frame (DAP {@code evaluate}, context {@code watch}); delivers on the FX thread. */
    interface Evaluator {
        void evaluate(String expression, int frameId, Consumer<DapModels.EvalResult> cb);
    }

    /**
     * Where Editora's own references start: above what an adapter hands out (each numbers its own from 1 up, even
     * CodeLLDB's from 1000), below DAP's 2^31.
     */
    static final int FIRST_REF = 1_900_000_000;

    /** The row named {@code [Raw View]}: the debugger's own children of a rendered value. */
    static final String RAW_VIEW = "[Raw View]";

    private record Expansion(TypeRenderer renderer, String expression, int frameId, int rawRef) {}

    private final Supplier<List<TypeRenderer>> renderers;
    private final Supplier<String> language;
    private final BooleanSupplier muted;
    private final Evaluator evaluator;
    private final Map<Integer, Expansion> expansions = new HashMap<>();
    private int nextRef = FIRST_REF;

    /**
     * @param renderers the renderers in Settings
     * @param language the session's debug language
     * @param muted Mute Renderers: every value as the debugger shows it
     */
    TypeRendering(
            Supplier<List<TypeRenderer>> renderers,
            Supplier<String> language,
            BooleanSupplier muted,
            Evaluator evaluator) {
        this.renderers = renderers;
        this.language = language;
        this.muted = muted;
        this.evaluator = evaluator;
    }

    /** The program moved on (or the session ended): the references handed out mean nothing now. */
    void clear() {
        expansions.clear();
        nextRef = FIRST_REF;
    }

    /** Whether {@code ref} is one of Editora's own (a rendered value's children), answered by {@link #children}. */
    boolean owns(int ref) {
        return expansions.containsKey(ref);
    }

    /** The renderer for a value of {@code type}, or null (none, or muted). */
    @Nullable
    TypeRenderer rendererFor(@Nullable String type, @Nullable String value) {
        if (muted.getAsBoolean()) {
            return null;
        }
        List<TypeRenderer> all = renderers.get();
        return all.isEmpty() ? null : TypeRenderers.match(all, language.get(), type, value);
    }

    /**
     * {@code vars} with each rendered value's renderer applied, in their order, to {@code cb} once every value is in.
     * A failed expression leaves the debugger's value.
     */
    void apply(List<DapModels.VariableInfo> vars, int frameId, Consumer<List<DapModels.VariableInfo>> cb) {
        if (muted.getAsBoolean() || frameId < 0 || renderers.get().isEmpty()) {
            cb.accept(vars);
            return;
        }
        List<DapModels.VariableInfo> out = new ArrayList<>(vars);
        int[] pending = {1}; // released once every request is out, so an answer in the same pulse cannot finish early
        Runnable done = () -> {
            if (--pending[0] == 0) {
                cb.accept(out);
            }
        };
        for (int i = 0; i < vars.size(); i++) {
            DapModels.VariableInfo v = vars.get(i);
            String expression = v.evaluateName();
            if (expression == null || expression.isBlank()) {
                continue; // no expression of the value to put in the renderer's
            }
            TypeRenderer r = rendererFor(v.type(), v.value());
            if (r == null) {
                continue;
            }
            int at = i;
            pending[0]++;
            render(r, expression, frameId, v.value(), v.variablesReference(), (value, ref, named) -> {
                out.set(
                        at,
                        new DapModels.VariableInfo(
                                v.name(),
                                value,
                                v.type(),
                                ref,
                                ref == v.variablesReference() ? v.namedVariables() : named,
                                ref == v.variablesReference() ? v.indexedVariables() : 0,
                                v.lazy() && ref == v.variablesReference(),
                                v.note(),
                                v.evaluateName()));
                done.run();
            });
        }
        done.run();
    }

    /** A rendered value: its text, its children's reference and how many named children it has. */
    interface Rendered {
        void accept(String value, int ref, int named);
    }

    /**
     * Renders the value {@code expression} gives with {@code r}: its value text (the debugger's {@code shown} when
     * the renderer has none or it fails) and its reference — Editora's own when the renderer lists children, else
     * {@code ref}.
     */
    void render(TypeRenderer r, String expression, int frameId, String shown, int ref, Rendered cb) {
        List<TypeRenderers.Child> children = TypeRenderers.children(r.getChildren());
        int childRef = ref;
        int named = 0;
        if (!children.isEmpty()) {
            childRef = nextRef++;
            expansions.put(childRef, new Expansion(r, expression, frameId, ref));
            named = children.size() + (ref > 0 ? 1 : 0);
        }
        int finalRef = childRef;
        int finalNamed = named;
        if (r.getValue().isBlank()) {
            cb.accept(shown, finalRef, finalNamed);
            return;
        }
        evaluator.evaluate(TypeRenderers.substitute(r.getValue(), expression), frameId, res -> {
            boolean ok = res != null && !res.failed() && res.result() != null;
            cb.accept(ok ? DebugValues.display(TypeRenderers.unquote(res.result())) : shown, finalRef, finalNamed);
        });
    }

    /**
     * The children of a rendered value ({@code ref} one of {@link #owns}): each of its renderer's expressions,
     * evaluated (rendered in turn when its type has a renderer), then {@code [Raw View]}. A failed one shows the
     * debugger's message.
     */
    void children(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
        Expansion e = expansions.get(ref);
        if (e == null) {
            cb.accept(List.of());
            return;
        }
        List<TypeRenderers.Child> children = TypeRenderers.children(e.renderer().getChildren());
        DapModels.VariableInfo[] out = new DapModels.VariableInfo[children.size()];
        int[] pending = {children.size() + 1};
        Runnable done = () -> {
            if (--pending[0] == 0) {
                List<DapModels.VariableInfo> list = new ArrayList<>(List.of(out));
                if (e.rawRef() > 0) {
                    list.add(new DapModels.VariableInfo(RAW_VIEW, "", "", e.rawRef(), 0, 0, false, null, null));
                }
                apply(list, e.frameId(), cb);
            }
        };
        for (int i = 0; i < children.size(); i++) {
            TypeRenderers.Child child = children.get(i);
            String expression = TypeRenderers.substitute(child.expression(), e.expression());
            int at = i;
            evaluator.evaluate(expression, e.frameId(), res -> {
                out[at] = res == null || res.failed()
                        ? new DapModels.VariableInfo(
                                child.name(),
                                res == null || res.result() == null ? tr("debugpanel.noValue") : res.result(),
                                "",
                                0,
                                0,
                                0,
                                false,
                                null,
                                expression)
                        : new DapModels.VariableInfo(
                                child.name(),
                                res.result() == null ? "" : res.result(),
                                res.type() == null ? "" : res.type(),
                                res.variablesReference(),
                                res.namedVariables(),
                                res.indexedVariables(),
                                false,
                                null,
                                expression);
                done.run();
            });
        }
        done.run();
    }
}
