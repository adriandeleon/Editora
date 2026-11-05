package com.editora.dap;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Set Value on an object variable (Java debugging review 3, B208). java-debug's {@code setVariable} sets only
 * primitives and Strings; for an {@code Integer}, a record or an enum it fails with "Set value is not supported yet
 * for type java.lang.Integer". Editora then assigns the value with an evaluation ({@code boxed = 44}), as IntelliJ
 * does. Pure (unit-tested).
 */
final class SetValueText {

    private SetValueText() {}

    /** Whether {@code error} is java-debug's refusal to set a variable of an object type. */
    static boolean unsupportedType(@Nullable String error) {
        return error != null && error.toLowerCase(Locale.ROOT).contains("set value is not supported");
    }

    /** The assignment that sets {@code target} (a variable, {@code obj.field} or {@code arr[3]}) to {@code value}. */
    static String assignment(String target, String value) {
        return target.strip() + " = " + value.strip();
    }
}
