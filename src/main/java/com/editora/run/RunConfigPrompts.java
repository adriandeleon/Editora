package com.editora.run;

/**
 * The prompt (placeholder) message keys the Run Configurations form shows for each type, so a blank field says
 * what it means for that type: an npm configuration's target is a script name from {@code package.json}, a
 * Node script's Interpreter is "automatic", a Python one's is the project's venv, a C file's working directory is
 * where its program is built. Pure.
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
        if (ScriptRunCommand.isCompiled(type)) {
            return "settings.runConfig.targetPromptSource";
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
        if (ScriptRunCommand.isCompiled(type)) {
            return "settings.runConfig.workingDirPromptSource";
        }
        return "settings.runConfig.workingDirPrompt";
    }

    /**
     * The Before launch prompt key: a Node or npm configuration's example is an npm build, not Maven's, and a C, C++,
     * Go or Rust one's says its build needs no step.
     */
    public static String beforeLaunch(String type) {
        if (ScriptRunCommand.isCompiled(type)) {
            return "settings.runConfig.beforeLaunchPromptSource";
        }
        return ScriptRunCommand.NODE.equals(type) || ScriptRunCommand.NPM.equals(type)
                ? "settings.runConfig.beforeLaunchPromptNode"
                : "settings.runConfig.beforeLaunchPrompt";
    }

    /**
     * The row labels a type names in its own words — a Node.js configuration's Interpreter is its "Node.js", its
     * options "Node.js options", its target a "Script" — as {@code [target, interpreter, options]} keys.
     */
    public static java.util.List<String> labels(String type) {
        if (ScriptRunCommand.NODE.equals(type)) {
            return java.util.List.of(
                    "settings.runConfig.targetScript",
                    "settings.runConfig.interpreterNode",
                    "settings.runConfig.interpreterOptionsNode");
        }
        if (ScriptRunCommand.isCompiled(type)) {
            return java.util.List.of(
                    "settings.runConfig.targetSource",
                    "settings.runConfig.interpreter",
                    "settings.runConfig.interpreterOptions");
        }
        return java.util.List.of(
                ScriptRunCommand.NPM.equals(type) ? "settings.runConfig.targetScript" : "settings.runConfig.target",
                "settings.runConfig.interpreter",
                "settings.runConfig.interpreterOptions");
    }

    /** Whether the target row's Browse… button applies: a script file to pick, or an npm script to choose. */
    public static boolean browses(String type) {
        return RunConfigFields.usesScriptFile(type) || ScriptRunCommand.NPM.equals(type);
    }
}
