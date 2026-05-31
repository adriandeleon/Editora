package com.editora;

/**
 * Plain entry point for the runnable fat jar (see the {@code fatjar} Maven profile).
 *
 * <p>The Java launcher refuses to start a main class that extends
 * {@link javafx.application.Application} unless the JavaFX runtime is on the module path — it fails
 * with "JavaFX runtime components are missing, and are required to run this application". A fat jar
 * runs everything from the classpath instead, so the main class must <em>not</em> extend
 * {@code Application}; delegating from here to {@link App#main(String[])} sidesteps that check and
 * lets {@code java -jar Editora-<version>.jar} work.
 *
 * <p>This class is unused by the modular run paths ({@code mvn javafx:run}, the {@code dist}
 * app-image/installer), which keep {@link App} as the module main class.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
