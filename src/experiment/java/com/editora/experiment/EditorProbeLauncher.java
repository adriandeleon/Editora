package com.editora.experiment;

import javafx.application.Application;

/** Classpath entry point; Application.launch also preserves StaticFX's macOS first-thread handoff. */
public final class EditorProbeLauncher {
    private EditorProbeLauncher() {}

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            System.exit(2);
        });
        Application.launch(EditorProbe.class, args);
    }
}
