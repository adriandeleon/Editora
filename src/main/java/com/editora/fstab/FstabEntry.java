package com.editora.fstab;

import java.util.List;

/**
 * One parsed {@code /etc/fstab} line: the six whitespace-separated columns (device spec, mount point,
 * filesystem type, options, dump, fsck pass). {@code dump}/{@code pass} default to 0 when omitted. A
 * malformed line carries a non-null {@link #error} (and its other fields are best-effort). Pure record;
 * the human-readable decoding lives in {@link FstabDescribe}.
 */
public record FstabEntry(
        String spec,
        String mountPoint,
        String fsType,
        List<String> options,
        int dump,
        int pass,
        String error,
        int line) {

    public boolean ok() {
        return error == null;
    }

    /** Whether this entry describes swap space (filesystem type {@code swap}). */
    public boolean isSwap() {
        return "swap".equalsIgnoreCase(fsType);
    }
}
