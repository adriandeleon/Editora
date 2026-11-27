package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaFxModulesTest {

    @Test
    void mavenGradleAndSdkJarsNameTheirModule() {
        assertEquals("javafx.controls", JavaFxModules.moduleOf("/m2/org/openjfx/javafx-controls-25-linux.jar"));
        assertEquals("javafx.controls", JavaFxModules.moduleOf("/m2/org/openjfx/javafx-controls-25.jar"));
        assertEquals("javafx.graphics", JavaFxModules.moduleOf("C:\\m2\\javafx-graphics-25.0.1-win.jar"));
        assertEquals("javafx.base", JavaFxModules.moduleOf("/gradle/javafx-base-21.0.5-mac-aarch64.jar"));
        assertEquals("javafx.fxml", JavaFxModules.moduleOf("/opt/javafx-sdk-25/lib/javafx.fxml.jar"));
    }

    @Test
    void otherJarsAndFoldersAreNotJavaFx() {
        assertNull(JavaFxModules.moduleOf("/m2/org/openjfx/javafx-maven-plugin-0.0.8.jar"));
        assertNull(JavaFxModules.moduleOf("/opt/javafx-sdk-25/lib/javafx-swt.jar"));
        assertNull(JavaFxModules.moduleOf("/m2/org/controlsfx/controlsfx-11.2.1.jar"));
        assertNull(JavaFxModules.moduleOf("/project/target/classes"));
        assertNull(JavaFxModules.moduleOf(null));
    }

    @Test
    void splitTakesTheJavaFxJarsOutAndNamesEachModuleOnce() {
        JavaFxModules.Split s = JavaFxModules.split(
                List.of(
                        "/p/target/classes",
                        "/m2/javafx-controls-25.jar",
                        "/m2/javafx-controls-25-linux.jar",
                        "/m2/javafx-graphics-25-linux.jar",
                        "/m2/javafx-base-25-linux.jar",
                        "/m2/gson-2.11.jar"),
                List.<String>of());
        assertEquals(
                List.of(
                        "/m2/javafx-controls-25.jar",
                        "/m2/javafx-controls-25-linux.jar",
                        "/m2/javafx-graphics-25-linux.jar",
                        "/m2/javafx-base-25-linux.jar"),
                s.jars());
        assertEquals("javafx.base,javafx.controls,javafx.graphics", s.addModules());
        assertEquals(List.of("/p/target/classes", "/m2/gson-2.11.jar"), s.rest());
    }

    @Test
    void vmOptionsThatChooseModulesAreLeftAlone() {
        List<String> paths = List.of("/c", "/m2/javafx-controls-25-linux.jar");
        for (List<String> vm : List.of(
                List.of("--module-path", "/sdk/lib"),
                List.of("--module-path=/sdk/lib"),
                List.of("-p", "/sdk/lib"),
                List.of("--add-modules", "javafx.controls"),
                List.of("--add-modules=ALL-MODULE-PATH"))) {
            JavaFxModules.Split s = JavaFxModules.split(paths, vm);
            assertFalse(s.any(), vm.toString());
            assertEquals(paths, s.rest());
        }
        assertFalse(JavaFxModules.split(paths, "-Xmx1g --add-modules javafx.controls")
                .any());
    }

    /**
     * Regression (r/JavaFX re-review, 2026-11-27): every run of a JavaFX app on JDK 24+ printed four red "restricted
     * method" warnings. JavaFX 24+ (which needs JDK 22+) gets native access for the modules that load native code.
     */
    @Test
    void recentJavaFxGetsNativeAccessForItsNativeModules() {
        JavaFxModules.Split s = JavaFxModules.split(
                List.of(
                        "/m2/javafx-controls-25-linux.jar",
                        "/m2/javafx-graphics-25-linux.jar",
                        "/m2/javafx-media-25-linux.jar"),
                List.<String>of());
        assertEquals(List.of("javafx.graphics", "javafx.media"), s.nativeAccess());
        assertEquals("--enable-native-access=javafx.graphics,javafx.media", s.enableNativeAccess());
        assertEquals(25, JavaFxModules.versionOf("/m2/javafx-graphics-25.0.1-mac-aarch64.jar"));
        assertEquals(0, JavaFxModules.versionOf("/sdk/lib/javafx.graphics.jar"));
    }

    @Test
    void olderOrUnversionedJavaFxOrTheUsersOwnFlagGetsNoneAdded() {
        assertNull(
                JavaFxModules.split(List.of("/m2/javafx-graphics-21.0.5-linux.jar"), List.<String>of())
                        .enableNativeAccess(),
                "JavaFX 21 may run on JDK 17");
        assertNull(
                JavaFxModules.split(List.of("/sdk/lib/javafx.graphics.jar"), List.<String>of())
                        .enableNativeAccess(),
                "an SDK jar names no version");
        assertNull(
                JavaFxModules.split(
                                List.of("/m2/javafx-graphics-25-linux.jar"),
                                List.of("--enable-native-access=ALL-UNNAMED"))
                        .enableNativeAccess(),
                "the user's own option stands");
    }
}
