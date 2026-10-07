package com.editora.recovery;

/**
 * Everything needed to bring one unsaved buffer back after the process died: where it belongs, how it would
 * have been saved, what on disk the edits were made against, and the text itself.
 *
 * @param bufferId stable for the life of one open buffer; the record's file name
 * @param path the file's storable path ({@code Vfs.toStorableString}), or {@code null} for an untitled buffer
 * @param title the tab title, shown in the offer
 * @param displayName an untitled buffer's suggested file name, or {@code null}
 * @param charset the charset label the buffer would save with
 * @param bom whether that save writes a byte-order mark where the charset allows one
 * @param lineEnding {@code LF}, {@code CRLF} or {@code CR}: the line ending the buffer would save with
 * @param baseModifiedMillis modified time of the file as last loaded or saved; negative when none was recorded
 * @param baseSize size of the file as last loaded or saved
 * @param baseFingerprint SHA-256 of the file's bytes as last loaded or saved, or {@code null}
 * @param savedAtMillis when this copy of the text was taken
 * @param caret caret offset in {@code text}
 * @param windowKey the window the buffer was open in ({@code ""} = the no-project window)
 * @param text the whole document with {@code \n} line ends; {@code null} in a listing that left it on disk
 */
public record RecoveryRecord(
        String bufferId,
        String path,
        String title,
        String displayName,
        String charset,
        boolean bom,
        String lineEnding,
        long baseModifiedMillis,
        long baseSize,
        String baseFingerprint,
        long savedAtMillis,
        int caret,
        String windowKey,
        String text) {

    /** Whether the buffer had no file yet. */
    public boolean untitled() {
        return path == null;
    }

    /** This record without its text: what a listing keeps in memory. */
    public RecoveryRecord withoutText() {
        return text == null
                ? this
                : new RecoveryRecord(
                        bufferId,
                        path,
                        title,
                        displayName,
                        charset,
                        bom,
                        lineEnding,
                        baseModifiedMillis,
                        baseSize,
                        baseFingerprint,
                        savedAtMillis,
                        caret,
                        windowKey,
                        null);
    }
}
