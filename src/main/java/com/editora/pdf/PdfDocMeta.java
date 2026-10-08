package com.editora.pdf;

/**
 * What a PDF says about itself, supplied by the caller: the document name, its language and how a page is
 * labelled in the footer. The writers live below the UI and the message catalogs, so the localised
 * "Page 2 of 7" arrives as a formatter rather than being built here.
 *
 * @param title the document name without its folder (the PDF's Title, and the left side of the footer); may
 *     be null
 * @param language a BCP 47 tag for the document catalog's {@code /Lang} (e.g. {@code "es"}); may be null
 * @param pageLabel formats the footer's page label; null for the language-neutral {@code "2 / 7"}
 */
public record PdfDocMeta(String title, String language, PageLabel pageLabel) {

    /** Formats a footer page label from the 1-based page number and the page count. */
    @FunctionalInterface
    public interface PageLabel {
        String format(int page, int pages);
    }

    /** No title, no language, the neutral page label. */
    public static final PdfDocMeta NONE = new PdfDocMeta(null, null, null);

    /** The footer label for {@code page} of {@code pages}; a formatter that fails yields the neutral form. */
    String label(int page, int pages) {
        if (pageLabel != null) {
            try {
                String s = pageLabel.format(page, pages);
                if (s != null && !s.isBlank()) {
                    return s;
                }
            } catch (RuntimeException e) {
                // fall through: a broken message pattern must not cost the export
            }
        }
        return page + " / " + pages;
    }
}
