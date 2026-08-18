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
 * <p>The modular run paths ({@code mvn javafx:run}, the {@code dist} app-image/installer) enter here too,
 * for a second and independent reason: when the main class extends {@code Application}, the FX launcher
 * starts the JavaFX toolkit <em>before</em> {@code main} runs, leaving {@link App#main} nothing to do
 * concurrently with it. Entering through this class moves toolkit startup after {@code main}, which is what
 * lets {@code App.main} load the config on a background thread while the toolkit comes up — measured as a
 * ~135 ms window on the Linux app image. Keep this class as the declared main class in {@code pom.xml}
 * (the {@code javafx-maven-plugin} {@code mainClass}, the jpackage {@code module}, and the {@code moduleMain}
 * argument handed to {@code scripts/aot_build.java}); pointing any of them back at {@code App} silently
 * closes that window again.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        App.main(args);
    }
}
