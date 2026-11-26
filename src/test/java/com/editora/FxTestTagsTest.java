package com.editora;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every test class that starts the JavaFX toolkit carries {@code @Tag("fx")}, so the pure suite runs alone by
 * leaving that tag out ({@code mvn test -DexcludedGroups=fx}) and the lanes that run only the FX suite
 * ({@code -Dgroups=fx}) run all of it. A class without the tag ran in the pure lane with a toolkit it was
 * not meant to have, and in no FX lane at all.
 *
 * <p>{@code @Tag("windows-fx")} picks the FX classes that full-tests.yml's Windows job runs
 * ({@code -Dgroups=windows-fx}): the ones that touch paths, Git, processes, sessions and the terminal. It only
 * narrows the FX suite, so a class carrying it must carry {@code @Tag("fx")} too.
 */
@Tag("guard")
class FxTestTagsTest {

    /** What starts the toolkit, or needs it started: the test support's own calls and the two direct ways. */
    private static final Pattern USES_THE_TOOLKIT = Pattern.compile("bootToolkit\\(|FxToolkit\\.|Platform\\.startup\\("
            + "|FxWindowFixture\\.create|new JFXPanel|runOnFx\\(|callOnFx\\(|drainFx\\(");

    private static final Pattern A_TEST = Pattern.compile("@(?:Test|ParameterizedTest|TestFactory|RepeatedTest)\\b");

    private static final Pattern FX_TAG = Pattern.compile("@Tag\\(\\s*\"fx\"\\s*\\)");

    private static final Pattern WINDOWS_FX_TAG = Pattern.compile("@Tag\\(\\s*\"windows-fx\"\\s*\\)");

    @Test
    void everyTestClassThatStartsTheToolkitIsTaggedFx() throws IOException {
        Path root = Path.of("src/test/java");
        assertTrue(Files.isDirectory(root), "Run from the Maven project root");
        List<String> untagged = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (file.endsWith("FxTestTagsTest.java")) {
                    continue; // names the calls it looks for
                }
                String source = Files.readString(file);
                if (A_TEST.matcher(source).find()
                        && USES_THE_TOOLKIT.matcher(source).find()
                        && !FX_TAG.matcher(source).find()) {
                    untagged.add(root.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertTrue(
                untagged.isEmpty(),
                () -> "add @Tag(\"fx\") to each of these test classes, which start the JavaFX toolkit:\n"
                        + String.join("\n", untagged));
    }

    @Test
    void everyWindowsFxClassIsAlsoAnFxClass() throws IOException {
        Path root = Path.of("src/test/java");
        assertTrue(Files.isDirectory(root), "Run from the Maven project root");
        List<String> tagged = new ArrayList<>();
        List<String> notFx = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (file.endsWith("FxTestTagsTest.java")) {
                    continue;
                }
                String source = Files.readString(file);
                if (WINDOWS_FX_TAG.matcher(source).find()) {
                    String name = root.relativize(file).toString().replace('\\', '/');
                    tagged.add(name);
                    if (!FX_TAG.matcher(source).find()) {
                        notFx.add(name);
                    }
                }
            }
        }
        assertTrue(!tagged.isEmpty(), "no class carries @Tag(\"windows-fx\"): the Windows FX job would run nothing");
        assertTrue(
                notFx.isEmpty(),
                () -> "@Tag(\"windows-fx\") narrows the FX suite; add @Tag(\"fx\") to these too:\n"
                        + String.join("\n", notFx));
    }
}
