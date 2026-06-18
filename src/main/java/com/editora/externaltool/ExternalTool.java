package com.editora.externaltool;

import java.util.Locale;

/**
 * A user-defined external CLI command (IntelliJ-style "External Tools"): a display {@code name}, the
 * {@code command} (program) and {@code arguments} (both support {@code $Name$} macros — see
 * {@link ToolMacros}), an optional {@code workingDir} (blank ⇒ the file's directory), a {@link StdinSource}
 * (what to pipe to the process's stdin), and an {@link OutputTarget} (what to do with its stdout). A mutable
 * Jackson POJO so it round-trips in {@code settings.toml} as part of {@code Settings} (an array of tables).
 */
public class ExternalTool {

    /** What text, if any, is piped to the command's standard input. */
    public enum StdinSource {
        NONE,
        SELECTION,
        BUFFER
    }

    /** What is done with the command's standard output. */
    public enum OutputTarget {
        CONSOLE,
        REPLACE_SELECTION,
        REPLACE_BUFFER,
        INSERT_AT_CARET
    }

    private String name = "";
    private String command = "";
    private String arguments = "";
    private String workingDir = "";
    private StdinSource stdin = StdinSource.NONE;
    private OutputTarget output = OutputTarget.CONSOLE;
    private boolean enabled = true;

    public ExternalTool() {}

    public ExternalTool(
            String name,
            String command,
            String arguments,
            String workingDir,
            StdinSource stdin,
            OutputTarget output,
            boolean enabled) {
        this.name = name;
        this.command = command;
        this.arguments = arguments;
        this.workingDir = workingDir;
        this.stdin = stdin;
        this.output = output;
        this.enabled = enabled;
    }

    /** The synthetic command id under which this tool is registered (palette- and key-bindable). */
    public static String commandIdFor(String name) {
        return "externalTool.run." + slug(name);
    }

    /** A command-id-safe slug of a tool name (mirrors {@code MacroService.slug}). */
    public static String slug(String name) {
        String s = (name == null ? "" : name)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "tool" : s;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(String workingDir) {
        this.workingDir = workingDir;
    }

    public StdinSource getStdin() {
        return stdin;
    }

    public void setStdin(StdinSource stdin) {
        this.stdin = stdin;
    }

    public OutputTarget getOutput() {
        return output;
    }

    public void setOutput(OutputTarget output) {
        this.output = output;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
