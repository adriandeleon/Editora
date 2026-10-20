package com.editora.vfs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.SocketAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.AttributeRepository;
import org.jspecify.annotations.Nullable;

/**
 * {@code ~/.ssh/config} for the shared client, read as MINA reads it but with two of {@code ssh}'s habits MINA
 * lacks: an {@code Include} line brings in the files it names, and a {@code Match} section is skipped.
 *
 * <p>MINA's own resolver refuses a whole file that has a {@code Match} section — "Currently not able to process
 * Match sections" — for every host, so before this anyone with one (1Password's setup writes one) could not
 * connect at all. A skipped section is what {@code ssh} would do for a {@code Match} whose condition does not
 * hold; its options are lost for the hosts it would have matched, which is the trade for connecting at all.
 *
 * <p>The text is read again only when the config file, or a file it includes, changes.
 */
final class SshConfigFile implements HostConfigEntryResolver {

    private static final Logger LOG = Logger.getLogger(SshConfigFile.class.getName());

    /** How deep {@code Include}s may nest, as {@code ssh}'s limit. */
    private static final int MAX_DEPTH = 16;

    private static final Pattern INCLUDE = Pattern.compile("(?i)^\\s*include(?:\\s*=\\s*|\\s+)(.+)$");
    private static final Pattern SECTION = Pattern.compile("(?i)^\\s*(host|match)(?:\\s*=\\s*|\\s+).*$");

    private final Path config;

    /** What was read, from which files as they were then. */
    private record Read(List<Path> files, List<FileTime> stamps, String text, HostConfigEntryResolver resolver) {}

    private volatile @Nullable Read read;

    SshConfigFile(Path config) {
        this.config = config;
    }

    @Override
    public HostConfigEntry resolveEffectiveHost(
            String host,
            int port,
            @Nullable SocketAddress local,
            @Nullable String username,
            @Nullable String proxyJump,
            @Nullable AttributeRepository context)
            throws IOException {
        return current().resolver().resolveEffectiveHost(host, port, local, username, proxyJump, context);
    }

    /** The config as the resolver sees it: includes in place, {@code Match} sections gone. Empty with no file. */
    String text() {
        return current().text();
    }

    private Read current() {
        Read r = read;
        if (r != null && unchanged(r)) {
            return r;
        }
        List<Path> files = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        expand(config, 0, files, text);
        String kept = withoutMatchSections(text.toString());
        HostConfigEntryResolver resolver;
        try {
            List<HostConfigEntry> entries =
                    HostConfigEntry.readHostConfigEntries(new BufferedReader(new StringReader(kept)));
            resolver = HostConfigEntry.toHostConfigEntryResolver(entries);
        } catch (IOException | RuntimeException e) {
            // A config ssh would also reject: connect as if there were none, rather than not at all.
            LOG.log(Level.WARNING, "Ignoring " + config + ": " + e.getMessage(), e);
            resolver = HostConfigEntryResolver.EMPTY;
        }
        r = new Read(List.copyOf(files), stamps(files), kept, resolver);
        read = r;
        return r;
    }

    private static boolean unchanged(Read r) {
        return r.stamps().equals(stamps(r.files()));
    }

    private static List<FileTime> stamps(List<Path> files) {
        List<FileTime> out = new ArrayList<>();
        for (Path f : files) {
            try {
                out.add(Files.getLastModifiedTime(f));
            } catch (IOException | RuntimeException e) {
                out.add(FileTime.fromMillis(-1)); // gone (or never there): a change if it appears
            }
        }
        return out;
    }

    /** Appends {@code file}'s lines to {@code out}, each {@code Include} replaced by the files it names. */
    private void expand(Path file, int depth, List<Path> files, StringBuilder out) {
        files.add(file);
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException | RuntimeException e) {
            return; // no such file (no config at all is the usual case), or unreadable: nothing from it
        }
        for (String line : lines) {
            Matcher include = INCLUDE.matcher(line);
            if (include.matches() && depth < MAX_DEPTH) {
                for (String name : include.group(1).strip().split("\\s+")) {
                    for (Path included : resolveInclude(unquote(name))) {
                        expand(included, depth + 1, files, out);
                    }
                }
            } else if (!include.matches()) {
                out.append(line).append('\n');
            }
        }
    }

    /**
     * The files an {@code Include} names: {@code ~} is home, a relative name is under {@code ~/.ssh}; a glob in the
     * last segment lists that directory, sorted. Glob characters never reach {@code Path.of} — Windows refuses
     * {@code *} and {@code ?} in a path — so only the directory part is turned into a path.
     */
    private List<Path> resolveInclude(String name) {
        Path sshDir = config.toAbsolutePath().getParent();
        if (sshDir == null) {
            return List.of(); // the config is a filesystem root, not a file
        }
        Path home = sshDir.getParent();
        Path base = sshDir;
        String rest = name;
        if (name.equals("~") || name.startsWith("~/")) {
            base = home;
            rest = name.length() <= 2 ? "" : name.substring(2);
        }
        String[] split = splitLeaf(rest, File.separatorChar == '\\');
        String leaf = split[1];
        try {
            if (base == null) {
                return List.of(); // "~" with ~/.ssh at the top of the tree: there is no home above it
            }
            Path dir = split[0].isEmpty() ? base : base.resolve(split[0]); // an absolute directory replaces base
            if (!isGlob(leaf)) {
                return List.of(leaf.isEmpty() ? dir : dir.resolve(leaf));
            }
            List<Path> matches = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, leaf)) {
                entries.forEach(p -> {
                    if (Files.isRegularFile(p)) {
                        matches.add(p);
                    }
                });
            }
            matches.sort(null);
            return matches;
        } catch (IOException | RuntimeException e) {
            return List.of(); // a glob in a directory segment, a missing directory, a bad pattern: nothing from it
        }
    }

    /** {@code name} as {@code {directory part with its trailing separator, last segment}}. Pure. */
    static String[] splitLeaf(String name, boolean backslashToo) {
        int cut = name.lastIndexOf('/');
        if (backslashToo) {
            cut = Math.max(cut, name.lastIndexOf('\\'));
        }
        return new String[] {name.substring(0, cut + 1), name.substring(cut + 1)};
    }

    static boolean isGlob(String segment) {
        return segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0 || segment.indexOf('[') >= 0;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** {@code text} with every {@code Match} section — from its line to the next {@code Host} — left out. Pure. */
    static String withoutMatchSections(String text) {
        StringBuilder out = new StringBuilder();
        boolean skipping = false;
        for (String line : text.split("\n", -1)) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                skipping = section.group(1).toLowerCase(Locale.ROOT).equals("match");
            }
            if (!skipping) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
