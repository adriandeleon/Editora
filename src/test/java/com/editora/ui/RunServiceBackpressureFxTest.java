package com.editora.ui;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.run.RunService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class RunServiceBackpressureFxTest {

    public static final class FloodMain {
        public static void main(String[] args) {
            if (args.length > 0 && "long-line".equals(args[0])) {
                System.out.print("x".repeat(2_000_000));
                return;
            }
            if (args.length > 0 && "numbered".equals(args[0])) {
                for (int i = 0; i < 40_000; i++) {
                    System.out.println("line " + i + " " + "x".repeat(50)); // about 60 characters a line
                }
                return;
            }
            for (int i = 0; i < 20_000; i++) {
                System.out.println("x".repeat(100));
            }
        }
    }

    public static final class PromptMain {
        public static void main(String[] args) throws Exception {
            System.out.print("Name: ");
            System.out.flush();
            String name = new BufferedReader(new InputStreamReader(System.in)).readLine();
            System.out.println("Hello " + name);
        }
    }

    public static final class CatMain {
        public static void main(String[] args) throws Exception {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                System.out.println(line);
            }
            System.out.println("eof");
        }
    }

    /**
     * Each line used to be written by a thread of its own, so two lines sent in quick succession could reach
     * the program swapped — and an end-of-input could overtake the line before it.
     */
    @Test
    void linesReachTheProgramInTheOrderTheyWereSentAndEndOfInputComesLast() throws Exception {
        RunService service = new RunService();
        CountDownLatch exited = new CountDownLatch(1);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"), CatMain.class.getName());
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            sent.add("line " + i);
        }
        try {
            FxTestSupport.runOnFx(() -> {
                service.runInDir(Path.of("."), command, new RunService.Listener() {
                    @Override
                    public void onStart(String commandLine) {}

                    @Override
                    public void onOutput(String line, boolean stderr) {
                        output.add(line);
                    }

                    @Override
                    public void onExit(int code) {
                        exited.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        throw new AssertionError(message);
                    }
                });
                sent.forEach(service::sendInput);
                service.closeInput();
            });
            assertTrue(exited.await(30, TimeUnit.SECONDS), "the program saw the end of its input");
            List<String> expected = new ArrayList<>(sent);
            expected.add("eof");
            assertEquals(expected, new ArrayList<>(output));
        } finally {
            service.stop();
        }
    }

    @Test
    void promptIsDeliveredBeforeTheProgramReceivesInput() throws Exception {
        RunService service = new RunService();
        CountDownLatch prompt = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"), PromptMain.class.getName());
        try {
            FxTestSupport.runOnFx(() -> service.runInDir(Path.of("."), command, new RunService.Listener() {
                @Override
                public void onStart(String commandLine) {}

                @Override
                public void onOutput(String line, boolean stderr) {
                    output.add(line);
                }

                @Override
                public void onPartialOutput(String text, boolean stderr) {
                    output.add(text);
                    prompt.countDown();
                }

                @Override
                public void onExit(int code) {
                    exited.countDown();
                }

                @Override
                public void onError(String message) {
                    throw new AssertionError(message);
                }
            }));
            assertTrue(prompt.await(5, TimeUnit.SECONDS), "the prompt must appear before input");
            assertEquals("Name: ", output.get(0));
            service.sendInput("Ada");
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertTrue(output.contains("Hello Ada"));
        } finally {
            service.stop();
        }
    }

    @Test
    void aSingleUnterminatedLineIsCappedBeforeDelivery() throws Exception {
        RunService service = new RunService();
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch exited = new CountDownLatch(1);
        List<String> command = javaCommand("long-line");

        FxTestSupport.runOnFx(() -> service.runInDir(Path.of("."), command, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {}

            @Override
            public void onOutput(String line, boolean stderr) {
                output.add(line);
            }

            @Override
            public void onExit(int code) {
                exited.countDown();
            }

            @Override
            public void onError(String message) {
                throw new AssertionError(message);
            }
        }));

        assertTrue(exited.await(10, TimeUnit.SECONDS));
        // The line usually arrives as one piece. It need not: the pump hands over what it holds whenever the
        // child has written nothing for 75 ms (so a prompt shows), and a child starved of CPU can pause that
        // long in the middle of its one print — the line then arrives as "what there was" plus the capped
        // rest. Counting pieces was a bet on the scheduler; what the cap promises holds for every piece.
        List<String> pieces = List.copyOf(output);
        assertFalse(pieces.isEmpty());
        for (String piece : pieces) {
            assertTrue(piece.length() < 70_000, "the reader must not retain the complete giant line");
        }
        assertTrue(
                pieces.stream().anyMatch(piece -> piece.endsWith("[line truncated]")),
                "the piece that reached the cap says the line was cut");
        long delivered = pieces.stream()
                .mapToLong(piece -> piece.replace(com.editora.process.OutputPump.LINE_TRUNCATED, "")
                        .length())
                .sum();
        assertTrue(delivered < 2_000_000 / 2, "the excess is dropped, not delivered in slices: " + delivered);
        assertTrue(
                pieces.stream()
                        .allMatch(piece -> piece.replace(com.editora.process.OutputPump.LINE_TRUNCATED, "")
                                .chars()
                                .allMatch(c -> c == 'x')),
                "nothing but the program's own output and the marker");
    }

    private static List<String> javaCommand(String... args) {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        List<String> command =
                new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"), FloodMain.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void outputFloodIsBoundedWhileFxThreadIsBusyAndExitRemainsOrdered() throws Exception {
        RunService service = new RunService();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);

        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"), FloodMain.class.getName());
        Platform.runLater(() -> {
            service.runInDir(Path.of("."), command, new RunService.Listener() {
                @Override
                public void onStart(String commandLine) {
                    events.add("start");
                    started.countDown();
                }

                @Override
                public void onOutput(String line, boolean stderr) {
                    events.add(line);
                }

                @Override
                public void onExit(int code) {
                    events.add("exit:" + code);
                    exited.countDown();
                }

                @Override
                public void onError(String message) {
                    events.add("error:" + message);
                    exited.countDown();
                }
            });
            try {
                releaseFx.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));
        Process process = awaitProcess(service);
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        releaseFx.countDown();
        assertTrue(exited.await(10, TimeUnit.SECONDS));

        assertTrue(events.contains("[output truncated while the UI was busy]"));
        assertTrue(events.size() < 5_000, "the FX queue must stay bounded, got " + events.size());
        assertEquals("exit:0", events.get(events.size() - 1));
    }

    /**
     * A flood the console cannot keep up with drops queued lines from the middle (the program is never slowed),
     * and says so with a marker. What is queued when the flood ends must still cover a whole console (its
     * 200,000-character cap): the queue used to stop at 2,048 lines, about 120,000 characters of 60-character
     * lines, so the newest screenful the console kept was itself a patchwork with a gap in it.
     */
    @Test
    void afterAFloodTheNewestConsolefulIsContiguous() throws Exception {
        RunService service = new RunService();
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        CountDownLatch releaseFx = new CountDownLatch(1);
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        List<String> command =
                List.of(java, "-cp", System.getProperty("java.class.path"), FloodMain.class.getName(), "numbered");
        Platform.runLater(() -> {
            service.runInDir(Path.of("."), command, new RunService.Listener() {
                @Override
                public void onStart(String commandLine) {
                    started.countDown();
                }

                @Override
                public void onOutput(String line, boolean stderr) {
                    lines.add(line);
                }

                @Override
                public void onExit(int code) {
                    exited.countDown();
                }

                @Override
                public void onError(String message) {
                    exited.countDown();
                }
            });
            try {
                releaseFx.await(20, TimeUnit.SECONDS); // the UI is busy for the whole flood
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Process process = awaitProcess(service);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        Thread.sleep(300);
        releaseFx.countDown();
        assertTrue(exited.await(20, TimeUnit.SECONDS));

        List<String> all = new ArrayList<>(lines);
        assertTrue(all.contains(com.editora.process.OutputPump.OUTPUT_DROPPED), "the flood did overflow");
        int chars = 0;
        int expected = 39_999;
        for (int i = all.size() - 1; i >= 0 && chars < 200_000; i--) {
            String line = all.get(i);
            assertEquals("line " + expected + " " + "x".repeat(50), line, "a gap inside the last 200,000 characters");
            expected--;
            chars += line.length() + 1;
        }
        service.shutdown();
    }

    private static Process awaitProcess(RunService service) throws Exception {
        for (int i = 0; i < 100; i++) {
            Process process = FxTestSupport.field(service, "current");
            if (process != null) {
                return process;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("run process never started");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
