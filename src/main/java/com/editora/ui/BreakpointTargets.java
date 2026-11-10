package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.config.Breakpoint;
import com.editora.dap.DapModels;
import com.editora.editor.debug.JavaLambdas;
import org.jspecify.annotations.Nullable;

/**
 * Pure: what one breakpoint asks of the debug adapter — its line, and on a line of Java each lambda it stops in, at
 * the column that lambda starts at now (lambdas are kept by number, so an edit elsewhere on the line moves none).
 */
final class BreakpointTargets {

    private BreakpointTargets() {}

    /**
     * {@code bp} as the adapter's breakpoints at 0-based {@code line} (in the numbering of the code the program runs):
     * the line unless it stops only in lambdas, then one per lambda of {@code lineText} it names. With no line text,
     * outside Java, or when none of its lambdas is on the line any more, it stops on the line, as a plain one does.
     */
    static List<DapModels.LineBreakpoint> of(Breakpoint bp, int line, @Nullable String lineText, boolean java) {
        List<DapModels.LineBreakpoint> out = new ArrayList<>();
        List<DapModels.LineBreakpoint> lambdas = new ArrayList<>();
        if (java && bp.hasLambdas() && lineText != null) {
            List<JavaLambdas.Lambda> found = JavaLambdas.on(lineText);
            for (int n : bp.lambdas()) {
                if (n <= found.size()) {
                    lambdas.add(new DapModels.LineBreakpoint(
                            line,
                            bp.condition(),
                            bp.logMessage(),
                            bp.hitCondition(),
                            found.get(n - 1).column() + 1, // DAP columns start at 1
                            bp.records()));
                }
            }
        }
        if (bp.stopsOnLine() || lambdas.isEmpty()) {
            out.add(new DapModels.LineBreakpoint(
                    line, bp.condition(), bp.logMessage(), bp.hitCondition(), 0, bp.records()));
        }
        out.addAll(lambdas);
        return out;
    }

    /** Whether {@code language} (an editor language id) is one whose lambdas a breakpoint can stop in. */
    static boolean javaLanguage(@Nullable String language) {
        return "java".equals(language);
    }
}
