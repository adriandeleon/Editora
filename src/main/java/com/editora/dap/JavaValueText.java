package com.editora.dap;

import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * How java-debug's value text is tidied before it is shown (Java debugging review 2, B108). A lambda's class is a
 * JVM hidden class named {@code Main$$Lambda/0x0000000036041000}; java-debug keeps the part after the last dot, so a
 * lambda variable read {@code lam = 0x0000000036041000@19} with the type {@code 0x0000000036041000}. It now reads
 * {@code lambda@19} of type {@code lambda}. An evaluated boxed number reads {@code 42}, as its Variables row does
 * (review 3, B206). Pure (unit-tested).
 */
final class JavaValueText {

    private JavaValueText() {}

    /** A hidden class's name as java-debug shows it: only its address. */
    private static final Pattern HIDDEN_TYPE = Pattern.compile("0x[0-9a-fA-F]+");

    /** A value naming a hidden class's object: {@code 0x0000000036041000@19}. */
    private static final Pattern HIDDEN_VALUE = Pattern.compile("^0x[0-9a-fA-F]+@");

    static final String LAMBDA = "lambda";

    /** {@code value} with a hidden class's address shown as {@code lambda}. */
    static @Nullable String value(@Nullable String value) {
        return value == null ? null : HIDDEN_VALUE.matcher(value).replaceFirst(LAMBDA + "@");
    }

    /** {@code type}, or {@code lambda} for a hidden class. */
    static @Nullable String type(@Nullable String type) {
        return type != null && HIDDEN_TYPE.matcher(type).matches() ? LAMBDA : type;
    }

    static DapModels.VariableInfo tidy(DapModels.VariableInfo v) {
        String value = value(v.value());
        String type = type(v.type());
        if (java.util.Objects.equals(value, v.value()) && java.util.Objects.equals(type, v.type())) {
            return v;
        }
        return new DapModels.VariableInfo(
                v.name(),
                value == null ? "" : value,
                type == null ? "" : type,
                v.variablesReference(),
                v.namedVariables(),
                v.indexedVariables(),
                v.lazy(),
                v.note());
    }

    static List<DapModels.VariableInfo> tidyAll(List<DapModels.VariableInfo> vars) {
        return vars == null ? vars : vars.stream().map(JavaValueText::tidy).toList();
    }

    static DapModels.@Nullable EvalResult tidy(DapModels.@Nullable EvalResult r) {
        if (r == null || r.failed()) {
            return r;
        }
        String value = value(r.result());
        String type = type(r.type());
        String bare = LazyValues.boxedValue(type, value);
        if (bare != null) {
            // A boxed number reads 42 here too, as in Variables, with no tree of Integer internals (review 3, B206).
            return new DapModels.EvalResult(bare, 0, type, 0, 0, false);
        }
        if (java.util.Objects.equals(value, r.result()) && java.util.Objects.equals(type, r.type())) {
            return r;
        }
        return new DapModels.EvalResult(
                value == null ? "" : value,
                r.variablesReference(),
                type,
                r.namedVariables(),
                r.indexedVariables(),
                false);
    }
}
