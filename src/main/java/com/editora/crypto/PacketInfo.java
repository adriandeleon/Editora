package com.editora.crypto;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who an encrypted message is for, read from {@code gpg --list-packets --list-only} without decrypting it: the
 * key ids of its public-key session packets (encryption <em>subkeys</em>, mapped to people with
 * {@link GpgKey#matches}) and whether it also opens with a passphrase. Pure.
 */
public record PacketInfo(List<String> keyIds, boolean passphrase) {

    private static final Pattern PUBKEY = Pattern.compile(":pubkey enc packet:.*?keyid ([0-9A-Fa-f]{8,40})");

    public PacketInfo {
        keyIds = List.copyOf(keyIds);
    }

    public static PacketInfo parse(String listing) {
        List<String> ids = new ArrayList<>();
        boolean symmetric = false;
        if (listing != null) {
            for (String line : listing.split("\r?\n")) {
                Matcher m = PUBKEY.matcher(line);
                if (m.find()) {
                    String id = m.group(1).toUpperCase(java.util.Locale.ROOT);
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } else if (line.startsWith(":symkey enc packet:")) {
                    symmetric = true;
                }
            }
        }
        return new PacketInfo(ids, symmetric);
    }

    /** A message that only a passphrase opens. */
    public boolean passphraseOnly() {
        return passphrase && keyIds.isEmpty();
    }

    /**
     * The key ids that are not "anonymous" ({@code 0000000000000000}, a hidden recipient that gpg tries every
     * secret key against).
     */
    public List<String> namedKeyIds() {
        return keyIds.stream().filter(id -> !id.chars().allMatch(c -> c == '0')).toList();
    }
}
