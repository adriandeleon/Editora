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

    private static final class Counting extends Motion.Animation {
        int frames;
        int ended;

        @Override
        public void handle(long now) {
            frames++;
        }

        @Override
        protected void atEnd() {
            ended++;
        }
    }

    @Test
    void withReducedMotionAnAnimationDoesNotRunAndItsEndStateIsShownAtOnce() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Motion.setReducedForTest(true);
            Counting animation = new Counting();
            Motion.start(animation);
            assertEquals(1, animation.ended, "the end state, at once");
            assertEquals(0, animation.frames);
            Motion.stopAll();
            assertEquals(1, animation.ended, "it never started: there is nothing to stop");
        });
    }

    @Test
    void turningTheSettingOnStopsWhatIsRunningAndShowsItsEndState() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Motion.setReducedForTest(false);
            Counting running = new Counting();
            Counting finished = new Counting();
            Motion.start(running);
            Motion.start(finished);
            assertEquals(0, running.ended + finished.ended, "motion is allowed: both run");
            finished.stop();
            Motion.ended(finished); // ran to its own end

            Motion.stopAll(); // what the setting's listener does when it turns on
            assertEquals(1, running.ended, "the running one is stopped at its end state");
            assertEquals(0, finished.ended, "the finished one is left alone");
            Motion.stopAll();
            assertEquals(1, running.ended, "once");
        });
    }

    /**
     * The registry of running animations keeps none of them alive: an animation whose window is gone is
     * collected, running or not, and with it everything it points at.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aRunningAnimationIsNotKeptAliveByTheRegistry() throws Exception {
        java.lang.ref.WeakReference<Counting>[] ref = new java.lang.ref.WeakReference[1];
        FxTestSupport.runOnFx(() -> {
            Motion.setReducedForTest(false);
            Counting animation = new Counting();
            Motion.start(animation);
            animation.stop(); // its owner closed without telling Motion
            ref[0] = new java.lang.ref.WeakReference<>(animation);
        });
        for (int i = 0; i < 50 && ref[0].get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertTrue(ref[0].get() == null, "the registry holds its animations weakly");
    }

    /** The files that hold an {@link AnimationTimer}, with how many, and what each is. */
    private static final Map<String, Integer> ANIMATION_TIMERS = Map.of(
            // These two coalesce repaints and warm icons; the flash of a revealed row is a Motion.Animation.
            "ui/MapSurface.java", 2,
            // Draws the terminal once a frame while it has something new: a repaint, not an animation.
            "terminal/TerminalView.java", 1,
            // Waits two frames before a first render: a delay, not an animation.
            "editor/minimap/Minimap.java", 1);

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
        assertEquals(
                new TreeMap<>(ANIMATION_TIMERS),
                timers,
                "an AnimationTimer that moves, fades or flashes is a Motion.Animation, started with Motion.start");
        assertTrue(
                Files.readString(root.resolve("ui/MapSurface.java")).contains("Motion.start(layoutFlashTimer"),
                "the Project Map's flash starts through Motion");
    }
}
