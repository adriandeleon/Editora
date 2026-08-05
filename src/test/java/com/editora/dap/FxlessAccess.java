package com.editora.dap;

import java.lang.reflect.Method;
import java.util.List;

/** Reaches DapManager's private static classpath parser (same package, reflection-free API not warranted). */
final class FxlessAccess {
    private FxlessAccess() {}

    static void parseClasspath(Object res, List<String> modulepaths, List<String> classpaths) {
        try {
            Method m = DapManager.class.getDeclaredMethod("parseClasspath", Object.class, List.class, List.class);
            m.setAccessible(true);
            m.invoke(null, res, modulepaths, classpaths);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("parseClasspath is the contract under test", e);
        }
    }

    /** The same, for {@code vscode.java.resolveMainClass}'s reply. */
    @SuppressWarnings("unchecked")
    static List<DapManager.MainClassOption> parseMainClasses(Object res) {
        try {
            Method m = DapManager.class.getDeclaredMethod("parseMainClasses", Object.class);
            m.setAccessible(true);
            return (List<DapManager.MainClassOption>) m.invoke(null, res);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("parseMainClasses is the contract under test", e);
        }
    }
}
