package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.config.Breakpoint;
import com.editora.dap.DapModels;
import com.editora.editor.debug.LineFunctions;
import org.jspecify.annotations.Nullable;

/**
 * Pure: what one breakpoint asks of the debug adapter — its line, and on a line of Java, JavaScript or TypeScript each
 * function it stops in (a lambda, an arrow function), at the column to stop in it at now (they are kept by number, so
 * an edit elsewhere on the line moves none).
 */
final class BreakpointTargets {

    private BreakpointTargets() {}

    /**
     * {@code bp} as the adapter's breakpoints at 0-based {@code line} (in the numbering of the code the program runs):
     * the line unless it stops only in functions, then one per function of {@code lineText} it names. With no line
     * text, in a {@code language} whose functions it cannot stop in, or when none of its functions is on the line any
     * more, it stops on the line, as a plain one does.
     */
    static List<DapModels.LineBreakpoint> of(
            Breakpoint bp, int line, @Nullable String lineText, @Nullable String language) {
        List<DapModels.LineBreakpoint> out = new ArrayList<>();
        List<DapModels.LineBreakpoint> lambdas = new ArrayList<>();
        if (bp.hasLambdas() && lineText != null) {
            List<LineFunctions.Function> found = LineFunctions.on(language, lineText);
            for (int n : bp.lambdas()) {
                if (n <= found.size()) {
                    lambdas.add(new DapModels.LineBreakpoint(
                            line,
                            bp.condition(),
                            bp.logMessage(),
                            bp.hitCondition(),
                            found.get(n - 1).stop() + 1, // DAP columns start at 1
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

    /** Whether {@code language} (an editor language id) is one whose functions a breakpoint can stop in. */
    static boolean functionsLanguage(@Nullable String language) {
        return LineFunctions.supports(language);
    }
}
