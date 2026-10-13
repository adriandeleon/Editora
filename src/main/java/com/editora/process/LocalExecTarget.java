package com.editora.process;

import java.io.IOException;
import java.nio.file.Path;

/** This machine: a {@link ProcessBuilder} with one of {@link ProcessRunner}'s two child environments. */
final class LocalExecTarget implements ExecTarget {

    @Override
    public String id() {
        return "local";
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Process start(ExecSpec spec) throws IOException {
        // Resolve a bare command name to an absolute path against the augmented PATH: on Unix
        // ProcessBuilder searches the JVM's (stripped, GUI-launched) PATH for the executable, not the
        // child env we set below — so without this, mmdc/npx still wouldn't be found.
        ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(spec.argv()));
        if (spec.scrubSecrets()) {
            SecretEnv.scrub(pb.environment());
        }
        if (spec.workingDir() != null) {
            pb.directory(spec.workingDir().toFile());
        }
        ProcessRunner.childEnvironment(pb.environment(), spec.locale() == ExecSpec.Locale.USER, spec.env());
        if (spec.discardStdout()) {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        if (spec.discardStderr()) {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        return pb.start();
    }

    @Override
    public PortForward forwardLocal(String host, int port) {
        return new PortForward() {
            @Override
            public int localPort() {
                return port;
            }

            @Override
            public void close() {
                // the port is the program's own; nothing was opened here
            }
        };
    }

    @Override
    public String toTargetPath(Path path) {
        return path.toString();
    }

    @Override
    public Path toPath(String targetPath) {
        return Path.of(targetPath);
    }
}
