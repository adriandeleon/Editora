package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;

import com.editora.csv.CsvParser;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.Document;
import org.commonmark.node.Node;
import org.commonmark.node.Text;

/**
 * Builds a CommonMark table <em>document</em> straight from CSV rows, for the CSV → PDF / Print path.
 *
 * <p>That path used to go CSV → Markdown text → Markdown parser → renderer, so every cell was re-read as
 * Markdown: {@code __init__} printed as a bold {@code init}, {@code 2*3*4} as {@code 234},
 * {@code List<String>} as {@code List} and {@code C:\\srv} as {@code C:\srv}. A CSV cell is data, not markup.
 * Here each cell becomes a single literal {@link Text} node, so the PDF and print renderers — which consume
 * the node tree — show it character for character.
 *
 * <p>Rows come from {@link CsvParser#parse(String)}, the same parser (and delimiter sniffing: comma,
 * semicolon, tab <em>and pipe</em>, read from the first non-blank line) the CSV grid uses, so the export has
 * the columns the grid shows. Pure and toolkit-free.
 */
public final class CsvTableDocument {

    private CsvTableDocument() {}

    /** The table document for {@code csv} (first record = header row), or {@code null} when it has no rows. */
    public static Node fromCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>(CsvParser.parse(csv));
        while (!rows.isEmpty() && rows.get(rows.size() - 1).stream().allMatch(String::isBlank)) {
            rows.remove(rows.size() - 1); // drop trailing blank records
        }
        return rows.isEmpty() ? null : fromRows(rows);
    }

    /** A document holding one table: {@code rows.get(0)} is the header, short rows are padded. */
    public static Node fromRows(List<List<String>> rows) {
        return fromRows(rows.get(0), rows.subList(1, rows.size()));
    }

    /**
     * A document holding one table of {@code rows} in the order given, under {@code header} — or with no
     * header row at all when that is null (the CSV grid with "first row is a header" off: every row is
     * data). Short rows are padded to the widest. Null when there is neither a header nor a row.
     */
    public static Node fromRows(List<String> header, List<List<String>> rows) {
        if (header == null && rows.isEmpty()) {
            return null;
        }
        int columns = Math.max(header == null ? 0 : header.size(), CsvParser.columnCount(rows));
        TableBlock table = new TableBlock();
        if (header != null) {
            TableHead head = new TableHead();
            head.appendChild(row(header, columns, true));
            table.appendChild(head);
        }
        if (!rows.isEmpty()) {
            TableBody body = new TableBody();
            for (List<String> cells : rows) {
                body.appendChild(row(cells, columns, false));
            }
            table.appendChild(body);
        }
        Document document = new Document();
        document.appendChild(table);
        return document;
    }

    private static TableRow row(List<String> cells, int columns, boolean header) {
        TableRow row = new TableRow();
        for (int c = 0; c < columns; c++) {
            TableCell cell = new TableCell();
            cell.setHeader(header);
            String value = c < cells.size() ? cells.get(c) : "";
            // A quoted multi-line field stays one cell: its line breaks become spaces.
            value = value.replace("\r\n", " ")
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .strip();
            if (!value.isEmpty()) {
                cell.appendChild(new Text(value));
            }
            row.appendChild(cell);
        }
        return row;
    }
}
