package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javafx.animation.AnimationTimer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Motion follows the system: an animation that moves, fades or flashes part of the window starts through
 * {@link Motion}, which with reduced motion shows the end state at once and stops what is running when the
 * setting is turned on.
 */
@Tag("fx")
class MotionFxTest {

    @BeforeAll
    static void toolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void systemSetting() {
        Motion.setReducedForTest(null);
    }

    private static final class Counting extends AnimationTimer {
        int frames;

        @Override
        public void handle(long now) {
            frames++;
        }
    }

    @Test
    void withReducedMotionAnAnimationDoesNotRunAndItsEndStateIsShownAtOnce() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Motion.setReducedForTest(true);
            Counting animation = new Counting();
            int[] ended = {0};
            Motion.start(animation, () -> ended[0]++);
            assertEquals(1, ended[0], "the end state, at once");
            assertEquals(0, animation.frames);
            Motion.stopAll();
            assertEquals(1, ended[0], "it never started: there is nothing to stop");
        });
    }

    @Test
    void turningTheSettingOnStopsWhatIsRunningAndShowsItsEndState() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Motion.setReducedForTest(false);
            Counting running = new Counting();
            Counting finished = new Counting();
            int[] ended = {0, 0};
            Motion.start(running, () -> ended[0]++);
            Motion.start(finished, () -> ended[1]++);
            assertEquals(0, ended[0] + ended[1], "motion is allowed: both run");
            finished.stop();
            Motion.ended(finished); // ran to its own end

            Motion.stopAll(); // what the setting's listener does when it turns on
            assertEquals(1, ended[0], "the running one is stopped at its end state");
            assertEquals(0, ended[1], "the finished one is left alone");
            Motion.stopAll();
            assertEquals(1, ended[0], "once");
        });
    }

    /** The files that hold an {@link AnimationTimer}, with how many, and what each is. */
    private static final Map<String, Integer> ANIMATION_TIMERS = Map.of(
            // The flash of a revealed row starts through Motion; the other two coalesce repaints and warm icons.
            "ui/MapSurface.java", 3,
            // Draws the terminal once a frame while it has something new: a repaint, not an animation.
            "terminal/TerminalView.java", 1,
            // Waits two frames before a first render: a delay, not an animation.
            "editor/minimap/Minimap.java", 1,
            "ui/Motion.java", 0);

    private static final Pattern TRANSITION = Pattern.compile(
            "new\\s+(?:javafx\\.animation\\.)?(Fade|Translate|Scale|Rotate|Fill|Stroke|Path|Parallel|Sequential)Transition\\b");

    /**
     * Nothing animates the window behind Motion's back: no transition that moves or fades is built anywhere, and
     * every {@link AnimationTimer} is one of those listed above. A new one fails here until it either starts
     * through {@link Motion} or is listed with what it is.
     */
    @Test
    void everyAnimationIsKnownAndTheOneThatFlashesStartsThroughMotion() throws IOException {
        Path root = Path.of("src/main/java/com/editora");
        List<String> transitions = new ArrayList<>();
        Map<String, Integer> timers = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                String name = root.relativize(file).toString().replace('\\', '/');
                if (TRANSITION.matcher(source).find()) {
                    transitions.add(name);
                }
                Matcher timer = Pattern.compile("new\\s+AnimationTimer\\s*\\(").matcher(source);
                int count = 0;
                while (timer.find()) {
                    count++;
                }
                if (count > 0) {
                    timers.put(name, count);
                }
            }
        }
        assertTrue(transitions.isEmpty(), "start a transition through Motion and list it here: " + transitions);
        Map<String, Integer> expected = new TreeMap<>(ANIMATION_TIMERS);
        expected.values().removeIf(count -> count == 0);
        assertEquals(expected, timers, "an AnimationTimer that moves, fades or flashes starts through Motion.start");
        assertTrue(
                Files.readString(root.resolve("ui/MapSurface.java")).contains("Motion.start(layoutFlashTimer"),
                "the Project Map's flash starts through Motion");
    }
}
