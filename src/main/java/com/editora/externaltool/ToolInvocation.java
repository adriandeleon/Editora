package com.editora.externaltool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.run.ProgramArgs;

/**
 * A resolved, ready-to-run external-tool invocation, built purely from an {@link ExternalTool} + a
 * {@link ToolContext} (unit-tested). The {@code command} and {@code arguments} are macro-expanded then
 * tokenized (quote-aware, via {@link ProgramArgs#tokenize}) into one {@code argv}; {@code workingDir} is the
 * expanded directory or the supplied default; {@code stdin} is the text to feed the process (or {@code null}).
 *
 * @param argv the full command line (program + args); empty if the command is blank
 * @param workingDir directory to run in, or {@code null} to inherit
 * @param stdin text to pipe to stdin, or {@code null} for none
 * @param displayCommand the argv joined with spaces, for console/status display
 */
public record ToolInvocation(List<String> argv, Path workingDir, String stdin, String displayCommand) {

    public static ToolInvocation of(ExternalTool tool, ToolContext ctx, Path defaultDir) {
        List<String> argv = new ArrayList<>();
        argv.addAll(ProgramArgs.tokenize(ToolMacros.expand(tool.getCommand(), ctx)));
        argv.addAll(ProgramArgs.tokenize(ToolMacros.expand(tool.getArguments(), ctx)));

        String dir = ToolMacros.expand(tool.getWorkingDir(), ctx);
        Path workingDir = (dir != null && !dir.isBlank()) ? Path.of(dir.trim()) : defaultDir;

        String stdin =
                switch (tool.getStdin()) {
                    case NONE -> null;
                    case SELECTION -> ns(ctx.selectedText());
                    case BUFFER -> ns(ctx.bufferText());
                };

        return new ToolInvocation(List.copyOf(argv), workingDir, stdin, String.join(" ", argv));
    }

    public boolean isEmpty() {
        return argv.isEmpty();
    }

    private static String ns(String s) {
        return s == null ? "" : s;
    }
}
