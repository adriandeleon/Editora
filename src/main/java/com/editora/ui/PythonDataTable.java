package com.editora.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * View as DataFrame and View as Array (PyCharm's data viewer): a pandas {@code DataFrame} or {@code Series}, or a numpy
 * array, of a Python program stopped in debugpy, as a table of its rows. Pure: the window ({@link DebugTableWindow})
 * evaluates {@link #expression} and shows what {@link #parse} and {@link #rows} make of the answer.
 *
 * <p>debugpy lists a DataFrame's attributes under it, not its rows, so the rows cannot come from its children the way
 * a list's elements do. Instead a small helper runs in the program ({@link #HELPER}): it takes one page of rows, turns
 * each cell into its text as {@code str()} writes it, and answers JSON — the index, the column names and dtypes, the
 * rows, and how many rows and columns there are in all. It is evaluated in debugpy's {@code clipboard} context, where
 * a string comes back whole (in the others, debugpy cuts it at 1,000 characters), written as a Python string literal.
 *
 * <p>An array of more than two dimensions is shown as rows of its last one, each row named by the indexes before it
 * ({@code (0, 2)}); a structured array's fields are its columns.
 */
final class PythonDataTable {

    private PythonDataTable() {}

    /** The helper's source: defines {@code _editora_table(o, start, count, maxColumns)}, which answers JSON. */
    static final String HELPER = """
            def _editora_table(o, s, n, mc):
                import json, sys
                def cell(v):
                    t = str(v)
                    return t if len(t) <= 1000 else t[:1000] + '...'
                pd = sys.modules.get('pandas')
                np = sys.modules.get('numpy')
                out = {'kind': type(o).__name__}
                if pd is not None and isinstance(o, pd.DataFrame):
                    page = o.iloc[s:s + n, :mc]
                    out['shape'] = list(o.shape)
                    out['columns'] = [str(c) for c in page.columns]
                    out['dtypes'] = [str(t) for t in page.dtypes]
                    out['index'] = [str(i) for i in page.index]
                    out['rows'] = [[cell(v) for v in r] for r in page.itertuples(index=False, name=None)]
                    out['total'], out['width'] = o.shape
                elif pd is not None and isinstance(o, pd.Series):
                    page = o.iloc[s:s + n]
                    out['shape'] = [len(o)]
                    out['leaf'] = True
                    out['columns'] = ['' if o.name is None else str(o.name)]
                    out['dtypes'] = [str(o.dtype)]
                    out['index'] = [str(i) for i in page.index]
                    out['rows'] = [[cell(v)] for v in page]
                    out['total'], out['width'] = len(o), 1
                elif np is not None and isinstance(o, np.ndarray):
                    out['shape'] = list(o.shape)
                    if o.ndim == 0:
                        out['leaf'] = True
                        out['columns'] = ['']
                        out['dtypes'] = [str(o.dtype)]
                        out['index'] = ['0'] if s == 0 else []
                        out['rows'] = [[cell(o.item())]] if s == 0 else []
                        out['total'], out['width'] = 1, 1
                    elif o.dtype.names:
                        flat = o.reshape(-1)
                        names = list(o.dtype.names)[:mc]
                        out['columns'] = names
                        out['dtypes'] = [str(o.dtype.fields[k][0]) for k in names]
                        out['index'] = [str(i) for i in range(s, min(s + n, flat.shape[0]))]
                        out['rows'] = [[cell(r[k]) for k in names] for r in flat[s:s + n]]
                        out['total'], out['width'] = flat.shape[0], len(o.dtype.names)
                    elif o.ndim == 1:
                        out['leaf'] = True
                        out['columns'] = ['']
                        out['dtypes'] = [str(o.dtype)]
                        out['index'] = [str(i) for i in range(s, min(s + n, o.shape[0]))]
                        out['rows'] = [[cell(v)] for v in o[s:s + n]]
                        out['total'], out['width'] = o.shape[0], 1
                    else:
                        m = o.reshape(-1, o.shape[-1])
                        lead = o.shape[:-1]
                        out['columns'] = [str(j) for j in range(min(o.shape[-1], mc))]
                        out['dtypes'] = [str(o.dtype)] * len(out['columns'])
                        rows = range(s, min(s + n, m.shape[0]))
                        out['index'] = [str(i) if len(lead) == 1
                                        else str(tuple(int(x) for x in np.unravel_index(i, lead))) for i in rows]
                        out['rows'] = [[cell(v) for v in m[i, :mc]] for i in rows]
                        out['total'], out['width'] = m.shape[0], o.shape[-1]
                else:
                    out['error'] = 'unsupported'
                return json.dumps(out)
            """;

    /** What the helper said of one page: {@code error} is set (and the rest empty) when it could not show the value. */
    record Page(
            String kind,
            List<Long> shape,
            boolean leaf,
            List<String> columns,
            List<String> dtypes,
            List<String> index,
            List<List<String>> rows,
            long total,
            long width,
            @Nullable String error) {

        /** The shape as it is written: {@code 1000 × 4}. */
        String shapeText() {
            List<String> dims = new ArrayList<>();
            for (Long d : shape) {
                dims.add(String.valueOf(d));
            }
            return String.join(" × ", dims);
        }
    }

    /** The answer that is not the helper's, or that it could not read. */
    static Page failed(String why) {
        return new Page("", List.of(), false, List.of(), List.of(), List.of(), List.of(), 0, 0, why);
    }

    /**
     * Whether a value of {@code type} in {@code language} is shown by the helper: a pandas DataFrame (or one of its
     * kind: a GeoDataFrame), a Series, or a numpy array.
     */
    static boolean viewable(@Nullable String language, @Nullable String type) {
        if (!"python".equals(language) || type == null) {
            return false;
        }
        String t = type.strip();
        return t.endsWith("DataFrame")
                || t.endsWith("Series")
                || t.equals("ndarray")
                || t.equals("matrix")
                || t.equals("MaskedArray")
                || t.equals("memmap");
    }

    /** Whether the menu says View as DataFrame (a DataFrame or Series), else View as Array. */
    static boolean frame(String type) {
        return type.strip().endsWith("DataFrame") || type.strip().endsWith("Series");
    }

    /**
     * The Python expression answering the page of {@code count} rows from {@code start} of {@code target} (an
     * expression in the stopped frame): it defines the helper in a dictionary of its own, so nothing of the program's
     * is touched, and calls it.
     */
    static String expression(String target, int start, int count) {
        return "(lambda g: (exec(" + pythonString(HELPER) + ", g), g['_editora_table'](("
                + target + "), " + Math.max(0, start) + ", " + Math.max(0, count) + ", "
                + ValueTable.MAX_COLUMNS + "))[1])({})";
    }

    /** {@code text} as a Python string literal. */
    static String pythonString(String text) {
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    /**
     * The text a Python string literal stands for ({@code 'it\'s'} is {@code it's}); text that is not a literal is
     * returned as it is.
     */
    static String unrepr(String literal) {
        String s = literal.strip();
        if (s.length() >= 2 && s.charAt(0) == s.charAt(s.length() - 1) && (s.charAt(0) == '\'' || s.charAt(0) == '"')) {
            s = s.substring(1, s.length() - 1);
        } else {
            return literal;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'x', 'u', 'U' -> {
                    int digits = e == 'x' ? 2 : e == 'u' ? 4 : 8;
                    Integer code = i + digits < s.length() ? hex(s.substring(i + 1, i + 1 + digits)) : null;
                    if (code != null && Character.isValidCodePoint(code)) {
                        out.appendCodePoint(code);
                        i += digits;
                    } else {
                        out.append('\\').append(e);
                    }
                }
                case '\\', '\'', '"' -> out.append(e);
                default -> out.append('\\').append(e); // as Python reads an escape it does not know
            }
        }
        return out.toString();
    }

    private static @Nullable Integer hex(String digits) {
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@link #parse} off the FX thread (PERF-01, Pauta 6.0.1), then {@code then} with the page on the FX thread. */
    static void parseLater(@Nullable String result, java.util.function.Consumer<Page> then) {
        com.editora.perf.OffFx.then("debug-table-page", () -> parse(result), a -> {
            Page page = a.value();
            then.accept(page != null ? page : parse(null));
        });
    }

    /** The page the helper answered, from debugpy's {@code result} (a Python string literal holding JSON). */
    static Page parse(@Nullable String result) {
        if (result == null || result.isBlank()) {
            return failed("");
        }
        JsonNode root;
        try {
            root = JSON.readTree(unrepr(result));
        } catch (Exception e) {
            return failed(result.strip());
        }
        if (root == null || !root.isObject()) {
            return failed(result.strip());
        }
        String kind = root.path("kind").asText("");
        if (root.hasNonNull("error")) {
            return new Page(
                    kind,
                    List.of(),
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    0,
                    root.get("error").asText());
        }
        List<Long> shape = new ArrayList<>();
        root.path("shape").forEach(n -> shape.add(n.asLong()));
        List<List<String>> rows = new ArrayList<>();
        root.path("rows").forEach(r -> rows.add(texts(r)));
        return new Page(
                kind,
                List.copyOf(shape),
                root.path("leaf").asBoolean(false),
                texts(root.path("columns")),
                texts(root.path("dtypes")),
                texts(root.path("index")),
                List.copyOf(rows),
                root.path("total").asLong(rows.size()),
                root.path("width").asLong(0),
                null);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.isNull() ? "" : n.asText()));
        return List.copyOf(out);
    }

    /**
     * The page's columns as the table names them: each one once — a second {@code a} is {@code a (2)} — and never the
     * table's own {@code #} or the blank name of its value column.
     */
    static List<String> columnNames(List<String> columns) {
        Set<String> taken = new HashSet<>(Set.of(ValueTable.KEY_COLUMN, ValueTable.VALUE_COLUMN));
        List<String> out = new ArrayList<>();
        for (String c : columns) {
            String base = c.isBlank() ? "(" + (out.size()) + ")" : c;
            String name = base;
            for (int n = 2; taken.contains(name); n++) {
                name = base + " (" + n + ")";
            }
            taken.add(name);
            out.add(name);
        }
        return List.copyOf(out);
    }

    /** The page's rows for the table: a Series' or a one-dimensional array's as values, the rest by column. */
    static List<ValueTable.Row> rows(Page page) {
        List<String> names = columnNames(page.columns());
        List<ValueTable.Row> out = new ArrayList<>();
        for (int r = 0; r < page.rows().size(); r++) {
            List<String> cells = page.rows().get(r);
            String key = r < page.index().size() ? page.index().get(r) : String.valueOf(r);
            if (page.leaf()) {
                out.add(new ValueTable.Row(key, cells.isEmpty() ? "" : cells.getFirst(), Map.of()));
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (int c = 0; c < names.size(); c++) {
                fields.put(names.get(c), c < cells.size() ? cells.get(c) : "");
            }
            out.add(new ValueTable.Row(key, "", fields));
        }
        return out;
    }

    /** Each column's dtype, by the name the table gives the column. */
    static Map<String, String> dtypes(Page page) {
        List<String> names = columnNames(page.columns());
        Map<String, String> out = new LinkedHashMap<>();
        for (int c = 0; c < names.size() && c < page.dtypes().size(); c++) {
            out.put(names.get(c), page.dtypes().get(c));
        }
        return out;
    }
}
