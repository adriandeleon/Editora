package com.editora.run;

/**
 * The prompt (placeholder) message keys the Run Configurations form shows for each type, so a blank field says
 * what it means for that type: an npm configuration's target is a script name from {@code package.json}, a
 * Node script's Interpreter is "automatic", a Python one's is the project's venv. Pure.
 */
public final class RunConfigPrompts {

    private RunConfigPrompts() {}

    /** The Script / target prompt key. */
    public static String target(String type) {
        if (ScriptRunCommand.PYTHON_MODULE.equals(type)) {
            return "settings.runConfig.targetPromptModule";
        }
        if (ScriptRunCommand.NPM.equals(type)) {
            return "settings.runConfig.targetPromptNpm";
        }
        if (ScriptRunCommand.NODE.equals(type)) {
            return "settings.runConfig.targetPromptNode";
        }
        return "settings.runConfig.targetPrompt";
    }

    /** The Interpreter prompt key. */
    public static String interpreter(String type) {
        if (ScriptRunCommand.isPython(type)) {
            return "settings.runConfig.interpreterPromptPython";
        }
        if (ScriptRunCommand.NODE.equals(type)) {
            return "settings.runConfig.interpreterPromptNode";
        }
        return "settings.runConfig.interpreterPrompt";
    }

    /** The Interpreter options prompt key (for Node: its arguments, such as {@code --inspect}). */
    public static String interpreterOptions(String type) {
        if (ScriptRunCommand.isPython(type)) {
            return "settings.runConfig.interpreterOptionsPromptPython";
        }
        if (ScriptRunCommand.NODE.equals(type)) {
            return "settings.runConfig.interpreterOptionsPromptNode";
        }
        return "settings.runConfig.interpreterOptionsPrompt";
    }

    /** The Working directory prompt key. */
    public static String workingDir(String type) {
        if (ScriptRunCommand.PYTHON.equals(type)) {
            return "settings.runConfig.workingDirPromptPython";
        }
        if (ScriptRunCommand.NODE.equals(type)) {
            return "settings.runConfig.workingDirPromptNode";
        }
        return "settings.runConfig.workingDirPrompt";
    }

    /** Whether the target row's Browse… button applies: a script file to pick, or an npm script to choose. */
    public static boolean browses(String type) {
        return RunConfigFields.usesScriptFile(type) || ScriptRunCommand.NPM.equals(type);
    }
}
