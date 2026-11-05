package com.editora.dap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Resolves the variables an adapter sends as {@code presentationHint.lazy} (Java debugging review 2, B101).
 *
 * <p>java-debug 0.53.2 marks every object whose class has its own {@code toString()} — {@code Integer} and the other
 * boxes, enums, records, dates, most domain classes — as lazy: the row says only {@code Integer@62}, and its single
 * child holds the real value ({@code Integer@62 "0"}) with the object's fields below it. Shown as an ordinary
 * object, a {@code List<Integer>} read as a hundred {@code Integer@…} rows and an enum never showed its constant.
 * Per DAP the client asks for that child and shows it in place of the row; this does so at once (IntelliJ shows
 * the value without a click), one request per lazy row, remembered until the program runs again.
 */
final class LazyValues {

    /** Resolved rows by the lazy row's {@code variablesReference}; valid while the program stays paused. */
    private final Map<Integer, DapModels.VariableInfo> cache = new ConcurrentHashMap<>();

    /** Forgets every resolved row: the program ran, so references and values are no longer valid. */
    void clear() {
        cache.clear();
    }

    /**
     * {@code vars} with every lazy row replaced by its resolved form. {@code children} fetches one reference's
     * children; a fetch that fails or does not give exactly one child leaves the row as it came.
     */
    CompletableFuture<List<DapModels.VariableInfo>> resolveAll(
            List<DapModels.VariableInfo> vars, IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> children) {
        if (vars == null || vars.stream().noneMatch(LazyValues::needsResolving)) {
            return CompletableFuture.completedFuture(vars);
        }
        List<CompletableFuture<DapModels.VariableInfo>> each = new ArrayList<>(vars.size());
        for (DapModels.VariableInfo v : vars) {
            each.add(resolve(v, children));
        }
        return CompletableFuture.allOf(each.toArray(new CompletableFuture<?>[0]))
                .thenApply(done -> each.stream().map(CompletableFuture::join).toList());
    }

    private CompletableFuture<DapModels.VariableInfo> resolve(
            DapModels.VariableInfo v, IntFunction<CompletableFuture<List<DapModels.VariableInfo>>> children) {
        if (!needsResolving(v)) {
            return CompletableFuture.completedFuture(v);
        }
        DapModels.VariableInfo known = cache.get(v.variablesReference());
        if (known != null) {
            return CompletableFuture.completedFuture(rename(known, v.name()));
        }
        CompletableFuture<List<DapModels.VariableInfo>> asked;
        try {
            asked = children.apply(v.variablesReference());
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(v);
        }
        return asked.handle((kids, e) -> {
            if (e != null || kids == null || kids.size() != 1) {
                return v;
            }
            DapModels.VariableInfo resolved = resolved(v, kids.get(0));
            cache.put(v.variablesReference(), resolved);
            return resolved;
        });
    }

    private static boolean needsResolving(DapModels.VariableInfo v) {
        return v != null && v.lazy() && v.variablesReference() > 0;
    }

    private static DapModels.VariableInfo rename(DapModels.VariableInfo v, String name) {
        return v.name().equals(name)
                ? v
                : new DapModels.VariableInfo(
                        name, v.value(), v.type(), v.variablesReference(), v.namedVariables(), v.indexedVariables());
    }

    /**
     * Pure: the row a lazy variable becomes once its one {@code child} is known — the child's value and children
     * under the variable's own name and type. A boxed primitive shows its bare value ({@code 42}, not
     * {@code Integer@13 "42"}), as IntelliJ does.
     */
    static DapModels.VariableInfo resolved(DapModels.VariableInfo lazy, DapModels.VariableInfo child) {
        String value = child.value() == null || child.value().isEmpty() ? lazy.value() : child.value();
        String type = lazy.type() == null || lazy.type().isEmpty() ? child.type() : lazy.type();
        String bare = boxedValue(type, value);
        // A boxed value's only children are its own `value` field: nothing worth a level of its own.
        boolean leaf = bare != null;
        return new DapModels.VariableInfo(
                lazy.name(),
                bare != null ? bare : value,
                type,
                leaf ? 0 : child.variablesReference(),
                leaf ? 0 : child.namedVariables(),
                leaf ? 0 : child.indexedVariables());
    }

    /** The boxed types whose {@code toString()} is the whole value. */
    private static final Set<String> BOXES =
            Set.of("Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character");

    /** java-debug's rendering of an object with its {@code toString()}: {@code Type@id "text"}. */
    private static final Pattern DETAILED = Pattern.compile("^\\S+@\\d+ \"(.*)\"$", Pattern.DOTALL);

    /**
     * Pure: the bare value of a boxed primitive rendered as {@code Integer@13 "42"} — {@code 42}, or {@code 'c'} for
     * a {@code Character} — else null.
     */
    static @Nullable String boxedValue(@Nullable String type, @Nullable String value) {
        if (type == null || value == null) {
            return null;
        }
        String simple = type.startsWith("java.lang.") ? type.substring("java.lang.".length()) : type;
        if (!BOXES.contains(simple)) {
            return null;
        }
        Matcher m = DETAILED.matcher(value);
        if (!m.matches()) {
            return null;
        }
        return "Character".equals(simple) ? "'" + m.group(1) + "'" : m.group(1);
    }
}
