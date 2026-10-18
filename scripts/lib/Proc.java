package lib;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Runs a command to completion and captures what it printed. */
public final class Proc {

    /** The exit code and the two output streams, decoded as UTF-8. */
    public record Result(int code, String out, String err) {}

    private Proc() {}

    public static Result run(List<String> command) {
        return run(command, null, null, false);
    }

    /**
     * Runs {@code command} in {@code cwd} (null: the current directory). The variables in {@code env} are added
     * to this process's environment, or replace it altogether when {@code replaceEnv} is set.
     */
    public static Result run(List<String> command, Map<String, String> env, Path cwd, boolean replaceEnv) {
        var builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        if (env != null) {
            if (replaceEnv) {
                builder.environment().clear();
            }
            builder.environment().putAll(env);
        }
        try {
            Process process = builder.start();
            process.getOutputStream().close();
            var out = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
            var err = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
            int code = process.waitFor();
            return new Result(code, out.join(), err.join());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Like {@link #run(List)}, but fails unless the command exits 0; returns its standard output. */
    public static String check(List<String> command) {
        Result result = run(command);
        if (result.code() != 0) {
            throw new IllegalStateException(
                    String.join(" ", command) + " exited " + result.code() + ": " + result.err().strip());
        }
        return result.out();
    }

    private static String read(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
