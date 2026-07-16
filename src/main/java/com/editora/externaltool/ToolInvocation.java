package com.editora.externaltool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.run.ProgramArgs;

/**
 * A resolved, ready-to-run external-tool invocation, built purely from an {@link ExternalTool} + a
 * {@link ToolContext} (unit-tested). The {@code command} and {@code arguments} are <b>tokenized first</b>
 * (quote-aware, via {@link ProgramArgs#tokenize}) and each token is then macro-expanded, so one macro always
 * yields exactly one {@code argv} element; {@code workingDir} is the expanded directory or the supplied
 * default; {@code stdin} is the text to feed the process (or {@code null}).
 *
 * <p>Expanding before tokenizing — as this did — tokenized the <b>user's data</b>: a
 * {@code $FilePath$} of {@code /Users/me/My Docs/a.txt} became two argv elements, and no quoting could fix
 * it ({@code '$FilePath$'} survives a space but an apostrophe in the path — {@code ~/Bob's Files/} — closes
 * the quote early and is itself deleted). An empty {@code $SelectedText$} vanished entirely rather than
 * passing {@code ""}, silently shifting every positional argument after it.
 *
 * @param argv the full command line (program + args); empty if the command is blank
 * @param workingDir directory to run in, or {@code null} to inherit
 * @param stdin text to pipe to stdin, or {@code null} for none
 * @param displayCommand the argv joined with spaces, for console/status display
 */
public record ToolInvocation(List<String> argv, Path workingDir, String stdin, String displayCommand) {

    public static ToolInvocation of(ExternalTool tool, ToolContext ctx, Path defaultDir) {
        List<String> argv = new ArrayList<>();
        argv.addAll(expandTokens(tool.getCommand(), ctx));
        argv.addAll(expandTokens(tool.getArguments(), ctx));

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

    /** Tokenizes the template, then expands the macros <b>within each token</b> — one macro, one argv element. */
    private static List<String> expandTokens(String template, ToolContext ctx) {
        List<String> out = new ArrayList<>();
        for (String token : ProgramArgs.tokenize(template)) {
            out.add(ToolMacros.expand(token, ctx));
        }
        return out;
    }

    public boolean isEmpty() {
        return argv.isEmpty();
    }

    private static String ns(String s) {
        return s == null ? "" : s;
    }
}
