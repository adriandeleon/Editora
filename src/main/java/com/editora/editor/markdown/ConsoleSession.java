package com.editora.editor.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.tm4e.core.grammar.IGrammar;

/**
 * A terminal session in a Markdown fence ({@code ```console}, {@code ```shell-session}): prompt lines hold a
 * command, the other lines are what it printed. Highlighting the whole block as a script coloured the output as
 * if it were code; highlighting none of it lost the commands. Here the prompt is muted, the command after it is
 * tokenized as shell, and the output stays plain — as GitHub shows such blocks.
 */
final class ConsoleSession {

    private ConsoleSession() {}

    /** Fence info strings that mean a terminal session. */
    private static final Set<String> INFOS = Set.of("console", "shell-session", "shellsession", "terminal");

    /**
     * A prompt at the start of a line: {@code $ }, {@code # }, {@code % }, {@code > } (a continuation),
     * {@code user@host:~/dir$ }, {@code (venv) $ }, {@code [root@host ~]# }.
     */
    private static final Pattern PROMPT =
            Pattern.compile("^(\\s*(?:\\([^)\\s]+\\)\\s+)?(?:\\[[^\\]]*\\]|[\\w.@:~/+-]*)[$#%>]\\s)");

    /** Whether {@code info} (a fence info string) names a terminal session. */
    static boolean isConsole(String info) {
        if (info == null || info.isBlank()) {
            return false;
        }
        return INFOS.contains(info.trim().split("\\s+")[0].toLowerCase(Locale.ROOT));
    }

    /** Runs for {@code code}: prompts as {@code comment}, commands tokenized with {@code shell}, output plain. */
    static List<MarkdownRenderer.Run> runs(String code, IGrammar shell) {
        List<MarkdownRenderer.Run> runs = new ArrayList<>();
        String[] lines = code.split("\n", -1);
        boolean continued = false; // the previous command line ended in a backslash
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String command = null;
            if (continued) {
                command = line;
            } else {
                Matcher m = PROMPT.matcher(line);
                if (m.find()) {
                    runs.add(new MarkdownRenderer.Run(m.group(1), List.of("comment")));
                    command = line.substring(m.end());
                }
            }
            if (command == null) {
                runs.add(new MarkdownRenderer.Run(line, List.of()));
            } else if (!command.isEmpty()) {
                List<MarkdownRenderer.Run> tokens = MarkdownRenderer.tokenizeRuns(command, shell);
                runs.addAll(tokens != null ? tokens : List.of(new MarkdownRenderer.Run(command, List.of())));
            }
            continued = command != null && command.endsWith("\\");
            if (i < lines.length - 1) {
                runs.add(new MarkdownRenderer.Run("\n", List.of()));
            }
        }
        runs.removeIf(r -> r.text().isEmpty());
        return runs;
    }
}
