package com.editora.container;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * How a container sees folders of this machine: its bind mounts, each a folder here ({@code source}) and the
 * path it has inside ({@code destination}). A file under a mounted folder has a name on both sides; anything
 * else exists on one side only. Pure.
 */
public record ContainerMounts(List<Mount> mounts) {

    /** One bind mount: {@code source} on this machine is {@code destination} in the container. */
    public record Mount(Path source, String destination) {
        public Mount {
            source = source.toAbsolutePath().normalize();
            destination = destination.endsWith("/") && destination.length() > 1
                    ? destination.substring(0, destination.length() - 1)
                    : destination;
        }
    }

    public ContainerMounts {
        // The deepest mount first: a folder mounted inside another mounted folder answers for its own files.
        mounts = mounts.stream()
                .sorted(Comparator.comparingInt((Mount m) -> m.source().getNameCount())
                        .reversed())
                .toList();
    }

    /** The container's name for {@code local}, or null when no mount covers it. */
    public String toContainer(Path local) {
        Path abs = local.toAbsolutePath().normalize();
        for (Mount m : mounts) {
            if (abs.startsWith(m.source())) {
                Path rel = m.source().relativize(abs);
                String tail = rel.toString().replace('\\', '/');
                return tail.isEmpty() ? m.destination() : join(m.destination(), tail);
            }
        }
        return null;
    }

    /** This machine's file for the container path {@code inside}, or null when no mount covers it. */
    public Path toLocal(String inside) {
        if (inside == null || !inside.startsWith("/")) {
            return null;
        }
        Mount best = null;
        for (Mount m : mounts) {
            String dest = m.destination();
            boolean under = inside.equals(dest) || inside.startsWith(dest.equals("/") ? "/" : dest + "/");
            if (under && (best == null || dest.length() > best.destination().length())) {
                best = m;
            }
        }
        if (best == null) {
            return null;
        }
        String tail = inside.substring(best.destination().length()).replaceFirst("^/+", "");
        return tail.isEmpty() ? best.source() : best.source().resolve(tail).normalize();
    }

    /** Whether some mount covers {@code local}. */
    public boolean covers(Path local) {
        return toContainer(local) != null;
    }

    private static String join(String dir, String tail) {
        return dir.equals("/") ? "/" + tail : dir + "/" + tail;
    }
}
