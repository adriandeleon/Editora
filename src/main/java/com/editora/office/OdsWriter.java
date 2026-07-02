package com.editora.office;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes parsed CSV/TSV rows to an OpenDocument Spreadsheet ({@code .ods}) — the LibreOffice Calc /
 * OpenDocument counterpart to Excel. Hand-rolled as a ZIP of XML (the {@link OdtWriter} idiom: {@code
 * mimetype} STORED first, then {@code content.xml} + {@code META-INF/manifest.xml}) so it needs no ODF
 * Toolkit dependency. A cell that {@link CsvValues#numericValue} judges safe is written as an
 * {@code office:value-type="float"} cell; everything else is a string. The header row (when {@code
 * hasHeader}) is bold. {@link #contentXml} is pure and unit-tested.
 */
public final class OdsWriter {

    static final String MIMETYPE = "application/vnd.oasis.opendocument.spreadsheet";

    private static final String MANIFEST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\""
            + " manifest:version=\"1.2\">"
            + "<manifest:file-entry manifest:full-path=\"/\" manifest:media-type=\"" + MIMETYPE + "\"/>"
            + "<manifest:file-entry manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/>"
            + "</manifest:manifest>";

    private OdsWriter() {}

    public static void write(List<List<String>> rows, boolean hasHeader, Path out) throws IOException {
        String content = contentXml(rows, hasHeader);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            // The ODF spec requires `mimetype` to be the first entry and STORED (uncompressed).
            stored(zip, "mimetype", MIMETYPE.getBytes(StandardCharsets.US_ASCII));
            deflated(zip, "content.xml", content.getBytes(StandardCharsets.UTF_8));
            deflated(zip, "META-INF/manifest.xml", MANIFEST.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Builds the ODF {@code content.xml} for a single-sheet spreadsheet. Pure — unit-tested. */
    static String contentXml(List<List<String>> rows, boolean hasHeader) {
        int cols = 0;
        for (List<String> r : rows) {
            cols = Math.max(cols, r.size());
        }
        cols = Math.max(1, cols);

        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        b.append("<office:document-content")
                .append(" xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\"")
                .append(" xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\"")
                .append(" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"")
                .append(" xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\"")
                .append(" xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\"")
                .append(" office:version=\"1.2\">");
        b.append("<office:automatic-styles>")
                .append("<style:style style:name=\"ceHeader\" style:family=\"table-cell\">")
                .append("<style:text-properties fo:font-weight=\"bold\"/></style:style>")
                .append("</office:automatic-styles>");
        b.append("<office:body><office:spreadsheet>");
        b.append("<table:table table:name=\"Sheet1\">");
        b.append("<table:table-column table:number-columns-repeated=\"")
                .append(cols)
                .append("\"/>");
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            boolean header = r == 0 && hasHeader;
            b.append("<table:table-row>");
            for (int c = 0; c < cols; c++) {
                String v = c < cells.size() ? cells.get(c) : "";
                Double num = header ? null : CsvValues.numericValue(v);
                if (num != null) {
                    b.append("<table:table-cell office:value-type=\"float\" office:value=\"")
                            .append(num)
                            .append("\"><text:p>")
                            .append(esc(v))
                            .append("</text:p></table:table-cell>");
                } else {
                    b.append("<table:table-cell");
                    if (header) {
                        b.append(" table:style-name=\"ceHeader\"");
                    }
                    b.append(" office:value-type=\"string\"><text:p>")
                            .append(esc(v))
                            .append("</text:p></table:table-cell>");
                }
            }
            b.append("</table:table-row>");
        }
        b.append("</table:table></office:spreadsheet></office:body></office:document-content>");
        return b.toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void stored(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    private static void deflated(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.DEFLATED);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}
