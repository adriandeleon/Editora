package com.editora.config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * A second JVM for {@link InstanceLockTest}: claims the config dir given as its argument, prints the role it
 * got, then answers {@code probe} lines with what it currently sees, until stdin closes.
 *
 * <p>Two JVMs are the only honest way to check an OS file lock: within one JVM a conflicting range is refused
 * by the JVM's own lock table before the operating system is ever asked.
 */
public final class InstanceLockProbe {

    private InstanceLockProbe() {}

    public static void main(String[] args) throws Exception {
        try (InstanceLock lock = InstanceLock.claim(Path.of(args[0]))) {
            System.out.println(lock.primary() ? "primary" : "secondary");
            System.out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("probe")) {
                    System.out.println("others=" + lock.othersPresent());
                    System.out.flush();
                }
            }
        }
    }
}
