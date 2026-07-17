package com.editora.sshconfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses an SSH client config ({@code ~/.ssh/config} / {@code ssh_config}) into ordered blocks for the
 * preview: a leading {@code global} block (options before the first {@code Host}/{@code Match}), then one
 * block per {@code Host <patterns>} / {@code Match <criteria>}. Each option is a {@code Keyword value}
 * line (whitespace- or {@code =}-separated; keywords are case-insensitive). Comments ({@code #}) and blanks
 * are dropped. Pure, java.base-only, unit-tested.
 */
public final class SshConfig {

    public record Option(String key, String value, int line) {}

    /** A block: {@code type} is {@code "global"}, {@code "Host"}, or {@code "Match"}; {@code argument} is its patterns/criteria. */
    public record Block(String type, String argument, List<Option> options) {

        public String first(String key) {
            for (Option o : options) {
                if (o.key().equalsIgnoreCase(key)) {
                    return o.value();
                }
            }
            return null;
        }
    }

    private SshConfig() {}

    /**
     * The effective value ssh would use for {@code key} when connecting to {@code host}, applying the config's
     * <b>first-obtained-value-wins</b> rule across the whole file: walk blocks in file order (the leading
     * {@code global} block matches every host; a {@code Host} block matches per {@link SshPattern}), and return
     * the first value any matching block sets for {@code key}. {@code Match} blocks are skipped (their criteria
     * — {@code exec}/{@code host}/{@code user}/… — aren't modelled). {@code null} if nothing sets it. Pure.
     */
    public static String effective(List<Block> blocks, String host, String key) {
        if (blocks == null || host == null) {
            return null;
        }
        for (Block b : blocks) {
            boolean applies =
                    switch (b.type()) {
                        case "global" -> true;
                        case "Host" -> SshPattern.matches(b.argument(), host);
                        default -> false; // Match blocks: criteria not modelled
                    };
            if (applies) {
                String v = b.first(key);
                if (v != null) {
                    return v; // first obtained value wins
                }
            }
        }
        return null;
    }

    /** Whether a {@code Host} block's argument is a single concrete host name (one token, no {@code * ? !}) —
     *  the only case where a per-block behaviour summary resolves to one connection. Pure. */
    public static boolean isConcreteHost(String argument) {
        if (argument == null) {
            return false;
        }
        String a = argument.strip();
        return !a.isEmpty()
                && a.indexOf(' ') < 0
                && a.indexOf('\t') < 0
                && a.indexOf('*') < 0
                && a.indexOf('?') < 0
                && a.indexOf('!') < 0;
    }

    public static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        Block global = new Block("global", "", new ArrayList<>());
        Block current = global;
        if (text != null) {
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] kv = splitKeyValue(line);
                if (kv == null) {
                    continue;
                }
                String key = kv[0];
                String value = kv[1];
                if (key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Match")) {
                    current = new Block(key.equalsIgnoreCase("Host") ? "Host" : "Match", value, new ArrayList<>());
                    blocks.add(current);
                } else {
                    current.options().add(new Option(key, value, i + 1));
                }
            }
        }
        // Only include the global block when it actually carries options.
        if (!global.options().isEmpty()) {
            blocks.add(0, global);
        }
        return blocks;
    }

    /** Splits "Keyword value" or "Keyword=value" into {@code {key, value}}, or {@code null} if there's no value. */
    private static String[] splitKeyValue(String line) {
        int i = 0;
        int n = line.length();
        while (i < n && !isSep(line.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        String key = line.substring(0, i);
        while (i < n && isSep(line.charAt(i))) {
            i++;
        }
        String value = line.substring(i).strip();
        // Strip surrounding quotes on the value (ssh_config allows quoting args with spaces).
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        return new String[] {key, value};
    }

    private static boolean isSep(char c) {
        return c == ' ' || c == '\t' || c == '=';
    }
}
