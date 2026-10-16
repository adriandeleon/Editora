package com.editora.process;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * A {@link TargetCache} this machine cannot see as a folder — a container's home — kept by POSIX commands run
 * on the target: {@code mkdir -p}, a file streamed through {@code cat} into a {@code .part} and moved in,
 * {@code rm -rf}. Every command gets the folder and the name as arguments, never inside its script.
 */
public final class ShellTargetCache implements TargetCache {

    static final Duration TIMEOUT = Duration.ofMinutes(2);

    private static final String MAKE = "mkdir -p -- \"$1/$2\"";
    /** Exit 0 when {@code $1/$2} is a file of {@code $3} bytes. */
    private static final String SAME_SIZE = "[ -f \"$1/$2\" ] && [ \"$(wc -c < \"$1/$2\" | tr -d ' ')\" = \"$3\" ]";
    /** Stdin into {@code $1/$2}, aside first; with {@code $3} = {@code keep}, an existing file stays (stdin drained). */
    private static final String PUT = "f=\"$1/$2\"\n"
            + "if [ \"$3\" = keep ] && [ -f \"$f\" ]; then cat > /dev/null; exit 0; fi\n"
            + "mkdir -p -- \"$(dirname -- \"$f\")\" && cat > \"$f.part\" && mv -f -- \"$f.part\" \"$f\"";

    private static final String DELETE = "rm -rf -- \"$1/$2\"";

    private static final Executor READERS =
            command -> Thread.ofVirtual().name("target-cache-read").start(command);

    private final ExecTarget target;
    private final String dir;

    /** @param dir the folder as the target names it, absolute */
    public ShellTargetCache(ExecTarget target, String dir) {
        if (dir == null || !dir.startsWith("/")) {
            throw new IllegalArgumentException("not an absolute folder: " + dir);
        }
        this.target = target;
        this.dir = dir.length() > 1 && dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
    }

    private String named(String relative) {
        return dir + "/" + TargetCache.checked(relative);
    }

    @Override
    public String makeDirectories(String relative) throws IOException {
        String path = named(relative);
        run(MAKE, null, relative).orThrow("could not make " + path);
        return path;
    }

    @Override
    public String putFile(String relative, Path localFile) throws IOException {
        String path = named(relative);
        long size = Files.size(localFile);
        if (run(SAME_SIZE, null, relative, Long.toString(size)).exit() != 0) {
            try (InputStream in = Files.newInputStream(localFile)) {
                run(PUT, in, relative, "replace").orThrow("could not write " + path);
            }
        }
        return path;
    }

    @Override
    public String putText(String relative, String text) throws IOException {
        String path = named(relative);
        run(PUT, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), relative, "keep")
                .orThrow("could not write " + path);
        return path;
    }

    @Override
    public void delete(String relative) throws IOException {
        String path = named(relative);
        run(DELETE, null, relative).orThrow("could not remove " + path);
    }

    private record Ran(int exit, String err) {
        void orThrow(String what) throws IOException {
            if (exit != 0) {
                throw new IOException(err.isBlank() ? what : what + ": " + err.strip());
            }
        }
    }

    /** {@code script} with the folder, then {@code args}, as {@code $1…}; {@code input} as its stdin. */
    private Ran run(String script, InputStream input, String... args) throws IOException {
        List<String> argv = new ArrayList<>(List.of("/bin/sh", "-c", script, "editora", dir));
        argv.addAll(List.of(args));
        Process p = target.start(ExecSpec.of(argv, null, Map.of(), ExecSpec.Locale.PARSE_STABLE));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> drain(p.getErrorStream()), READERS);
        CompletableFuture.runAsync(() -> drain(p.getInputStream()), READERS);
        try (OutputStream in = p.getOutputStream()) {
            if (input != null) {
                input.transferTo(in);
            }
        } catch (IOException stopped) {
            // it stopped reading: its exit says why
        }
        try {
            if (!p.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IOException("no answer from " + target.id() + " in " + TIMEOUT.toSeconds() + " s");
            }
            return new Ran(p.exitValue(), new String(err.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted", e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            return new Ran(p.exitValue(), "");
        }
    }

    private static byte[] drain(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
