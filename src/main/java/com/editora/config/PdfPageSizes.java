package com.editora.config;

import java.util.Locale;
import java.util.Set;

/**
 * The PDF page size a <em>new</em> installation starts with: the paper of the user's region. Pure.
 *
 * <p>Only the default follows the region. A value already stored in {@code settings.json} is the user's and
 * is never changed (see the v115 → v116 settings migration).
 */
public final class PdfPageSizes {

    public static final String LETTER = "letter";
    public static final String A4 = "a4";

    /**
     * Regions whose everyday paper is US Letter: the CLDR {@code paperSize type="US-Letter"} territories
     * (BZ CA CL CO CR GT MX NI PA PH PR SV US VE), the Dominican Republic, and the US territories that take
     * their stationery from the mainland. Everywhere else uses ISO A4.
     */
    private static final Set<String> LETTER_REGIONS = Set.of(
            "US", "CA", "MX", "PH", "CL", "CO", "VE", "CR", "PA", "GT", "DO", "PR", "BZ", "NI", "SV", "GU", "VI", "AS",
            "MP", "UM");

    private PdfPageSizes() {}

    /**
     * {@code "letter"} or {@code "a4"} for an ISO 3166 region code such as {@code "MX"} (case-insensitive).
     * An unknown region — null, blank, or the {@code C}/{@code POSIX} locale, which has none — keeps Letter,
     * the size every build before this one started with.
     */
    public static String forRegion(String region) {
        if (region == null || region.isBlank()) {
            return LETTER;
        }
        return LETTER_REGIONS.contains(region.strip().toUpperCase(Locale.ROOT)) ? LETTER : A4;
    }

    /**
     * The size for the region this JVM was started in. Read from {@code user.country}, not from
     * {@link Locale#getDefault()}: the application replaces the default locale with the chosen UI language,
     * which carries no region — a user in Mexico reading the interface in English is still on Letter paper.
     */
    public static String systemDefault() {
        String region = System.getProperty("user.country");
        if (region == null || region.isBlank()) {
            region = Locale.getDefault().getCountry();
        }
        return forRegion(region);
    }
}
