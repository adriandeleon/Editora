package com.editora.ui;

import java.util.List;

import com.editora.dap.DapManager;
import org.jspecify.annotations.Nullable;

/** Pure helpers of {@link DebugCoordinator}'s launches, kept apart so that class stays within its size. */
final class DebugLaunchArgs {

    private DebugLaunchArgs() {}

    /** {@code host:port} an attach configuration names ({@code connect: {host, port}}, or {@code address} / {@code port}). */
    static String[] attachAddress(java.util.Map<String, Object> body, int defaultPort) {
        Object connect = body.get("connect");
        java.util.Map<?, ?> where = connect instanceof java.util.Map<?, ?> m ? m : body;
        Object host = where.get("host") != null ? where.get("host") : where.get("address");
        Object port = where.get("port");
        String p = port instanceof Number n ? String.valueOf(n.longValue()) : port == null ? "" : String.valueOf(port);
        return new String[] {
            host == null || String.valueOf(host).isBlank() ? "127.0.0.1" : String.valueOf(host),
            p.matches("\\d+") ? p : String.valueOf(defaultPort)
        };
    }

    /**
     * jdtls's entry for a configuration's {@code mainClass}. In a plain folder jdtls may know a packaged class
     * without its package (it guesses the source folder — see DapManager): the simple name then matches.
     */
    static DapManager.@Nullable MainClassOption matchConfiguredClass(
            List<DapManager.MainClassOption> options, String mainClass, boolean plainFolder) {
        for (DapManager.MainClassOption o : options) {
            if (mainClass.equals(o.className())) {
                return o;
            }
        }
        if (!plainFolder) {
            return null;
        }
        String simple = mainClass.substring(mainClass.lastIndexOf('.') + 1);
        for (DapManager.MainClassOption o : options) {
            String cls = o.className();
            if (cls != null && simple.equals(cls.substring(cls.lastIndexOf('.') + 1))) {
                return o;
            }
        }
        return null;
    }
}
