package com.editora.pdf;

/**
 * A PNG on its way to a page (PDF or print) with what the layout needs to know without decoding it: its
 * size in pixels, how many of them make a logical pixel, and where it may be cut (see
 * {@link ImagePaging.Source}). Kept encoded so a 4,000-row tree is a few megabytes of PNG chunks, decoded
 * one at a time, rather than every chunk's pixels at once.
 */
public record PageImage(byte[] png, ImagePaging.Source source) {

    /** A PNG drawn one image pixel per point (its density is unknown). */
    public static PageImage of(byte[] png) {
        return of(png, 1);
    }

    /** A PNG rendered at {@code pixelScale} image pixels per logical pixel. */
    public static PageImage of(byte[] png, double pixelScale) {
        return new PageImage(png, new ImagePaging.Source(width(png), height(png), pixelScale, null, false));
    }

    /** A chunk of a row list: cut only at {@code safeCuts}; {@code continues} the chunk before it. */
    public static PageImage rows(byte[] png, double pixelScale, int[] safeCuts, boolean continues) {
        return new PageImage(png, new ImagePaging.Source(width(png), height(png), pixelScale, safeCuts, continues));
    }

    /** The pixel width from the PNG header ({@code IHDR}); 0 when {@code png} is not a PNG. */
    static int width(byte[] png) {
        return isPng(png) ? intAt(png, 16) : 0;
    }

    static int height(byte[] png) {
        return isPng(png) ? intAt(png, 20) : 0;
    }

    private static boolean isPng(byte[] b) {
        return b != null
                && b.length >= 24
                && (b[0] & 0xff) == 0x89
                && b[1] == 'P'
                && b[2] == 'N'
                && b[3] == 'G'
                && b[12] == 'I'
                && b[13] == 'H'
                && b[14] == 'D'
                && b[15] == 'R';
    }

    private static int intAt(byte[] b, int at) {
        int v = ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
        return Math.max(0, v);
    }
}
