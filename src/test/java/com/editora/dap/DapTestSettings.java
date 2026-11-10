package com.editora.dap;

/**
 * Sets a debug manager's shared settings ({@link DapSettings}) directly, for window tests that skip detection: Python
 * on and found with no Python installed, debugging on without the java-debug plugin.
 */
public final class DapTestSettings {

    private DapTestSettings() {}

    /** Sets field {@code name} of {@code manager}'s {@link DapSettings} to {@code value}. */
    public static void set(DapManager manager, String name, Object value) {
        try {
            var shared = DapManager.class.getDeclaredField("shared");
            shared.setAccessible(true);
            var field = DapSettings.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(shared.get(manager), value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
