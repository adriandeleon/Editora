package com.editora.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AcpClient}'s writes against a real child process. A prompt, Stop and a model switch are all sent
 * from the FX thread, and the client used to write them straight into the agent's stdin pipe: an agent that
 * was not reading — busy with a turn — blocked the caller, and with it the Stop button that was meant to
 * interrupt the turn.
 */
@DisabledOnOs(OS.WINDOWS)
class AcpClientWriteProcessTest {

    /** The agent's side: either never reads its stdin, or copies each line it is sent to a file. */
    public static final class Peer {
        public static void main(String[] args) throws Exception {
            if ("deaf".equals(args[0])) {
                Thread.sleep(Long.MAX_VALUE);
                return;
            }
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    BufferedWriter log = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
                String line;
                while ((line = in.readLine()) != null) {
                    // Only what identifies the message: its position in the stream is what the test checks.
                    var id = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(line);
                    log.write(line.contains("session/cancel") ? "cancel" : id.find() ? "id " + id.group(1) : "?");
                    log.newLine();
                    log.flush();
                }
            }
        }
    }

    private static final AcpClient.Host NO_HOST = new AcpClient.Host() {
        @Override
        public void onUpdate(AcpJson.Update update) {}

        @Override
        public void onExit(int code) {}

        @Override
        public String readTextFile(String path, Integer line, Integer limit) {
            return "";
        }

        @Override
        public void writeTextFile(String path, String content) {}

        @Override
        public CompletableFuture<String> requestPermission(String title, List<AcpJson.PermissionOption> options) {
            return CompletableFuture.completedFuture(null);
        }
    };

    private static AcpClient client(Path dir, String... peerArgs) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Peer.class.getName()));
        command.addAll(List.of(peerArgs));
        return new AcpClient(command, dir, NO_HOST);
    }

    /**
     * 8 MB of prompts to an agent that reads nothing: far more than a pipe holds (64 KiB on Linux), so a
     * direct write blocks on the second prompt and never returns.
     */
    @Test
    void sendingToAnAgentThatIsNotReadingDoesNotBlockTheCaller(@TempDir Path dir) {
        AcpClient client = client(dir, "deaf");
        assertTrue(client.start(), "the test agent did not launch");
        String prompt = "x".repeat(100_000);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                for (int i = 0; i < 80; i++) {
                    client.prompt("session", prompt);
                }
                client.cancel("session"); // Stop: must get through the same door without waiting
            });
        } finally {
            assertTimeoutPreemptively(Duration.ofSeconds(20), client::dispose);
        }
    }

    /** Moving the write to another thread must not reorder anything: a turn's Stop follows its prompt. */
    @Test
    void messagesReachTheAgentInTheOrderTheyWereSent(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("received.log");
        AcpClient client = client(dir, "record", log.toString());
        assertTrue(client.start(), "the test agent did not launch");
        int prompts = 300;
        try {
            for (int i = 0; i < prompts; i++) {
                client.prompt("session", "p" + i);
            }
            client.cancel("session");

            List<String> received = List.of();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                received = Files.exists(log) ? Files.readAllLines(log) : List.of();
                if (received.size() > prompts) {
                    break;
                }
                // The other process writes the file: there is nothing in this JVM to wait on but the clock.
                TimeUnit.MILLISECONDS.sleep(20);
            }

            assertEquals(prompts + 1, received.size(), "every message arrives");
            for (int i = 0; i < prompts; i++) {
                // Request ids are handed out in call order, starting at 1.
                assertEquals("id " + (i + 1), received.get(i));
            }
            assertEquals("cancel", received.get(prompts), "the cancel is last, behind every prompt");
        } finally {
            client.dispose();
        }
        assertFalse(client.isAlive());
    }
}
