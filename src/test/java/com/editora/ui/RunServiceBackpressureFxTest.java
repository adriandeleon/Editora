package com.editora.ui;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class RunServiceBackpressureFxTest {

    public static final class FloodMain {
        public static void main(String[] args) {
            if (args.length > 0 && "long-line".equals(args[0])) {
                System.out.print("x".repeat(2_000_000));
                return;
            }
            for (int i = 0; i < 20_000; i++) {
                System.out.println("x".repeat(100));
            }
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
        assertEquals(1, output.size());
        assertTrue(output.get(0).length() < 70_000, "the reader must not retain the complete giant line");
        assertTrue(output.get(0).endsWith("[line truncated]"));
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
